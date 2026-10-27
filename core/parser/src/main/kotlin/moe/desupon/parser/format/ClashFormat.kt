package moe.desupon.parser.format

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.desupon.parser.SkipReason
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Chain
import moe.desupon.parser.internal.Entry
import moe.desupon.parser.internal.Hop
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.V2Stream
import moe.desupon.parser.internal.asArr
import moe.desupon.parser.internal.asObj
import moe.desupon.parser.internal.bool
import moe.desupon.parser.internal.child
import moe.desupon.parser.internal.entry
import moe.desupon.parser.internal.int
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.str
import moe.desupon.parser.internal.strList
import moe.desupon.parser.link.QuicLinks
import moe.desupon.parser.link.ShadowsocksLink
import moe.desupon.parser.link.V2rayLinks
import moe.desupon.parser.link.WgConf
import moe.desupon.parser.link.WgPeer

/** Clash / mihomo `proxies:` list. */
internal object ClashFormat {
    private val PROXIES = Regex("(?m)^proxies\\s*:")

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
            anchorsAndAliases = AnchorsAndAliases.Permitted(),
        ),
    )

    fun looksLike(text: String): Boolean = PROXIES.containsMatchIn(text)

    /** Null when the document is not readable YAML with a `proxies` list. */
    fun parse(text: String): List<Entry>? {
        val root = try {
            toJson(yaml.parseToYamlNode(text)).asObj()
        } catch (_: Exception) {
            null
        } ?: return null
        val proxies = root["proxies"].asArr() ?: return null
        val byName = proxies.mapNotNull { it.asObj() }.filter { it.str("name") != null }.associateBy { it.str("name")!! }
        val groups = root["proxy-groups"].asArr().orEmpty().mapNotNull { it.asObj()?.str("name") }.toSet()
        val hop = { name: String -> hop(name, byName, groups) }
        return proxies.map { el ->
            val p = el.asObj() ?: return@map Entry.Skip(null, SkipReason.MALFORMED)
            val type = p.str("type")?.lowercase()
            if (type !in TYPES) return@map Entry.Skip(type, if (type == null) SkipReason.MALFORMED else SkipReason.UNKNOWN_SCHEME)
            entry(type) { listOf(Chain.resolve(proxy(p, type!!), p.str("name"), p.str("dialer-proxy"), hop)) }
        }
    }

    private fun hop(name: String, byName: Map<String, JsonObject>, groups: Set<String>): Hop? {
        val p = byName[name] ?: return when {
            name in groups -> Hop.Unsupported("dialer proxy through a proxy group")
            name.equals("DIRECT", ignoreCase = true) -> Hop.Direct()
            else -> null
        }
        val type = p.str("type")?.lowercase()
        if (type == "direct") return Hop.Direct(p.str("dialer-proxy"))
        if (type !in TYPES) return Hop.Unsupported("dialer proxy to ${type ?: "unknown"}")
        return Hop.Proxy(proxy(p, type!!), p.str("dialer-proxy"))
    }

    private val TYPES = setOf("ss", "vmess", "vless", "trojan", "hysteria2", "hy2", "hysteria", "tuic", "wireguard")

    private fun toJson(node: YamlNode): JsonElement = when (node) {
        is YamlScalar -> JsonPrimitive(node.content)
        is YamlNull -> JsonNull
        is YamlList -> JsonArray(node.items.map(::toJson))
        is YamlMap -> {
            val out = LinkedHashMap<String, JsonElement>()
            val merged = ArrayList<JsonObject>()
            for ((k, v) in node.entries) {
                val value = toJson(v)
                if (k.content == "<<") {
                    when (value) {
                        is JsonObject -> merged += value
                        is JsonArray -> value.forEach { (it as? JsonObject)?.let(merged::add) }
                        else -> {}
                    }
                } else {
                    out[k.content] = value
                }
            }
            // YAML merge keys never override explicit keys.
            for (m in merged) for ((k, v) in m) out.putIfAbsent(k, v)
            JsonObject(out)
        }
        is YamlTaggedNode -> toJson(node.innerNode)
    }

    private fun proxy(p: JsonObject, type: String): Candidate {
        val name = p.str("name")
        val server = p.str("server") ?: malformed("missing server")
        val port = p.int("port")?.takeIf { it in 1..65535 }
        val issues = Issues()
        val c = when (type) {
            "ss" -> ss(p, server, port ?: malformed("bad port"), name)
            "vmess", "vless", "trojan" -> v2ray(p, type, server, port ?: malformed("bad port"), name, issues)
            "hysteria2", "hy2" -> hysteria2(p, server, port, name, issues)
            "hysteria" -> hysteria(p, server, port, name, issues)
            "tuic" -> tuic(p, server, port ?: malformed("bad port"), name, issues)
            else -> wireguard(p, server, port ?: malformed("bad port"), name)
        }
        return if (issues.reason != null && c.unsupported == null) c.copy(unsupported = issues.reason) else c
    }

    private fun ss(p: JsonObject, server: String, port: Int, name: String?): Candidate {
        val plugin = p.str("plugin")?.let { pl ->
            val opts = p.child("plugin-opts")
            when (pl) {
                "obfs" -> "obfs-local;" + listOfNotNull(
                    opts?.str("mode")?.let { "obfs=$it" },
                    opts?.str("host")?.let { "obfs-host=$it" },
                ).joinToString(";")
                "v2ray-plugin" -> "v2ray-plugin;" + listOfNotNull(
                    opts?.str("mode")?.let { "mode=$it" },
                    "tls".takeIf { opts?.bool("tls") == true },
                    opts?.str("host")?.let { "host=$it" },
                    opts?.str("path")?.let { "path=$it" },
                ).joinToString(";")
                else -> pl
            }.trimEnd(';')
        }
        return ShadowsocksLink.build(
            method = p.str("cipher") ?: malformed("missing cipher"),
            password = p.str("password") ?: malformed("missing password"),
            host = server,
            port = port,
            plugin = plugin,
            name = name,
        )
    }

    private fun v2ray(p: JsonObject, type: String, server: String, port: Int, name: String?, issues: Issues): Candidate {
        val network = p.str("network")?.lowercase() ?: "tcp"
        val ws = p.child("ws-opts")
        val grpc = p.child("grpc-opts")
        val h2 = p.child("h2-opts")
        val xhttp = p.child("xhttp-opts")
        val reality = p.child("reality-opts")
        val tlsOn = p.bool("tls") == true || type == "trojan" || reality != null
        val stream = V2Stream(
            network = when {
                network == "ws" && ws?.bool("v2ray-http-upgrade") == true -> "httpupgrade"
                network == "http" -> if (p.child("http-opts") != null) "tcp" else "h2"
                else -> network
            },
            security = when {
                reality != null -> "reality"
                tlsOn -> "tls"
                else -> "none"
            },
            sni = p.str("servername", "sni"),
            alpn = p.strList("alpn"),
            fingerprint = p.str("client-fingerprint"),
            insecure = p.bool("skip-cert-verify") == true,
            ech = p.child("ech-opts")?.takeIf { it.bool("enable") == true }?.let { it.str("config") ?: "dns" },
            realityPublicKey = reality?.str("public-key"),
            realityShortId = reality?.str("short-id"),
            host = ws?.child("headers")?.str("Host", "host") ?: h2?.strList("host")?.joinToString(",")?.ifEmpty { null }
                ?: xhttp?.str("host"),
            path = ws?.let(::wsPath) ?: h2?.str("path") ?: xhttp?.str("path"),
            serviceName = grpc?.str("grpc-service-name"),
            mode = xhttp?.str("mode"),
            headerType = if (network == "http" && p.child("http-opts") != null) "http" else null,
            xhttp = xhttp,
        )
        val out = jsonObj {
            str("type", type)
            str("server", server)
            num("server_port", port)
            when (type) {
                "vless" -> {
                    str("uuid", p.str("uuid") ?: malformed("missing uuid"))
                    str("flow", p.str("flow"))
                    str("encryption", V2rayLinks.vlessEncryption(p.str("encryption"), issues))
                }
                "vmess" -> {
                    str("uuid", p.str("uuid") ?: malformed("missing uuid"))
                    str("security", V2rayLinks.vmessSecurity(p.str("cipher")))
                    p.int("alterId")?.takeIf { it > 0 }?.let { num("alter_id", it) }
                }
                else -> str("password", p.str("password") ?: malformed("missing password"))
            }
            el("tls", stream.tls(issues))
            el("transport", stream.transport(issues))
        }
        return Candidate(out, name, issues.reason)
    }

    /** Early data rides the path as `?ed=…&eh=…`, the form [V2Stream] already unpacks for links. */
    private fun wsPath(ws: JsonObject): String? {
        val path = ws.str("path")
        val ed = ws.int("max-early-data")?.takeIf { it > 0 } ?: return path
        val eh = ws.str("early-data-header-name")?.let { "&eh=$it" }.orEmpty()
        return (path ?: "/") + (if ('?' in (path ?: "")) "&" else "?") + "ed=$ed$eh"
    }

    private fun hysteria2(p: JsonObject, server: String, port: Int?, name: String?, issues: Issues): Candidate {
        val hop = QuicLinks.portRanges(p.str("ports"))
        val obfs = p.str("obfs")?.lowercase()
        val out = jsonObj {
            str("type", "hysteria2")
            str("server", server)
            num("server_port", port ?: hop.firstOrNull()?.substringBefore(':')?.toInt() ?: malformed("bad port"))
            strings("server_ports", hop)
            str("password", p.str("password", "auth"))
            QuicLinks.mbps(p.str("up"))?.let { num("up_mbps", it) }
            QuicLinks.mbps(p.str("down"))?.let { num("down_mbps", it) }
            el("obfs", QuicLinks.obfs(obfs, p.str("obfs-password"), p.str("obfs-min-packet-size"), p.str("obfs-max-packet-size"), issues))
            obj("tls") {
                bool("enabled", true)
                str("server_name", p.str("sni", "servername"))
                flag("insecure", p.bool("skip-cert-verify") == true)
                strings("alpn", QuicLinks.quicAlpn(p.strList("alpn").joinToString(",").ifEmpty { null }))
            }
        }
        return Candidate(out, name)
    }

    private fun hysteria(p: JsonObject, server: String, port: Int?, name: String?, issues: Issues): Candidate {
        val protocol = p.str("protocol")?.lowercase() ?: "udp"
        if (protocol != "udp") issues.unsupported("hysteria protocol $protocol")
        val hop = QuicLinks.portRanges(p.str("ports"))
        val out = jsonObj {
            str("type", "hysteria")
            str("server", server)
            num("server_port", port ?: hop.firstOrNull()?.substringBefore(':')?.toInt() ?: malformed("bad port"))
            strings("server_ports", hop)
            num("up_mbps", QuicLinks.mbps(p.str("up")) ?: QuicLinks.DEFAULT_UP_MBPS)
            num("down_mbps", QuicLinks.mbps(p.str("down")) ?: QuicLinks.DEFAULT_DOWN_MBPS)
            str("obfs", p.str("obfs"))
            str("auth_str", p.str("auth-str", "auth_str"))
            str("auth", p.str("auth"))
            obj("tls") {
                bool("enabled", true)
                str("server_name", p.str("sni", "servername"))
                flag("insecure", p.bool("skip-cert-verify") == true)
                strings("alpn", p.strList("alpn"))
            }
        }
        return Candidate(out, name)
    }

    private fun tuic(p: JsonObject, server: String, port: Int, name: String?, issues: Issues): Candidate {
        if (p.str("token") != null && p.str("uuid") == null) issues.unsupported("TUIC v4 token auth")
        val out = jsonObj {
            str("type", "tuic")
            str("server", server)
            num("server_port", port)
            str("uuid", p.str("uuid"))
            str("password", p.str("password"))
            str("congestion_control", p.str("congestion-controller")?.lowercase())
            str("udp_relay_mode", p.str("udp-relay-mode")?.lowercase())
            flag("zero_rtt_handshake", p.bool("reduce-rtt") == true)
            obj("tls") {
                bool("enabled", true)
                str("server_name", p.str("sni", "servername"))
                flag("insecure", p.bool("skip-cert-verify") == true)
                flag("disable_sni", p.bool("disable-sni") == true)
                strings("alpn", p.strList("alpn"))
            }
        }
        return Candidate(out, name)
    }

    private fun wireguard(p: JsonObject, server: String, port: Int, name: String?): Candidate {
        val peers = p["peers"].asArr()?.mapNotNull { it.asObj() }?.takeIf { it.isNotEmpty() }?.map { peer ->
            WgPeer(
                host = peer.str("server") ?: server,
                port = peer.int("port") ?: port,
                publicKey = peer.str("public-key") ?: malformed("peer without public key"),
                preSharedKey = peer.str("pre-shared-key"),
                allowedIps = peer.strList("allowed-ips"),
                keepalive = null,
            )
        } ?: listOf(
            WgPeer(
                host = server,
                port = port,
                publicKey = p.str("public-key") ?: malformed("missing public key"),
                preSharedKey = p.str("pre-shared-key"),
                allowedIps = p.strList("allowed-ips"),
                keepalive = p.int("persistent-keepalive"),
            ),
        )
        val awg = p.child("amnezia-wg-option")?.entries?.associate { (k, v) ->
            moe.desupon.parser.internal.looseKey(k) to (v as? JsonPrimitive)?.content.orEmpty()
        }.orEmpty()
        return WgConf(
            privateKey = p.str("private-key") ?: malformed("missing private key"),
            addresses = listOfNotNull(p.str("ip"), p.str("ipv6")),
            mtu = p.int("mtu"),
            peers = peers,
            amnezia = awg.filterKeys { it in WgConf.AMNEZIA_KEYS },
            name = name,
        ).toCandidate()
    }
}
