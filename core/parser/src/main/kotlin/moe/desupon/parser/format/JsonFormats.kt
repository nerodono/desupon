package moe.desupon.parser.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.desupon.parser.SkipReason
import moe.desupon.parser.internal.Candidate
import moe.desupon.parser.internal.Chain
import moe.desupon.parser.internal.Entry
import moe.desupon.parser.internal.Hop
import moe.desupon.parser.internal.Issues
import moe.desupon.parser.internal.Normalizer
import moe.desupon.parser.internal.V2Stream
import moe.desupon.parser.internal.XrayFinalMask
import moe.desupon.parser.internal.asArr
import moe.desupon.parser.internal.asObj
import moe.desupon.parser.internal.bool
import moe.desupon.parser.internal.child
import moe.desupon.parser.internal.entry
import moe.desupon.parser.internal.int
import moe.desupon.parser.internal.jsonObj
import moe.desupon.parser.internal.malformed
import moe.desupon.parser.internal.scalar
import moe.desupon.parser.internal.str
import moe.desupon.parser.internal.strList
import moe.desupon.parser.internal.with
import moe.desupon.parser.internal.without
import moe.desupon.parser.link.QuicLinks
import moe.desupon.parser.link.ShadowsocksLink
import moe.desupon.parser.link.V2rayLinks
import moe.desupon.parser.link.WgConf
import moe.desupon.parser.link.WgPeer

internal object SingBoxFormat {
    val PROXY_TYPES = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "tuic", "juicity", "anytls", "socks", "http", "wireguard")
    private val NON_PROXY = setOf("direct", "block", "dns", "selector", "urltest", "fallback", "failover", "loadbalance")

    fun isConfig(root: JsonElement): Boolean = when (root) {
        is JsonObject -> isSingle(root) ||
            (root["outbounds"] is JsonArray || root["endpoints"] is JsonArray) && outboundsOf(root).none { it["protocol"] != null }
        is JsonArray -> root.isNotEmpty() && root.all { it.asObj()?.let { o -> o["type"] != null && o["protocol"] == null } == true }
        else -> false
    }

    // A lone outbound object, as pasted from another client.
    private fun isSingle(root: JsonObject): Boolean = root.str("type")?.lowercase() in PROXY_TYPES && root["outbounds"] == null

    private fun outboundsOf(root: JsonObject): List<JsonObject> =
        (root["outbounds"].asArr().orEmpty() + root["endpoints"].asArr().orEmpty()).mapNotNull { it.asObj() }

    fun parse(root: JsonElement): List<Entry> {
        val single = root is JsonObject && isSingle(root)
        val items = when (root) {
            is JsonObject -> if (single) listOf(root) else outboundsOf(root)
            is JsonArray -> root.mapNotNull { it.asObj() }
            else -> emptyList()
        }
        val byTag = items.filter { it.str("tag") != null }.associateBy { it.str("tag")!! }
        return items.map { o ->
            val type = o.str("type")?.lowercase()
            when {
                type == null -> Entry.Skip(null, SkipReason.MALFORMED)
                type in NON_PROXY -> Entry.Nodes(emptyList())
                type !in PROXY_TYPES -> Entry.Skip(type, SkipReason.UNKNOWN_SCHEME)
                // A lone outbound has nothing to resolve its detour against; Normalizer reports it.
                single -> entry(type) { listOf(outbound(o, type, keepDetour = true)) }
                else -> entry(type) {
                    listOf(Chain.resolve(outbound(o, type), o.str("tag"), o.str("detour")) { tag -> byTag[tag]?.let(::hop) })
                }
            }
        }
    }

    private fun hop(o: JsonObject): Hop = when (val type = o.str("type")?.lowercase()) {
        in PROXY_TYPES -> Hop.Proxy(outbound(o, type!!), o.str("detour"))
        "direct" -> Hop.Direct(o.str("detour"))
        in GROUPS -> Hop.Unsupported("detour through an outbound group")
        else -> Hop.Unsupported("detour to ${type ?: "unknown"}")
    }

    private val GROUPS = setOf("selector", "urltest", "fallback", "failover", "loadbalance")

    private fun outbound(o0: JsonObject, type: String, keepDetour: Boolean = false): Candidate {
        val o = if (keepDetour) o0 else o0.without("detour")
        val name = o.str("tag")
        if (type == "wireguard" && o["peers"] == null && o["server"] != null) return legacyWireGuard(o, name)
        if (o.str("server").isNullOrEmpty() && type != "wireguard") malformed("missing server")
        return Candidate(o.without("tag"), name)
    }

    /** Pre-1.11 wireguard outbound → endpoint. */
    private fun legacyWireGuard(o: JsonObject, name: String?): Candidate = WgConf(
        privateKey = o.str("private_key") ?: malformed("missing private key"),
        addresses = o.strList("local_address"),
        mtu = o.int("mtu"),
        peers = listOf(
            WgPeer(
                host = o.str("server")!!,
                port = o.int("server_port") ?: malformed("missing port"),
                publicKey = o.str("peer_public_key") ?: malformed("missing public key"),
                preSharedKey = o.str("pre_shared_key"),
                allowedIps = emptyList(),
                keepalive = null,
            ),
        ),
        amnezia = emptyMap(),
        name = name,
    ).toCandidate()
}

