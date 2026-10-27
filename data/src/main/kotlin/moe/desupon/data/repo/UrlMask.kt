package moe.desupon.data.repo

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Display form of a subscription URL: scheme, host and the leading non-secret path, the token part as [MASK]. */
object UrlMask {
    const val MASK = "••••"

    fun mask(url: String): String {
        val u = url.trim().toHttpUrlOrNull() ?: return MASK
        val out = StringBuilder(u.scheme).append("://").append(if (':' in u.host) "[${u.host}]" else u.host)
        if (u.port != HttpUrl.defaultPort(u.scheme)) out.append(':').append(u.port)
        val segments = u.pathSegments.filter { it.isNotEmpty() }
        var kept = segments.takeWhile { !looksSecret(it) }
        // Without a query the token is the path itself, so its last segment never shows even if it looks plain.
        if (u.encodedQuery == null && kept.size == segments.size && kept.isNotEmpty()) kept = kept.dropLast(1)
        for (s in kept) out.append('/').append(s)
        if (kept.size < segments.size) out.append('/').append(MASK)
        if (u.encodedQuery != null) out.append('?').append(MASK)
        return out.toString()
    }

    private fun looksSecret(segment: String): Boolean =
        segment.length >= SECRET_LENGTH || (segment.count(Char::isDigit) >= SECRET_DIGITS && segment.length >= SECRET_DIGIT_LENGTH)

    private const val SECRET_LENGTH = 12
    private const val SECRET_DIGITS = 3
    private const val SECRET_DIGIT_LENGTH = 6
}
