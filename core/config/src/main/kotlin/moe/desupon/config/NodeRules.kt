package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol

/** Per-node rules of the extended core; every rule here was confirmed against a live core. */
internal object NodeRules {
    const val DEFAULT_X_PADDING = "100-1000"
    const val VISION = "xtls-rprx-vision"
    private const val VISION_UDP443 = "xtls-rprx-vision-udp443"
    private const val REALITY_FINGERPRINT = "chrome"
    private const val AWG_MIN_PADDING_WITH_HPK = 12
    private const val H3 = "h3"
    const val MAX_HOPS = 3

    private val QUIC_TYPES = setOf("hysteria", "hysteria2", "tuic", "juicity")
    private val NON_NODE_TYPES = setOf("direct", "block", "dns", "selector", "urltest", "fallback", "failover", "freeturn")
    private val DROPPED_KEYS = listOf("tag", "detour", "domain_strategy", "domain_resolver")
    private val DROPPED_ENDPOINT_KEYS = listOf("listen_port", "system", "name", "reserved")
    private val DROPPED_PEER_KEYS = listOf("reserved", "persistent_keepalive_interval")
    private val AWG_PADDINGS = listOf("s1", "s2", "s3", "s4")

    // Key shapes the core decodes at start: one bad key fails the whole box, so such nodes are rejected individually.
    private val STD_KEY32 = Regex("[A-Za-z0-9+/]{43}=")
    private val STD_KEY16 = Regex("[A-Za-z0-9+/]{22}==")
    private val RAW_URL_KEY32 = Regex("[A-Za-z0-9_-]{43}")
    private val SHA256_PIN = listOf(Regex("[A-Za-z0-9+/]{43}=?"), Regex("[A-Za-z0-9_-]{43}=?"), Regex("[0-9a-fA-F]{64}"))
    private val UUID = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")

    /** One finished outbound or endpoint. */
    data class Built(val json: JsonObject, val endpoint: Boolean)

    sealed interface Outcome {
        /** [hops] are the outbounds the node dials through, nearest first, already linked by `detour`. */
        data class Ok(val json: JsonObject, val endpoint: Boolean, val hops: List<Built> = emptyList()) : Outcome

        data class Rejected(val reason: String) : Outcome
    }

    private class Reject(val reason: String) : Exception(reason, null, false, false)

    /** Tag of the [index]th (1-based) outbound [id] dials through. */
    fun hopTag(id: NodeId, index: Int): String = "${id.tag}-$index"

    fun normalize(node: Node, resolver: DomainResolverDto): Outcome {
        if (node.capability == Capability.UNSUPPORTED) return Outcome.Rejected("unsupported by the core")
        if (node.protocol == Protocol.FREETURN) return Outcome.Rejected("FreeTurn is configured as a TURN profile, not a node")
        val source = parseObject(node.outboundJson.raw()) ?: return Outcome.Rejected("outbound JSON is malformed")
        val chain = when (val c = source[Node.CHAIN_KEY]) {
            null -> emptyList()
            is JsonArray -> c.map { it as? JsonObject ?: return Outcome.Rejected("chain hop is not an object") }
            else -> return Outcome.Rejected("chain is not an array")
        }
        if (chain.size > MAX_HOPS) return Outcome.Rejected("chain longer than $MAX_HOPS")
        val detour = { i: Int -> if (i < chain.size) hopTag(node.id, i + 1) else null }
        val built = when (val r = build(JsonObject(source - Node.CHAIN_KEY), node.id.tag, node.isEndpoint, detour(0), resolver)) {
            is Outcome.Rejected -> return r
            is Outcome.Ok -> r
        }
        val hops = chain.mapIndexed { i, hop ->
            when (val r = build(hop, hopTag(node.id, i + 1), false, detour(i + 1), resolver)) {
                is Outcome.Rejected -> return Outcome.Rejected("chain hop ${i + 1}: ${r.reason}")
                is Outcome.Ok -> Built(r.json, r.endpoint)
            }
        }
        return built.copy(hops = hops)
    }