/** Xray client configs: a JSON array of full configs with `remarks`, or a single config. */
internal object XrayFormat {
    private val NON_PROXY = setOf("freedom", "blackhole", "dns", "loopback")

    fun isConfig(root: JsonElement): Boolean = when (root) {
        is JsonArray -> root.isNotEmpty() && root.all { el -> el.asObj()?.get("outbounds").asArr()?.isNotEmpty() == true }
        is JsonObject -> root["outbounds"].asArr()?.any { it.asObj()?.get("protocol") != null } == true
        else -> false
    }

    fun parse(root: JsonElement): List<Entry> {
        val configs = when (root) {
            is JsonArray -> root.map { it.asObj() }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return configs.map { cfg ->
            if (cfg == null) return@map Entry.Skip(null, SkipReason.MALFORMED)
            val outbounds = cfg["outbounds"].asArr().orEmpty().mapNotNull { it.asObj() }
            val proxies = outbounds.filter { it.str("protocol")?.lowercase() !in NON_PROXY }
            val dialers = outbounds.mapNotNull(::dialerOf).toSet()
            val chosen = proxies.firstOrNull { it.str("tag") == "proxy" }
                ?: proxies.firstOrNull { it.str("tag") !in dialers }
                ?: proxies.firstOrNull()
                ?: return@map Entry.Skip(null, SkipReason.MALFORMED)
            val picks = (listOf(chosen) + balancerMembers(cfg, proxies)).distinct()
            val byTag = outbounds.filter { it.str("tag") != null }.associateBy { it.str("tag")!! }
            val remarks = cfg.str("remarks")
            val entries = picks.map { o ->
                val name = if (picks.size == 1) remarks ?: o.str("tag") else listOfNotNull(remarks, o.str("tag")).joinToString(" · ")
                member(o, name) { tag -> byTag[tag]?.let(::hop) }
            }
            entries.singleOrNull() ?: Entry.Nodes(
                entries.filterIsInstance<Entry.Nodes>().flatMap { it.candidates },
                entries.filterIsInstance<Entry.Skip>(),
            )
        }
    }

    private fun member(o: JsonObject, name: String?, lookup: (String) -> Hop?): Entry {
        val protocol = o.str("protocol")?.lowercase()
        if (protocol !in PROTOCOLS) return Entry.Skip(protocol, SkipReason.UNKNOWN_SCHEME)
        return entry(protocol) { listOf(Chain.resolve(outbound(o, protocol!!, name), o.str("tag"), dialerOf(o), lookup)) }
    }

    /** Proxies a `routing.balancers` entry can pick: tags starting with a `selector` prefix, plus its `fallbackTag`. */
    private fun balancerMembers(cfg: JsonObject, proxies: List<JsonObject>): List<JsonObject> {
        val balancers = cfg.child("routing")?.get("balancers").asArr().orEmpty().mapNotNull { it.asObj() }
        val prefixes = balancers.flatMap { it.strList("selector") }.filter { it.isNotEmpty() }
        val fallbacks = balancers.mapNotNull { it.str("fallbackTag") }.toSet()
        return proxies.filter { p ->
            val tag = p.str("tag") ?: return@filter false
            tag in fallbacks || prefixes.any(tag::startsWith)
        }
    }

    /** `sockopt.dialerProxy`, or the removed `proxySettings.tag` older configs still carry. */
    private fun dialerOf(o: JsonObject): String? =
        o.child("streamSettings")?.child("sockopt")?.str("dialerProxy") ?: o.child("proxySettings")?.str("tag")

    private fun hop(o: JsonObject): Hop = when (val protocol = o.str("protocol")?.lowercase()) {
        "freedom" -> freedom(o)
        in PROTOCOLS -> Hop.Proxy(outbound(o, protocol!!, o.str("tag")), dialerOf(o))
        else -> Hop.Unsupported("dialer proxy to ${protocol ?: "unknown"}")
    }

    private fun freedom(o: JsonObject): Hop {
        val s = o.child("settings") ?: JsonObject(emptyMap())
        if (s.str("redirect") != null) return Hop.Unsupported("dialer proxy to a redirecting freedom")
        val fragment = s.child("fragment")
        val noises = s["noises"].asArr()?.isNotEmpty() == true
        if (fragment == null && !noises) return Hop.Direct(dialerOf(o))
        return Hop.Direct(dialerOf(o)) { dialer, issues -> fragmented(dialer, fragment, noises, issues) }
    }

    /**
     * Freedom `fragment` splits the TCP stream of the outbound dialing through it, `noises` precede its UDP; sing-box can only
     * split the ClientHello of the outbound's own TLS: `tlshello` maps to TLS records, a packet range from 1 to TCP segments.
     */
    private fun fragmented(o: JsonObject, fragment: JsonObject?, noises: Boolean, issues: Issues): JsonObject {
        val type = o.str("type").orEmpty()
        if (type == "wireguard" || Normalizer.isQuic(type, o) || o.child("transport")?.str("type") == "mkcp") {
            if (noises) issues.unsupported("xray UDP noises")
            return o
        }
        fragment ?: return o
        val packets = fragment.str("packets")?.lowercase().orEmpty()
        val hello = packets == "tlshello"
        if (!hello && packets.isNotEmpty() && packets.substringBefore('-').trim() != "1") {
            issues.unsupported("xray fragmentation of later packets")
            return o
        }
        val tls = o.child("tls")?.takeIf { it.bool("enabled") == true }
        if (tls == null) {
            if (!hello) issues.unsupported("xray TCP fragmentation without TLS")
            return o
        }
        val interval = V2Stream.range(fragment["interval"])?.scalar()?.substringAfterLast('-')?.trim()?.toLongOrNull() ?: 0
        var t = tls
        if (hello) t = t.with("record_fragment", JsonPrimitive(true))
        if (!hello || interval > 0) t = t.with("fragment", JsonPrimitive(true))
        return o.with("tls", t)
    }

    private val PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "wireguard", "socks", "http", "hysteria")

