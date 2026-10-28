package moe.desupon.parser.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.desupon.model.Secret
import moe.desupon.model.node.AmneziaRules
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeKey
import moe.desupon.model.node.Protocol
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.NodeLabels
import java.util.Locale

/**
 * Turns a [Candidate] into a [Node]: applies the fixes the bundled core needs to accept or run the outbound,
 * derives identity from the final JSON so the same server dedupes across formats, and reads country/labels from the name.
 * Runtime policy (keepalive, resolvers, MTU) stays with the config generator.
 */
internal object Normalizer {
    data class Result(val node: Node, val unsupported: String?)

    fun toNode(c: Candidate, subId: SubId): Result {
        val issues = Issues()
        c.unsupported?.let(issues::unsupported)
        val fixed = fix(c.outbound, issues)
        val hops = c.chain.map { hop ->
            val hopIssues = Issues()
            hop.unsupported?.let(hopIssues::unsupported)
            fix(hop.outbound, hopIssues).also { hopIssues.reason?.let { issues.unsupported("dialer proxy: $it") } }
        }
        val type = fixed.str("type") ?: malformed("missing type")
        val isWg = type == "wireguard"
        val protocol = protocolOf(type, fixed)
        val key = identity(protocol, fixed).let { k -> if (hops.isEmpty()) k else k.copy(via = k.via + hops.map { identity(it).id.hash }) }
        val out = if (hops.isEmpty()) fixed else fixed.with(Node.CHAIN_KEY, JsonArray(hops))
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: hostPort(key.host, key.port)
        val node = Node(
            id = key.id,
            subs = setOf(subId),
            protocol = protocol,
            displayName = name,
            country = country(name),
            capability = if (issues.reason == null) Capability.SUPPORTED else Capability.UNSUPPORTED,
            outboundJson = Secret(out.toString()),
            isEndpoint = isWg,
            udpOnly = isWg || isQuic(type, out),
            labels = labels(name),
        )
        return Result(node, issues.reason)
    }

    private fun protocolOf(type: String, o: JsonObject): Protocol = when (type) {
        "vless" -> Protocol.VLESS
        "vmess" -> Protocol.VMESS
        "trojan" -> Protocol.TROJAN
        "shadowsocks" -> Protocol.SHADOWSOCKS
        "hysteria" -> Protocol.HYSTERIA
        "hysteria2" -> Protocol.HYSTERIA2
        "tuic" -> Protocol.TUIC
        "juicity" -> Protocol.JUICITY
        "anytls" -> Protocol.ANYTLS
        "socks" -> Protocol.SOCKS
        "http" -> Protocol.HTTP
        "wireguard" -> if (o.child("amnezia")?.isNotEmpty() == true) Protocol.AMNEZIAWG else Protocol.WIREGUARD
        else -> malformed("unknown type")
    }

    fun isQuic(type: String, o: JsonObject): Boolean {
        if (type == "hysteria" || type == "hysteria2" || type == "tuic" || type == "juicity") return true
        val t = o.child("transport")?.str("type")
        if (t == "quic") return true
        val alpn0 = o.child("tls")?.strList("alpn")?.firstOrNull()
        return t == "xhttp" && alpn0 == "h3"
    }

    private val CHROME = jsonObj { bool("enabled", true); str("fingerprint", "chrome") }
    private val TLS_REQUIRED = setOf("hysteria", "hysteria2", "tuic", "juicity", "anytls")