    /** Without [detour] the outbound dials itself and resolves its server through [resolver]; with it the server name travels to the hop. */
    private fun build(source: JsonObject, tag: String, isEndpoint: Boolean, detour: String?, resolver: DomainResolverDto): Outcome {
        val type = source.string("type") ?: return Outcome.Rejected("outbound type missing")
        if (type in NON_NODE_TYPES) return Outcome.Rejected("type $type is not a proxy node")
        val endpoint = isEndpoint || type == "wireguard"
        return try {
            val body = source.toMutableMap()
            DROPPED_KEYS.forEach(body::remove)
            normalizeBody(type, body, endpoint)
            val out = LinkedHashMap<String, JsonElement>()
            out["type"] = JsonPrimitive(type)
            out["tag"] = JsonPrimitive(tag)
            body.remove("type")
            out.putAll(body)
            detour?.let { out["detour"] = JsonPrimitive(it) }
            // WireGuard resolves its peers even behind a detour.
            if (detour == null || endpoint) out["domain_resolver"] = configJson.encodeToJsonElement(DomainResolverDto.serializer(), resolver)
            val json = JsonObject(out)
            val violations = violations(json, endpoint)
            if (violations.isEmpty()) Outcome.Ok(json, endpoint) else Outcome.Rejected(violations.joinToString("; "))
        } catch (e: Reject) {
            Outcome.Rejected(e.reason)
        }
    }

    private fun normalizeBody(type: String, body: MutableMap<String, JsonElement>, endpoint: Boolean) {
        if (type == "vless" && body.string("flow") == VISION_UDP443) body["flow"] = JsonPrimitive(VISION)
        val quic = isQuic(type, body)
        body.obj("tls")?.let { body["tls"] = normalizeTls(it, quic) }
        body.obj("transport")?.let { transport ->
            if (transport.string("type") == "xhttp") body["transport"] = normalizeXhttp(transport)
        }
        if (endpoint && type == "wireguard") {
            DROPPED_ENDPOINT_KEYS.forEach(body::remove)
            (body["peers"] as? JsonArray)?.let { peers ->
                body["peers"] = JsonArray(
                    peers.map { peer ->
                        if (peer is JsonObject) JsonObject(peer.toMutableMap().apply { DROPPED_PEER_KEYS.forEach(::remove) }) else peer
                    },
                )
            }
        }
    }

    private fun normalizeTls(tls: JsonObject, quic: Boolean): JsonObject {
        val out = tls.toMutableMap()
        val reality = tls.obj("reality")
        if (reality != null && reality.bool("enabled") == true) {
            if (quic) throw Reject("REALITY over QUIC is unsupported")
            out["utls"] = JsonObject(mapOf("enabled" to JsonPrimitive(true), "fingerprint" to JsonPrimitive(REALITY_FINGERPRINT)))
            out["reality"] = JsonObject(reality.toMutableMap().apply { put("support_x25519mlkem768", JsonPrimitive(true)) })
        } else if (quic) {
            out.remove("utls")
        }
        val alpn = alpnList(tls)
        if (!quic && H3 in alpn) {
            // A TCP transport cannot speak h3; offering it anyway sets the handshake apart.
            val tcp = alpn.filter { it != H3 }
            if (tcp.isEmpty()) out.remove("alpn") else out["alpn"] = JsonArray(tcp.map(::JsonPrimitive))
        }
        return JsonObject(out)
    }

    private fun normalizeXhttp(transport: JsonObject): JsonObject {
        val out = normalizeXhttpBase(transport)
        if (out.string("mode") == "stream-one") {
            out.remove("download")
        } else {
            out.obj("download")?.let { out["download"] = JsonObject(normalizeXhttpBase(it)) }
        }
        return JsonObject(out)
    }