    private fun outbound(o: JsonObject, protocol: String, name: String?): Candidate {
        val settings = o.child("settings") ?: malformed("missing settings")
        if (protocol == "wireguard") return wireguard(settings, name)
        val server = settings["vnext"].asArr()?.firstOrNull().asObj()
            ?: settings["servers"].asArr()?.firstOrNull().asObj()
            ?: settings
        val user = server["users"].asArr()?.firstOrNull().asObj() ?: server
        val host = server.str("address") ?: malformed("missing address")
        val port = server.int("port")?.takeIf { it in 1..65535 } ?: malformed("bad port")
        if (protocol == "hysteria") return hysteria(settings, o.child("streamSettings") ?: JsonObject(emptyMap()), host, port, name)
        val stream = V2Stream.fromXray(o.child("streamSettings"))
        val issues = Issues()
        val out = when (protocol) {
            "vless" -> jsonObj {
                str("type", "vless")
                str("server", host)
                num("server_port", port)
                str("uuid", user.str("id") ?: malformed("missing id"))
                str("flow", user.str("flow"))
                str("encryption", V2rayLinks.vlessEncryption(user.str("encryption"), issues))
                el("tls", stream.tls(issues))
                el("transport", stream.transport(issues))
            }
            "vmess" -> jsonObj {
                str("type", "vmess")
                str("server", host)
                num("server_port", port)
                str("uuid", user.str("id") ?: malformed("missing id"))
                str("security", V2rayLinks.vmessSecurity(user.str("security")))
                user.int("alterId")?.takeIf { it > 0 }?.let { num("alter_id", it) }
                el("tls", stream.tls(issues))
                el("transport", stream.transport(issues))
            }
            "trojan" -> jsonObj {
                str("type", "trojan")
                str("server", host)
                num("server_port", port)
                str("password", server.str("password") ?: malformed("missing password"))
                el("tls", stream.tls(issues))
                el("transport", stream.transport(issues))
            }
            "socks", "http" -> jsonObj {
                str("type", protocol)
                str("server", host)
                num("server_port", port)
                str("username", user.str("user"))
                str("password", user.str("pass"))
                val tls = stream.tls(issues)
                if (protocol == "http") el("tls", tls) else if (tls != null) issues.unsupported("socks over TLS")
                if (stream.transport(issues) != null) issues.unsupported("transport on $protocol")
            }
            else -> return ShadowsocksLink.build(
                method = server.str("method") ?: malformed("missing method"),
                password = server.str("password") ?: malformed("missing password"),
                host = host,
                port = port,
                plugin = null,
                name = name,
            )
        }
        return Candidate(out, name, issues.reason)
    }

