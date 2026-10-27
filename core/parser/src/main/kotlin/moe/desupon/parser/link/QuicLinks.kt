package moe.desupon.parser.link

import kotlinx.serialization.json.JsonObject
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.JsonBuilder
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.XrayFinalMask
import moe.desupon.parser.internal.asObj
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.parseJsonOrNull
import moe.desupon.parser.internal.splitList

internal object QuicLinks {
    /** `hysteria2://auth@host:port[,ports]?obfs=salamander&obfs-password=…&sni=…&insecure=1&mport=…[&fm=<xray finalmask>]`. */
    fun hysteria2(u: ShareUrl): Candidate {
        val issues = Issues()
        val (port, extraPorts) = ports(u.portSpec)
        val mask = u.q("fm")?.let { XrayFinalMask.hysteria(parseJsonOrNull(it).asObj() ?: malformed("fm is not a JSON object"), issues) }
        val hop = (extraPorts + portRanges(u.q("mport", "ports")) + mask?.hopPorts.orEmpty()).distinct()
        val pin = u.q("pinsha256")
        val insecure = u.flag("insecure", "allowinsecure", "allow_insecure")
        // A hysteria pin hashes the certificate, sing-box pins the SPKI: dropping it is only safe while the chain is still verified.
        if (pin != null && insecure) issues.unsupported("certificate pin with insecure TLS")
        val out = jsonObj {
            str("type", "hysteria2")
            str("server", u.requireHost())
            num("server_port", port)
            strings("server_ports", hop)
            hopInterval(u.q("hopinterval", "hop_interval", "mporthopint") ?: mask?.hopInterval)
            str("hop_interval_max", mask?.hopIntervalMax)
            str("password", u.userInfo?.takeIf { it.isNotEmpty() } ?: u.q("auth", "password"))
            (mbps(u.q("upmbps", "up")) ?: mask?.upMbps)?.let { num("up_mbps", it) }
            (mbps(u.q("downmbps", "down")) ?: mask?.downMbps)?.let { num("down_mbps", it) }
            str("bbr_profile", mask?.bbrProfile)
            // Panels write obfs=salamander for a gecko mask too; the finalmask holds the server's actual setting.
            val password = u.q("obfs-password", "obfs_password", "obfspassword")
            el("obfs", mask?.obfs ?: obfs(u.q("obfs"), password, u.q("minpacketsize"), u.q("maxpacketsize"), issues))
            obj("tls") {
                bool("enabled", true)
                str("server_name", u.q("sni", "peer"))
                flag("insecure", insecure)
                strings("alpn", quicAlpn(u.q("alpn")))
            }
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    /** Hysteria 2 `obfs` from link or mihomo fields; packet sizes only exist for gecko. */
    fun obfs(type: String?, password: String?, minPacket: String?, maxPacket: String?, issues: Issues): JsonObject? =
        when (val t = type?.lowercase()) {
            null, "", "none", "plain" -> null
            "salamander" -> XrayFinalMask.obfs(t, password ?: malformed("$t without password"))
            "gecko" -> XrayFinalMask.obfs(t, password ?: malformed("$t without password"), minPacket?.toIntOrNull(), maxPacket?.toIntOrNull())
            else -> {
                issues.unsupported("hysteria2 obfs $t")
                null
            }
        }

    /** Hysteria v1: `hysteria://host:port?protocol=udp&auth=…&peer=…&upmbps=…&downmbps=…&obfsParam=…`. */
    fun hysteria(u: ShareUrl): Candidate {
        val issues = Issues()
        val protocol = u.q("protocol")?.lowercase() ?: "udp"
        if (protocol != "udp") issues.unsupported("hysteria protocol $protocol")
        val (port, extraPorts) = ports(u.portSpec)
        val out = jsonObj {
            str("type", "hysteria")
            str("server", u.requireHost())
            num("server_port", port)
            strings("server_ports", (extraPorts + portRanges(u.q("mport"))).distinct())
            num("up_mbps", mbps(u.q("upmbps", "up")) ?: DEFAULT_UP_MBPS)
            num("down_mbps", mbps(u.q("downmbps", "down")) ?: DEFAULT_DOWN_MBPS)
            str("obfs", u.q("obfsparam", "obfs-password").takeIf { u.q("obfs").let { it == null || it == "xplus" } })
            str("auth_str", u.q("auth", "auth_str") ?: u.userInfo?.takeIf { it.isNotEmpty() })
            obj("tls") {
                bool("enabled", true)
                str("server_name", u.q("peer", "sni"))
                flag("insecure", u.flag("insecure", "allowinsecure"))
                strings("alpn", u.q("alpn")?.let(::splitList))
            }
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    /** `tuic://uuid:password@host:port?congestion_control=…&udp_relay_mode=…&alpn=…&sni=…`. */
    fun tuic(u: ShareUrl): Candidate {
        val issues = Issues()
        val info = u.userInfo ?: malformed("missing credentials")
        val c = info.indexOf(':')
        if (c <= 0) issues.unsupported("TUIC v4 token auth")
        val out = jsonObj {
            str("type", "tuic")
            str("server", u.requireHost())
            num("server_port", u.requirePort(default = 443))
            str("uuid", if (c > 0) info.substring(0, c) else info)
            str("password", if (c > 0) info.substring(c + 1) else null)
            str("congestion_control", u.q("congestion_control", "congestion-control", "congestion")?.lowercase())
            str("udp_relay_mode", u.q("udp_relay_mode", "udp-relay-mode")?.lowercase())
            flag("zero_rtt_handshake", u.flag("reduce_rtt", "zero_rtt", "zero_rtt_handshake"))
            obj("tls") {
                bool("enabled", true)
                str("server_name", u.q("sni", "peer"))
                flag("insecure", u.flag("allow_insecure", "insecure", "allowinsecure"))
                flag("disable_sni", u.flag("disable_sni"))
                strings("alpn", u.q("alpn")?.let(::splitList))
            }
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    /** `juicity://uuid:password@host:port?congestion_control=bbr&sni=…&allow_insecure=1&pinned_certchain_sha256=…`. */
    fun juicity(u: ShareUrl): Candidate {
        val issues = Issues()
        val info = u.userInfo ?: malformed("missing credentials")
        val c = info.indexOf(':')
        if (c <= 0) malformed("missing password")
        val congestion = u.q("congestion_control")?.lowercase()
        if (congestion != null && congestion !in JUICITY_CONGESTION) issues.unsupported("juicity congestion control $congestion")
        val pin = u.qKey("pinned_certchain_sha256")
        val out = jsonObj {
            str("type", "juicity")
            str("server", u.requireHost())
            num("server_port", u.requirePort(default = 443))
            str("uuid", info.substring(0, c))
            str("password", info.substring(c + 1))
            str("congestion_control", congestion)
            str("pinned_certchain_sha256", pin)
            obj("tls") {
                bool("enabled", true)
                str("server_name", u.q("sni", "peer"))
                // Generated links set allow_insecure next to the pin, which then replaces CA verification.
                flag("insecure", pin == null && u.flag("allow_insecure", "insecure", "allowinsecure"))
            }
        }
        return Candidate(out, u.fragment, issues.reason)
    }

    private val JUICITY_CONGESTION = setOf("bbr", "cubic", "new_reno")

    const val DEFAULT_UP_MBPS = 10
    const val DEFAULT_DOWN_MBPS = 50

    /** URL port spec `443` or `443,20000-30000`: first single port plus sing-box `a:b` hop ranges. */
    fun ports(spec: String?): Pair<Int, List<String>> {
        if (spec.isNullOrBlank()) return 443 to emptyList()
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val main = parts.firstNotNullOfOrNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }
        val ranges = portRanges(spec)
        if (main == null) {
            val first = ranges.firstOrNull() ?: malformed("bad port")
            return first.substringBefore(':').toInt() to ranges
        }
        return main to if (parts.size > 1) ranges else emptyList()
    }

    fun portRanges(spec: String?): List<String> {
        if (spec.isNullOrBlank()) return emptyList()
        return spec.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
            val r = p.replace(':', '-').split('-').map { it.trim().toIntOrNull()?.takeIf { v -> v in 1..65535 } ?: malformed("bad port range") }
            when (r.size) {
                1 -> "${r[0]}:${r[0]}"
                2 -> if (r[0] <= r[1]) "${r[0]}:${r[1]}" else malformed("bad port range")
                else -> malformed("bad port range")
            }
        }
    }

    /** `100`, `100 Mbps`, `1 Gbps` → Mbps. */
    fun mbps(raw: String?): Int? {
        val s = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val num = Regex("^([0-9]+(?:\\.[0-9]+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val unit = s.substring(s.indexOfFirst { !it.isDigit() && it != '.' }.takeIf { it >= 0 } ?: s.length).trim()
        val v = when {
            unit.startsWith("g") -> num * 1000
            unit.startsWith("k") -> num / 1000
            else -> num
        }
        return v.toInt().takeIf { it > 0 }
    }

    /** Hysteria2 negotiates h3; the share-link ALPN list is narrowed to it when present. */
    fun quicAlpn(raw: String?): List<String>? {
        val list = raw?.let(::splitList).orEmpty()
        return when {
            list.isEmpty() -> null
            "h3" in list -> listOf("h3")
            else -> list
        }
    }

    private fun JsonBuilder.hopInterval(raw: String?) {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
        str("hop_interval", if (s.all { it.isDigit() }) "${s}s" else s)
    }
}