    fun fix(input: JsonObject, issues: Issues): JsonObject {
        var o = input.without("tag")
        val type = o.str("type")?.lowercase() ?: malformed("missing type")
        o = o.with("type", JsonPrimitive(type))
        if (o.str("detour") != null) issues.unsupported("detour chain")
        o.str("server")?.let { s -> if (s.startsWith("[")) o = o.with("server", JsonPrimitive(s.removeSurrounding("[", "]"))) }

        if (type == "vless") {
            when (val flow = o.str("flow")) {
                null, "xtls-rprx-vision" -> {}
                "xtls-rprx-vision-udp443" -> o = o.with("flow", JsonPrimitive("xtls-rprx-vision"))
                else -> issues.unsupported("flow $flow")
            }
        }

        when (type) {
            "tuic" -> o = o.with("uuid", JsonPrimitive(Keys.uuid(o.str("uuid"), "tuic uuid")))
            "juicity" -> {
                o = o.with("uuid", JsonPrimitive(Keys.uuid(o.str("uuid"), "juicity uuid")))
                // Husi exports the pin under its own key.
                o.str("pin_cert_sha256")?.let { o = o.without("pin_cert_sha256").with("pinned_certchain_sha256", JsonPrimitive(it)) }
            }
            "shadowsocks" -> o.str("method")?.takeIf { it.startsWith("2022-") }?.let { m ->
                o = o.with("password", JsonPrimitive(Keys.ss2022(m, o.rawStr("password").orEmpty(), issues)))
            }
        }

        if (type == "hysteria2") o.child("obfs")?.takeIf { it.str("type") == "gecko" }?.let { geckoRange(it, issues) }

        o.child("transport")?.let { t -> o = o.with("transport", fixTransport(t)) }

        o.child("tls")?.let { tls0 ->
            var tls = tls0
            tls.child("reality")?.takeIf { it.bool("enabled") == true }?.let { r0 ->
                var r = r0.with("public_key", JsonPrimitive(Keys.reality(r0.str("public_key") ?: malformed("REALITY without public key"))))
                if (r["support_x25519mlkem768"] == null) r = r.with("support_x25519mlkem768", JsonPrimitive(true))
                tls = tls.with("utls", CHROME).with("reality", r)
            }
            if (isQuic(type, o)) tls = tls.without("utls")
            o = o.with("tls", tls)
        }
        if (type in TLS_REQUIRED && o.child("tls")?.bool("enabled") != true) issues.unsupported("$type without TLS")
        if (type == "hysteria") {
            // The core has no bandwidth detection for v1 and refuses to start without both rates.
            if (o.str("up") == null && (o.int("up_mbps") ?: 0) <= 0) issues.unsupported("hysteria upload rate missing")
            if (o.str("down") == null && (o.int("down_mbps") ?: 0) <= 0) issues.unsupported("hysteria download rate missing")
        }

        if (type == "wireguard") {
            o = o.with("private_key", JsonPrimitive(Keys.std(o.str("private_key") ?: malformed("missing private key"), 32, "private key")))
            val peers = o["peers"].asArr()?.map { el ->
                var p = el.asObj()?.without("reserved") ?: malformed("peer is not an object")
                p = p.with("public_key", JsonPrimitive(Keys.std(p.str("public_key") ?: malformed("peer without public key"), 32, "peer public key")))
                p.str("pre_shared_key")?.let { p = p.with("pre_shared_key", JsonPrimitive(Keys.std(it, 32, "peer pre-shared key"))) }
                p
            }
            if (peers.isNullOrEmpty()) malformed("wireguard without peers")
            if (peers.any { it.strList("allowed_ips").isEmpty() }) issues.unsupported("peer allowed_ips missing")
            o = o.with("peers", JsonArray(peers))
            o.child("amnezia")?.let { a0 ->
                val a = a0.str("header_protection_key")?.let { a0.with("header_protection_key", JsonPrimitive(Keys.std(it, 32, "AmneziaWG header protection key"))) } ?: a0
                o = o.with("amnezia", a)
                if (a.str("header_protection_key") != null && listOf("s1", "s2", "s3", "s4").any { (a.int(it) ?: 0) < 12 }) {
                    issues.unsupported("AmneziaWG header protection needs S1-S4 >= 12")
                }
                AmneziaRules.violation { a[it].amneziaText() }?.let(issues::unsupported)
            }
        }
        return o
    }

    /** Applies the core's gecko defaults (512/1200, 0 meaning unset); a range it refuses fails the whole box at start. */
    private fun geckoRange(obfs: JsonObject, issues: Issues) {
        val min = obfs.int("min_packet_size")?.takeIf { it != 0 } ?: GECKO_DEFAULT_MIN
        val max = obfs.int("max_packet_size")?.takeIf { it != 0 } ?: GECKO_DEFAULT_MAX
        if (min <= 0 || min > max || max > GECKO_MAX_PACKET) issues.unsupported("gecko packet size range")
    }

    private const val GECKO_DEFAULT_MIN = 512
    private const val GECKO_DEFAULT_MAX = 1200
    private const val GECKO_MAX_PACKET = 2048

    private fun fixTransport(t0: JsonObject): JsonObject {
        var t = t0
        if (t.str("type") != "xhttp") return t
        val pad = t["x_padding_bytes"]
        if (pad == null || pad.scalar()?.split('-')?.all { it.trim().toLongOrNull() == 0L } == true) {
            t = t.with("x_padding_bytes", JsonPrimitive(V2Stream.DEFAULT_PADDING))
        }
        t.child("headers")?.let { h ->
            val hostKey = h.keys.firstOrNull { it.equals("host", ignoreCase = true) }
            if (hostKey != null) {
                if (t.str("host") == null) h[hostKey].scalar()?.let { t = t.with("host", JsonPrimitive(it)) }
                val rest = h.without(hostKey)
                t = if (rest.isEmpty()) t.without("headers") else t.with("headers", rest)
            }
        }
        if (t.str("mode") == "stream-one" && t["download"] != null) t = t.without("download")
        return t
    }

    private fun identity(o: JsonObject): NodeKey = identity(protocolOf(o.str("type") ?: malformed("missing type"), o), o)