    /** Hysteria 2: auth in `hysteriaSettings`, obfuscation, hopping and Brutal rates in `finalmask`. */
    private fun hysteria(settings: JsonObject, ss: JsonObject, host: String, port: Int, name: String?): Candidate {
        val issues = Issues()
        val version = settings.int("version") ?: ss.child("hysteriaSettings")?.int("version")
        if (version != 2) issues.unsupported("hysteria version $version")
        if (ss.str("network")?.lowercase() != "hysteria") issues.unsupported("hysteria over ${ss.str("network") ?: "tcp"}")
        if (ss.str("security")?.lowercase() != "tls") issues.unsupported("hysteria without TLS")
        val tls = ss.child("tlsSettings")
        val insecure = tls?.bool("allowInsecure") == true
        // A hysteria pin hashes the certificate, sing-box pins the SPKI: dropping it is only safe while the chain is still verified.
        if (tls?.str("pinnedPeerCertSha256") != null && insecure) issues.unsupported("certificate pin with insecure TLS")
        val mask = ss.child("finalmask")?.let { XrayFinalMask.hysteria(it, issues) }
        val out = jsonObj {
            str("type", "hysteria2")
            str("server", host)
            num("server_port", port)
            strings("server_ports", mask?.hopPorts)
            str("hop_interval", mask?.hopInterval)
            str("hop_interval_max", mask?.hopIntervalMax)
            str("password", ss.child("hysteriaSettings")?.str("auth"))
            num("up_mbps", mask?.upMbps)
            num("down_mbps", mask?.downMbps)
            str("bbr_profile", mask?.bbrProfile)
            el("obfs", mask?.obfs)
            obj("tls") {
                bool("enabled", true)
                str("server_name", tls?.str("serverName"))
                flag("insecure", insecure)
                strings("alpn", QuicLinks.quicAlpn(tls?.strList("alpn")?.joinToString(",")?.ifEmpty { null }))
            }
        }
        return Candidate(out, name, issues.reason)
    }

    private fun wireguard(s: JsonObject, name: String?): Candidate {
        val peers = s["peers"].asArr().orEmpty().mapNotNull { it.asObj() }.map { p ->
            val ep = p.str("endpoint") ?: malformed("peer without endpoint")
            val hp = moe.desupon.parser.internal.ShareUrl.parse("wg://x@$ep")
            WgPeer(
                host = hp.requireHost(),
                port = hp.requirePort(),
                publicKey = p.str("publicKey") ?: malformed("peer without public key"),
                preSharedKey = p.str("preSharedKey"),
                allowedIps = p.strList("allowedIPs"),
                keepalive = p.int("keepAlive"),
            )
        }
        return WgConf(
            privateKey = s.str("secretKey") ?: malformed("missing secret key"),
            addresses = s.strList("address"),
            mtu = s.int("mtu"),
            peers = peers,
            amnezia = emptyMap(),
            name = name,
        ).toCandidate()
    }
}

/** SIP008 `{"version":1,"servers":[…]}`. */
internal object Sip008Format {
    fun isConfig(root: JsonElement): Boolean = root is JsonObject && root["servers"] is JsonArray

    fun parse(root: JsonElement): List<Entry> = (root as JsonObject)["servers"].asArr().orEmpty().map { el ->
        entry("ss") {
            val s = el.asObj() ?: malformed("server is not an object")
            val plugin = s.str("plugin")?.let { p -> s.str("plugin_opts")?.let { "$p;$it" } ?: p }
            listOf(
                ShadowsocksLink.build(
                    method = s.str("method") ?: malformed("missing method"),
                    password = s.str("password") ?: malformed("missing password"),
                    host = s.str("server") ?: malformed("missing server"),
                    port = s.int("server_port")?.takeIf { it in 1..65535 } ?: malformed("bad port"),
                    plugin = plugin,
                    name = s.str("remarks"),
                ),
            )
        }
    }
}
