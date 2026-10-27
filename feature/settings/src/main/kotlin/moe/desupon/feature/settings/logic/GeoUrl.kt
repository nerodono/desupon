package moe.desupon.feature.settings.logic

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Database download address: an http(s) URL of a file. */
object GeoUrl {
    /** Blank is valid: it restores the default. */
    fun isValid(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val uri = try {
            URI(t)
        } catch (_: URISyntaxException) {
            return false
        }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        if (uri.host.isNullOrEmpty() || (uri.port != -1 && uri.port !in 1..65535)) return false
        return !uri.rawPath.isNullOrEmpty() && !uri.rawPath.endsWith("/")
    }

    /** Over plain http the published checksum comes from the same unprotected origin, so it cannot catch a swapped file. */
    fun cleartext(text: String): Boolean = text.trim().startsWith("http://", ignoreCase = true)
}