    /** [key] plus TLS fragmentation, which changes how the server is reached. */
    private fun identity(protocol: Protocol, o: JsonObject): NodeKey {
        val tls = o.child("tls")
        val marks = listOfNotNull(
            "fragment".takeIf { tls?.bool("fragment") == true },
            "record_fragment".takeIf { tls?.bool("record_fragment") == true },
        )
        return key(protocol, o).copy(via = marks)
    }

    fun key(protocol: Protocol, o: JsonObject): NodeKey {
        val type = o.str("type")
        val peer = o["peers"].asArr()?.firstOrNull().asObj()
        val host = (if (type == "wireguard") peer?.str("address") else o.str("server")) ?: malformed("missing host")
        val port = (if (type == "wireguard") peer?.int("port") else o.int("server_port"))?.takeIf { it in 1..65535 }
            ?: malformed("missing port")
        val credential = when (type) {
            "vless", "vmess" -> o.str("uuid")
            "trojan", "hysteria2", "anytls" -> o.rawStr("password")
            "shadowsocks" -> "${o.str("method")}:${o.rawStr("password").orEmpty()}"
            "hysteria" -> o.str("auth_str", "auth")
            "tuic", "juicity" -> "${o.str("uuid").orEmpty()}:${o.rawStr("password").orEmpty()}"
            "socks", "http" -> o.str("username")?.let { "$it:${o.rawStr("password").orEmpty()}" }
            "wireguard" -> "${o.str("private_key").orEmpty()}|${peer?.str("public_key").orEmpty()}"
            else -> null
        }
        val transport = o.child("transport")
        val obfs = when (type) {
            "hysteria2" -> o.child("obfs")?.let { "${it.str("type").orEmpty()}:${it.rawStr("password").orEmpty()}" }
            "hysteria" -> o.str("obfs")
            "shadowsocks" -> o.str("plugin")?.let { "$it;${o.str("plugin_opts").orEmpty()}" }
            "wireguard" -> o.child("amnezia")?.let { JsonObject(it.toSortedMap()).toString() }
            else -> null
        }
        return NodeKey(
            protocol = protocol,
            host = host,
            port = port,
            credential = credential?.let(::Secret),
            transport = transport?.str("type"),
            path = transport?.str("path", "service_name"),
            sni = o.child("tls")?.str("server_name"),
            obfs = obfs,
        )
    }

    private val ISO = Locale.getISOCountries().toSet()

    /** Two-letter upper-case tokens that are ISO codes but usually mean something else in node names. */
    private val TOKEN_DENY = setOf("WS", "SS", "AI", "ID", "IO", "TV", "AM", "PM", "TO", "IP")

    fun country(name: String): String? {
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            if (cps[i] in RI && cps[i + 1] in RI) {
                val code = "${'A' + (cps[i] - RI.first)}${'A' + (cps[i + 1] - RI.first)}"
                val iso = if (code == "UK") "GB" else code
                if (iso in ISO) return iso
            }
        }
        for (m in TOKEN.findAll(name)) {
            val t = m.value
            if (t in TOKEN_DENY) continue
            val iso = if (t == "UK") "GB" else t
            if (iso in ISO) return iso
        }
        return null
    }

    private val RI = 0x1F1E6..0x1F1FF
    private val TOKEN = Regex("(?<![\\p{L}\\p{N}])[A-Z]{2}(?![\\p{L}\\p{N}])")

    private const val SEP = "[\\s_\\-.:/|()\\[\\]]*"
    private val ONLY_MOBILE = Regex(
        "(только|only)${SEP}(для$SEP)?(моб|mobile|lte|4g|5g|сотов)|(mobile|моб\\p{L}*)${SEP}only",
        RegexOption.IGNORE_CASE,
    )
    private val ONLY_WIFI = Regex(
        "(только|only)${SEP}(для$SEP)?(wi-?fi|вай-?фай|wlan)|(wi-?fi|вай-?фай)${SEP}only",
        RegexOption.IGNORE_CASE,
    )
    private val LTE = Regex("(?<![\\p{L}\\p{N}])(lte|4g|5g)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val WHITELIST = Regex(
        "(?<!\\p{L})(wl|бс|whitelist|white${SEP}list|бел\\p{L}*${SEP}спис\\p{L}*|обход$SEP(бс|бел\\p{L}*))(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )

    fun labels(name: String): Set<String> = buildSet {
        if (ONLY_MOBILE.containsMatchIn(name)) add(NodeLabels.ONLY_MOBILE)
        if (ONLY_WIFI.containsMatchIn(name)) add(NodeLabels.ONLY_WIFI)
        if (LTE.containsMatchIn(name)) add(NodeLabels.LTE)
        if (WHITELIST.containsMatchIn(name)) add(NodeLabels.WHITELIST)
    }
}

/** An `amnezia` value as text; the core also takes a range as `{"from":lo,"to":hi}`. */
private fun JsonElement?.amneziaText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    is JsonObject -> "${this["from"].scalar()}-${this["to"].scalar()}"
    else -> toString()
}
