package moe.desupon.parser.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Xray-style stream settings (transport + security) from any source, rendered as sing-box `tls`/`transport`. */
internal data class V2Stream(
    val network: String = "tcp",
    val security: String = "none",
    val sni: String? = null,
    val alpn: List<String> = emptyList(),
    val fingerprint: String? = null,
    val insecure: Boolean = false,
    val ech: String? = null,
    val realityPublicKey: String? = null,
    val realityShortId: String? = null,
    val realityPqv: String? = null,
    val host: String? = null,
    val path: String? = null,
    val serviceName: String? = null,
    val mode: String? = null,
    val headerType: String? = null,
    val kcpSeed: String? = null,
    /** XHTTP settings in xray, mihomo or sing-box key style. */
    val xhttp: JsonObject? = null,
    val finalMask: Boolean = false,
) {
    val isXhttp: Boolean get() = network.lowercase() in XHTTP_NAMES

    fun tls(issues: Issues): JsonObject? = when (security.lowercase()) {
        "tls", "xtls" -> jsonObj {
            bool("enabled", true)
            str("server_name", sni)
            flag("insecure", insecure)
            strings("alpn", alpn)
            el("utls", utls(fingerprint))
            el("ech", ech(ech))
        }
        "reality" -> {
            val pbk = realityPublicKey ?: malformed("reality without public key")
            if (!realityPqv.isNullOrEmpty()) issues.unsupported("REALITY ML-DSA verification (pqv)")
            jsonObj {
                bool("enabled", true)
                str("server_name", sni)
                el("utls", jsonObj { bool("enabled", true); str("fingerprint", "chrome") })
                el(
                    "reality",
                    jsonObj {
                        bool("enabled", true)
                        str("public_key", pbk)
                        str("short_id", realityShortId)
                        bool("support_x25519mlkem768", true)
                    },
                )
            }
        }
        "", "none" -> null
        else -> {
            issues.unsupported("security $security")
            null
        }
    }

    fun transport(issues: Issues): JsonObject? {
        if (finalMask) issues.unsupported("xray finalmask")
        return when (val net = network.lowercase()) {
            "", "tcp", "raw", "none" -> {
                if (headerType.equals("http", ignoreCase = true)) issues.unsupported("TCP HTTP header obfuscation")
                null
            }
            "ws", "websocket" -> websocket()
            "grpc", "gun" -> jsonObj {
                str("type", "grpc")
                str("service_name", serviceName ?: path)
            }
            "httpupgrade" -> jsonObj {
                str("type", "httpupgrade")
                str("host", host)
                str("path", path)
            }
            "h2", "http" -> jsonObj {
                str("type", "http")
                strings("host", host?.let(::splitList))
                str("path", path)
            }
            in XHTTP_NAMES -> xhttp(issues)
            "kcp", "mkcp" -> jsonObj {
                str("type", "mkcp")
                str("header_type", headerType?.takeUnless { it.equals("none", ignoreCase = true) })
                str("seed", kcpSeed)
            }
            else -> {
                issues.unsupported("transport $net")
                null
            }
        }
    }

    private fun websocket(): JsonObject {
        var p = path
        var earlyData: Int? = null
        var earlyHeader: String? = null
        if (p != null && '?' in p) {
            val base = p.substringBefore('?')
            val kept = ArrayList<String>()
            for (pair in p.substringAfter('?').split('&')) {
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                when (k) {
                    "ed" -> earlyData = v.toIntOrNull()
                    "eh" -> earlyHeader = percentDecode(v)
                    else -> if (pair.isNotEmpty()) kept += pair
                }
            }
            p = if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
        }
        return jsonObj {
            str("type", "ws")
            str("path", p)
            if (!host.isNullOrEmpty()) obj("headers") { str("Host", host) }
            if (earlyData != null && earlyData > 0) {
                num("max_early_data", earlyData)
                str("early_data_header_name", earlyHeader ?: "Sec-WebSocket-Protocol")
            }
        }
    }

    private fun xhttp(issues: Issues): JsonObject {
        val src = xhttp ?: JsonObject(emptyMap())
        val m = mode ?: src.looseGet("mode").scalar()?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
        if (m !in XHTTP_MODES) issues.unsupported("xhttp mode $m")
        var h = host ?: src.looseGet("host").scalar()?.trim()?.takeIf { it.isNotEmpty() }
        val p = path ?: src.looseGet("path").scalar()?.trim()?.takeIf { it.isNotEmpty() }
        val headers = LinkedHashMap<String, JsonElement>()
        src.looseGet("headers").asObj()?.forEach { (k, v) ->
            if (k.equals("host", ignoreCase = true)) {
                if (h.isNullOrEmpty()) h = v.scalar()
            } else {
                v.scalar()?.let { headers[k] = JsonPrimitive(it) }
            }
        }
        val download = src.looseGet("downloadSettings") ?: src.looseGet("download")
        if (download.asObj()?.isNotEmpty() == true && m != "stream-one") issues.unsupported("xhttp downloadSettings")

        return jsonObj {
            str("type", "xhttp")
            str("mode", m)
            str("host", h)
            str("path", p)
            if (headers.isNotEmpty()) el("headers", JsonObject(headers))
            el("x_padding_bytes", range(src.looseGet("xPaddingBytes"))?.takeUnless(::isZero) ?: JsonPrimitive(DEFAULT_PADDING))
            for ((from, to) in XHTTP_BOOLS) if (src.looseGet(from).scalar().let(::parseBool) == true) bool(to, true)
            for ((from, to) in XHTTP_STRINGS) str(to, src.looseGet(from).scalar()?.trim())
            for ((from, to) in XHTTP_RANGES) el(to, range(src.looseGet(from)))
            src.looseGet("scMaxBufferedPosts").scalar()?.toLongOrNull()?.let { num("sc_max_buffered_posts", it) }
            src.looseGet("xmux").asObj()?.takeIf { it.isNotEmpty() }?.let { x ->
                el(
                    "xmux",
                    jsonObj {
                        for ((from, to) in XMUX_RANGES) el(to, range(x.looseGet(from)))
                        x.looseGet("hKeepAlivePeriod").scalar()?.toLongOrNull()?.let { num("h_keep_alive_period", it) }
                    },
                )
            }
        }
    }

    companion object {
        const val DEFAULT_PADDING = "100-1000"
        private val XHTTP_NAMES = setOf("xhttp", "splithttp")
        private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")
        private val XHTTP_BOOLS = listOf(
            "noGRPCHeader" to "no_grpc_header",
            "noSSEHeader" to "no_sse_header",
            "xPaddingObfsMode" to "x_padding_obfs_mode",
        )
        private val XHTTP_STRINGS = listOf(
            "xPaddingKey" to "x_padding_key",
            "xPaddingHeader" to "x_padding_header",
            "xPaddingPlacement" to "x_padding_placement",
            "xPaddingMethod" to "x_padding_method",
            "uplinkHTTPMethod" to "uplink_http_method",
            "sessionIDPlacement" to "session_placement",
            "sessionPlacement" to "session_placement",
            "sessionIDKey" to "session_key",
            "sessionKey" to "session_key",
            "seqPlacement" to "seq_placement",
            "seqKey" to "seq_key",
            "uplinkDataPlacement" to "uplink_data_placement",
            "uplinkDataKey" to "uplink_data_key",
            "sessionIDTable" to "session_id_table",
        )
        private val XHTTP_RANGES = listOf(
            "scMaxEachPostBytes" to "sc_max_each_post_bytes",
            "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
            "scStreamUpServerSecs" to "sc_stream_up_server_secs",
            "uplinkChunkSize" to "uplink_chunk_size",
            "sessionIDLength" to "session_id_length",
        )
        private val XMUX_RANGES = listOf(
            "maxConcurrency" to "max_concurrency",
            "maxConnections" to "max_connections",
            "cMaxReuseTimes" to "c_max_reuse_times",
            "hMaxRequestTimes" to "h_max_request_times",
            "hMaxReusableSecs" to "h_max_reusable_secs",
        )
        private val KNOWN_FP = setOf(
            "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized",
            "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        )

        fun utls(fp: String?): JsonObject? {
            val f = fp?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
            val mapped = when (f) {
                in KNOWN_FP -> f
                "randomizednoalpn" -> "randomized"
                else -> "chrome"
            }
            return jsonObj { bool("enabled", true); str("fingerprint", mapped) }
        }

        /** xray `echConfigList`: inline base64 config, or a DNS source to query, which sing-box does itself. */
        fun ech(value: String?): JsonObject? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (v.startsWith("-----BEGIN")) {
                return jsonObj { bool("enabled", true); strings("config", v.lines().map { it.trim() }.filter { it.isNotEmpty() }) }
            }
            val b64 = B64.decode(v)
            return jsonObj {
                bool("enabled", true)
                if (b64 != null && "://" !in v) {
                    strings("config", listOf("-----BEGIN ECH CONFIGS-----", v, "-----END ECH CONFIGS-----"))
                }
            }
        }

        /** Number, `"a-b"`, `"a"` or `{from,to}` as a sing-box range value. */
        fun range(el: JsonElement?): JsonElement? = when (el) {
            is JsonObject -> {
                val from = el["from"].scalar()?.toLongOrNull()
                val to = el["to"].scalar()?.toLongOrNull()
                if (from != null && to != null) JsonPrimitive("$from-$to") else null
            }
            is JsonPrimitive -> when {
                el is kotlinx.serialization.json.JsonNull -> null
                !el.isString && el.longOrNull != null -> JsonPrimitive(el.longOrNull)
                else -> normalizeRange(el.content)?.let(::JsonPrimitive)
            }
            is JsonArray, null -> null
        }

        private fun isZero(el: JsonElement): Boolean {
            val s = el.scalar() ?: return true
            return s.split('-').all { it.toLongOrNull() == 0L }
        }

        fun fromQuery(u: ShareUrl, defaultSecurity: String = "none"): V2Stream {
            val network = (u.q("type", "network", "net") ?: "tcp").lowercase()
            val extra = u.q("extra")?.let { parseJsonOrNull(it).asObj() }
            val flatPadding = u.q("x_padding_bytes", "xpaddingbytes")
            val xhttp = if (flatPadding != null && extra?.looseGet("xPaddingBytes") == null) {
                (extra ?: JsonObject(emptyMap())).with("xPaddingBytes", JsonPrimitive(flatPadding))
            } else {
                extra
            }
            return V2Stream(
                network = network,
                security = u.q("security") ?: defaultSecurity,
                sni = u.q("sni", "peer", "servername"),
                alpn = u.q("alpn")?.let(::splitList).orEmpty(),
                fingerprint = u.q("fp", "fingerprint"),
                insecure = u.flag("allowinsecure", "insecure", "allow_insecure"),
                ech = u.q("ech"),
                realityPublicKey = u.q("pbk"),
                realityShortId = u.q("sid"),
                realityPqv = u.q("pqv"),
                host = u.q("host"),
                path = u.q("path"),
                serviceName = u.q("servicename", "service_name").takeIf { network == "grpc" || network == "gun" },
                mode = u.q("mode")?.takeIf { network in XHTTP_NAMES },
                headerType = u.q("headertype", "header_type"),
                kcpSeed = u.q("seed"),
                xhttp = xhttp,
                finalMask = u.q("fm") != null,
            )
        }

        fun fromXray(ss: JsonObject?): V2Stream {
            if (ss == null) return V2Stream()
            val network = (ss.str("network") ?: "tcp").lowercase()
            val tls = ss.child("tlsSettings")
            val reality = ss.child("realitySettings")
            var host: String? = null
            var path: String? = null
            var serviceName: String? = null
            var mode: String? = null
            var headerType: String? = null
            var seed: String? = null
            var xhttp: JsonObject? = null
            when (network) {
                "ws", "websocket" -> ss.child("wsSettings")?.let {
                    path = it.str("path")
                    host = it.str("host") ?: it.child("headers")?.str("Host", "host")
                }
                "grpc", "gun" -> ss.child("grpcSettings")?.let { serviceName = it.str("serviceName") }
                "httpupgrade" -> ss.child("httpupgradeSettings")?.let {
                    path = it.str("path")
                    host = it.str("host")
                }
                in XHTTP_NAMES -> ss.child("xhttpSettings", "splithttpSettings")?.let {
                    path = it.str("path")
                    host = it.str("host")
                    mode = it.str("mode")
                    val extra = it.child("extra")
                    xhttp = if (extra == null) it else JsonObject(it + extra)
                }
                "h2", "http" -> ss.child("httpSettings")?.let {
                    path = it.str("path")
                    host = it.strList("host").joinToString(",").ifEmpty { null }
                }
                "tcp", "raw" -> headerType = ss.child("tcpSettings", "rawSettings")?.child("header")?.str("type")
                "kcp", "mkcp" -> ss.child("kcpSettings")?.let {
                    headerType = it.child("header")?.str("type")
                    seed = it.str("seed")
                }
            }
            return V2Stream(
                network = network,
                security = ss.str("security") ?: "none",
                sni = tls?.str("serverName") ?: reality?.str("serverName"),
                alpn = tls?.strList("alpn").orEmpty(),
                fingerprint = tls?.str("fingerprint") ?: reality?.str("fingerprint"),
                insecure = tls?.bool("allowInsecure") == true,
                ech = tls?.str("echConfigList"),
                realityPublicKey = reality?.str("publicKey", "password"),
                realityShortId = reality?.str("shortId"),
                realityPqv = reality?.str("mldsa65Verify"),
                host = host,
                path = path,
                serviceName = serviceName,
                mode = mode,
                headerType = headerType,
                kcpSeed = seed,
                xhttp = xhttp,
                finalMask = ss.child("finalmask")?.isNotEmpty() == true,
            )
        }

        /** v2rayN vmess JSON; 3x-ui flattens the xhttp extra fields onto the same object. */
        fun fromVmessJson(o: JsonObject): V2Stream {
            val network = (o.str("net") ?: "tcp").lowercase()
            val type = o.str("type")
            return V2Stream(
                network = network,
                security = o.str("tls")?.takeIf { it.isNotEmpty() } ?: "none",
                sni = o.str("sni"),
                alpn = o.strList("alpn"),
                fingerprint = o.str("fp"),
                insecure = o.bool("allowInsecure", "insecure", "skip-cert-verify") == true,
                ech = o.str("ech"),
                realityPublicKey = o.str("pbk"),
                realityShortId = o.str("sid"),
                host = o.str("host"),
                path = o.str("path"),
                serviceName = if (network == "grpc") o.str("path", "serviceName") else null,
                mode = if (network in XHTTP_NAMES) o.str("mode") else null,
                headerType = type?.takeIf { network == "tcp" || network == "kcp" || network == "mkcp" },
                kcpSeed = o.str("seed"),
                xhttp = o.child("extra")?.let { JsonObject(o + it) } ?: o,
            )
        }
    }
}
