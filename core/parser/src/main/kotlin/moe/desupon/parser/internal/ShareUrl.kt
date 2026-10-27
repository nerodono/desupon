package moe.desupon.parser.internal

internal class MalformedException(message: String) : Exception(message)

internal fun malformed(what: String): Nothing = throw MalformedException(what)

/** Readable entry that holds no proxy node this parser can express. */
internal class NotANodeException(message: String) : Exception(message)

/**
 * Lenient `scheme://[userinfo@]host[:ports][/path][?query][#fragment]` splitter.
 * Share links routinely carry unescaped emoji, spaces and base64 `/` in userinfo, which java.net.URI rejects.
 */
internal class ShareUrl private constructor(
    val scheme: String,
    /** Still percent-encoded: ss needs the raw form to tell base64 from `method:password`. */
    val userInfoRaw: String?,
    val host: String,
    /** Port part as written; hysteria2 allows `443,20000-30000`. */
    val portSpec: String?,
    val path: String,
    private val query: Map<String, String>,
    val fragment: String?,
) {
    val userInfo: String? get() = userInfoRaw?.let { percentDecode(it) }

    val port: Int? get() = portSpec?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun requirePort(default: Int? = null): Int {
        if (portSpec.isNullOrBlank()) return default ?: malformed("missing port")
        return port ?: malformed("bad port")
    }

    fun requireHost(): String = host.ifEmpty { malformed("missing host") }

    /** First non-blank value among [keys]; form-decoded so `+` is a space, as Go and JS link builders emit. */
    fun q(vararg keys: String): String? {
        for (k in keys) {
            val v = query[k.lowercase()]?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    /** For base64 key material: a literal `+` that form-decoding turned into a space is restored. */
    fun qKey(vararg keys: String): String? {
        for (k in keys) {
            val v = query[k.lowercase()]?.replace(' ', '+')?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    fun flag(vararg keys: String): Boolean = keys.any { parseBool(q(it)) == true }

    fun has(key: String): Boolean = query.containsKey(key.lowercase())

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

        /** Happ subtitle that 3x-ui appends to the remark. */
        private val HAPP_DESCRIPTION = Regex("\\?serverDescription=[A-Za-z0-9+/=_%-]*$")

        fun schemeOf(line: String): String? = SCHEME.find(line.trim())?.groupValues?.get(1)?.lowercase()

        fun parse(raw: String): ShareUrl {
            val line = raw.trim()
            val m = SCHEME.find(line) ?: malformed("no scheme")
            var rest = line.substring(m.range.last + 1)

            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = percentDecode(rest.substring(it + 1).replace(HAPP_DESCRIPTION, "")).trim()
                rest = rest.substring(0, it)
            }
            var queryStr = ""
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                queryStr = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }
            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) rest.substring(0, at) else null
            var hostPart = if (at >= 0) rest.substring(at + 1) else rest
            var path = ""
            hostPart.indexOf('/').takeIf { it >= 0 }?.let {
                path = percentDecode(hostPart.substring(it))
                hostPart = hostPart.substring(0, it)
            }

            val host: String
            var portSpec: String? = null
            if (hostPart.startsWith("[")) {
                val close = hostPart.indexOf(']')
                if (close < 0) malformed("unterminated IPv6 literal")
                host = hostPart.substring(1, close)
                val after = hostPart.substring(close + 1)
                if (after.startsWith(":")) portSpec = after.substring(1)
                else if (after.isNotEmpty()) malformed("junk after IPv6 literal")
            } else {
                val colons = hostPart.count { it == ':' }
                if (colons == 1) {
                    val c = hostPart.indexOf(':')
                    host = hostPart.substring(0, c)
                    portSpec = hostPart.substring(c + 1)
                } else {
                    host = hostPart
                }
            }

            val query = LinkedHashMap<String, String>()
            if (queryStr.isNotEmpty()) {
                for (pair in queryStr.split('&')) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val k = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair, plusAsSpace = true).trim().lowercase()
                    val v = if (eq >= 0) percentDecode(pair.substring(eq + 1), plusAsSpace = true) else ""
                    if (k.isNotEmpty() && k !in query) query[k] = v
                }
            }
            return ShareUrl(
                scheme = m.groupValues[1].lowercase(),
                userInfoRaw = userInfo,
                host = percentDecode(host).trim(),
                portSpec = portSpec?.trim(),
                path = path,
                query = query,
                fragment = fragment?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