    private fun normalizeXhttpBase(base: JsonObject): MutableMap<String, JsonElement> {
        val out = base.toMutableMap()
        if (!validPadding(out["x_padding_bytes"])) out["x_padding_bytes"] = JsonPrimitive(DEFAULT_X_PADDING)
        val headers = out.obj("headers")
        if (headers != null) {
            val hostKey = headers.keys.firstOrNull { it.equals("host", ignoreCase = true) }
            if (hostKey != null) {
                val host = (headers[hostKey] as? JsonPrimitive)?.contentOrNull?.trim()
                if (out.string("host").isNullOrBlank() && !host.isNullOrEmpty()) out["host"] = JsonPrimitive(host)
                val rest = headers.filterKeys { !it.equals("host", ignoreCase = true) }
                if (rest.isEmpty()) out.remove("headers") else out["headers"] = JsonObject(rest)
            }
        }
        return out
    }

    /** Violations of the node rules in a finished outbound or endpoint; messages name fields only, never values. */
    fun violations(obj: JsonObject, endpoint: Boolean): List<String> {
        val out = mutableListOf<String>()
        val type = obj.string("type") ?: return listOf("type missing")
        if (!endpoint && type == "wireguard") out += "wireguard must be an endpoint"
        if (type == "freeturn") return out
        val quic = isQuic(type, obj)
        obj.obj("tls")?.let { tls ->
            val reality = tls.obj("reality")
            val utls = tls.obj("utls")
            if (reality != null && reality.bool("enabled") == true) {
                if (utls?.bool("enabled") != true || utls.string("fingerprint") != REALITY_FINGERPRINT) {
                    out += "REALITY requires uTLS fingerprint $REALITY_FINGERPRINT"
                }
                if (reality.bool("support_x25519mlkem768") != true) out += "REALITY without support_x25519mlkem768"
                if (reality.string("public_key")?.let(RAW_URL_KEY32::matches) != true) out += "REALITY public_key is not a 32-byte base64url key"
            } else if (quic && utls != null) {
                out += "uTLS on a QUIC transport"
            }
            if (!quic && H3 in alpnList(tls)) out += "h3 ALPN on a TCP transport"
        }
        if (type == "vless") {
            val flow = obj.string("flow")
            if (!flow.isNullOrEmpty() && flow != VISION) out += "unsupported flow"
        }
        if ((type == "tuic" || type == "juicity") && obj.string("uuid")?.let(UUID::matches) != true) out += "$type uuid is not a UUID"
        if (type == "hysteria2") obj.obj("obfs")?.takeIf { it.string("type") == "gecko" }?.let { geckoViolation(it)?.let(out::add) }
        if (type == "shadowsocks") ss2022Violation(obj)?.let { out += it }
        if (type == "juicity" && obj.string("pinned_certchain_sha256")?.let { pin -> SHA256_PIN.none { it.matches(pin) } } == true) {
            out += "juicity pinned_certchain_sha256 is not a SHA-256"
        }
        obj.obj("transport")?.takeIf { it.string("type") == "xhttp" }?.let { transport ->
            xhttpBaseViolations(transport, "xhttp", out)
            val download = transport.obj("download")
            if (download != null) {
                if (transport.string("mode") == "stream-one") out += "xhttp stream-one with download"
                xhttpBaseViolations(download, "xhttp download", out)
            }
        }
        if (type == "wireguard") {
            if (obj.containsKey("listen_port")) out += "endpoint listen_port set"
            if (obj.bool("system") == true) out += "endpoint uses a system interface"
            if (obj.containsKey("reserved")) out += "endpoint reserved field"
            val peers = (obj["peers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            if (peers.any { it.containsKey("reserved") }) out += "peer reserved field"
            if (obj.string("private_key")?.let(STD_KEY32::matches) != true) out += "private_key is not a 32-byte base64 key"
            if (peers.any { it.string("public_key")?.let(STD_KEY32::matches) != true }) out += "peer public_key is not a 32-byte base64 key"
            if (peers.any { p -> p.string("pre_shared_key")?.let { !STD_KEY32.matches(it) } == true }) out += "peer pre_shared_key is not a 32-byte base64 key"
            if (peers.any { it.containsKey("persistent_keepalive_interval") }) out += "peer persistent keepalive set"
            obj.obj("amnezia")?.let { awg ->
                if (!awg.string("header_protection_key").isNullOrBlank() &&
                    AWG_PADDINGS.any { (awg.int(it) ?: 0) < AWG_MIN_PADDING_WITH_HPK }
                ) {
                    out += "AWG S1-S4 must be at least $AWG_MIN_PADDING_WITH_HPK with header_protection_key"
                }
                if (awg.string("header_protection_key")?.let { !STD_KEY32.matches(it) } == true) out += "AWG header_protection_key is not a 32-byte base64 key"
            }
        }
        val viaHop = obj.string("detour") != null && !endpoint
        if (!viaHop && obj.obj("domain_resolver")?.string("server") == null && obj.string("domain_resolver") == null) {
            out += "domain_resolver missing"
        }
        return out
    }

    /** The core fills unset sizes with 512/1200 and refuses the outbound unless 0 < min <= max <= 2048. */
    private fun geckoViolation(obfs: JsonObject): String? {
        val min = obfs.int("min_packet_size")?.takeIf { it != 0 } ?: 512
        val max = obfs.int("max_packet_size")?.takeIf { it != 0 } ?: 1200
        return if (min <= 0 || min > max || max > 2048) "gecko packet size range" else null
    }

    private fun ss2022Violation(obj: JsonObject): String? {
        val method = obj.string("method") ?: return null
        if (!method.startsWith("2022-")) return null
        val shape = if (method == "2022-blake3-aes-128-gcm") STD_KEY16 else STD_KEY32
        val keys = obj.string("password")?.split(':').orEmpty()
        return when {
            keys.isEmpty() || keys.any { !shape.matches(it) } -> "shadowsocks 2022 key has the wrong length for the method"
            method == "2022-blake3-chacha20-poly1305" && keys.size > 1 -> "shadowsocks 2022 chacha20 has no multi-user keys"
            else -> null
        }
    }

    private fun xhttpBaseViolations(base: JsonObject, what: String, out: MutableList<String>) {
        if (!validPadding(base["x_padding_bytes"])) out += "$what x_padding_bytes missing or disabled"
        if (base.obj("headers")?.keys?.any { it.equals("host", ignoreCase = true) } == true) out += "$what headers contain Host"
    }

    fun isQuic(type: String, obj: Map<String, JsonElement>): Boolean {
        if (type in QUIC_TYPES) return true
        val transport = obj.obj("transport")?.string("type")
        if (transport == "quic") return true
        return transport == "xhttp" && firstAlpn(obj.obj("tls")) == H3
    }

    /** Every flow rides one long-lived TCP connection: sing-mux, AnyTLS sessions, or an HTTP/2 transport. */
    fun isPooled(type: String, obj: Map<String, JsonElement>): Boolean {
        if (type == "anytls" || obj.obj("multiplex")?.bool("enabled") == true) return true
        return when (obj.obj("transport")?.string("type")) {
            "grpc", "http" -> true
            "xhttp" -> !isQuic(type, obj)
            else -> false
        }
    }

    private fun firstAlpn(tls: JsonObject?): String? = alpnList(tls).firstOrNull()

    private fun alpnList(tls: JsonObject?): List<String> = when (val alpn = tls?.get("alpn")) {
        is JsonArray -> alpn.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(alpn.contentOrNull)
        else -> emptyList()
    }

    /** `x_padding_bytes` accepts "a-b", a number, or {from,to}; zero disables it, which the core refuses. */
    private fun validPadding(value: JsonElement?): Boolean = when (value) {
        is JsonPrimitive -> if (value.isString) validRange(value.content) else (value.intOrNull ?: 0) > 0
        is JsonObject -> (value.int("from") ?: 0) > 0 && (value.int("to") ?: 0) >= (value.int("from") ?: 0)
        else -> false
    }

    private fun validRange(text: String): Boolean {
        val parts = text.trim().split('-').map { it.trim().toIntOrNull() ?: return false }
        return when (parts.size) {
            1 -> parts[0] > 0
            2 -> parts[0] > 0 && parts[1] >= parts[0]
            else -> false
        }
    }
}

internal fun parseObject(text: String): JsonObject? = try {
    configJson.parseToJsonElement(text) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun Map<String, JsonElement>.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun Map<String, JsonElement>.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun Map<String, JsonElement>.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun Map<String, JsonElement>.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun Map<String, JsonElement>.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
