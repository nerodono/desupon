package moe.desupon.feature.settings.logic

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Remote DNS address check matching the config generator's accepted forms. */
object DnsAddress {
    /** Scheme to whether a path is allowed. */
    private val SCHEMES = mapOf(
        "https" to true,
        "h3" to true,
        "tls" to false,
        "quic" to false,
        "tcp" to false,
        "udp" to false,
    )

    /** Blank is valid: it restores the default server. */
    fun isValid(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if ("://" !in t && t.count { it == ':' } > 1) return RuleValidator.parseIpv6(t.removeSurrounding("[", "]")) != null
        val uri = try {
            URI(if ("://" in t) t else "udp://$t")
        } catch (_: URISyntaxException) {
            return false
        }
        val pathAllowed = SCHEMES[uri.scheme?.lowercase(Locale.ROOT)] ?: return false
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return false
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (uri.port != -1 && uri.port !in 1..65535) return false
        if (!pathAllowed && !uri.path.isNullOrEmpty() && uri.path != "/") return false
        return RuleValidator.parseIpv4(host) != null ||
            RuleValidator.parseIpv6(host) != null ||
            RuleValidator.normalizeDomain(host) == host.lowercase(Locale.ROOT)
    }
}
