package moe.desupon.parser

import moe.desupon.model.Secret
import moe.desupon.model.subscription.UserInfo
import moe.desupon.parser.internal.B64
import moe.desupon.parser.internal.percentDecode
import kotlin.math.roundToInt

object SubscriptionHeaders {
    const val USERINFO = "subscription-userinfo"
    const val UPDATE_INTERVAL = "profile-update-interval"
    const val TITLE = "profile-title"
    const val ANNOUNCE = "announce"
    const val SUPPORT_URL = "support-url"
    const val WEB_PAGE_URL = "profile-web-page-url"
    const val CONTENT_DISPOSITION = "content-disposition"
    const val ALT_ENDPOINTS = "subscription-alt-endpoints"
    const val FALLBACK_URL = "fallback-url"

    /** Keys also accepted as `#key: value` lines in the body. */
    internal val DIRECTIVE_KEYS = setOf(
        USERINFO, UPDATE_INTERVAL, TITLE, ANNOUNCE, SUPPORT_URL, WEB_PAGE_URL, ALT_ENDPOINTS, FALLBACK_URL,
        "routing", "routing-enable", "hide-settings",
    )

    /**
     * [headers] win over [directives]; names are matched case-insensitively.
     * Title falls back to the `content-disposition` file name, then [sourceHost].
     */
    fun parse(
        headers: Map<String, String>,
        directives: Map<String, String> = emptyMap(),
        sourceHost: String? = null,
    ): SubscriptionMeta {
        val h = HashMap<String, String>()
        directives.forEach { (k, v) -> h[k.trim().lowercase()] = v }
        headers.forEach { (k, v) -> if (v.isNotBlank()) h[k.trim().lowercase()] = v }

        val title = decodeText(h[TITLE])
            ?: contentDispositionFilename(h[CONTENT_DISPOSITION])
            ?: sourceHost?.trim()?.takeIf { it.isNotEmpty() }
        val alt = (splitUrls(h[ALT_ENDPOINTS]) + splitUrls(h[FALLBACK_URL])).distinct().map(::Secret)
        return SubscriptionMeta(
            title = title,
            userInfo = parseUserInfo(h[USERINFO]),
            updateIntervalH = parseUpdateInterval(h[UPDATE_INTERVAL]),
            supportUrl = sanitizeLink(h[SUPPORT_URL]),
            webPageUrl = sanitizeLink(h[WEB_PAGE_URL]),
            announce = decodeText(h[ANNOUNCE]),
            altEndpoints = alt,
        )
    }

    /** `upload=…; download=…; total=…; expire=…` in any case/order/spacing; null when no key is readable. */
    fun parseUserInfo(value: String?): UserInfo? {
        if (value.isNullOrBlank()) return null
        val fields = HashMap<String, Long>()
        for (part in value.split(';', ',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim().lowercase()
            if (key !in USERINFO_KEYS) continue
            val raw = part.substring(eq + 1).trim()
            val num = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
            fields[key] = (num ?: 0L).coerceAtLeast(0L)
        }
        if (fields.isEmpty()) return null
        var expire = fields["expire"] ?: 0L
        if (expire > MS_THRESHOLD) expire /= 1000
        return UserInfo(
            upload = fields["upload"] ?: 0L,
            download = fields["download"] ?: 0L,
            total = fields["total"] ?: 0L,
            expireEpochSec = expire,
        )
    }

    /** Hours; null when absent or non-positive. */
    fun parseUpdateInterval(value: String?): Int? {
        val d = value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        return d.roundToInt().coerceAtLeast(1)
    }

    /** `base64:<b64>` or plain, the latter optionally percent-encoded. */
    fun decodeText(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val text = if (v.startsWith("base64:", ignoreCase = true)) {
            B64.decodeUtf8(v.substring(7)) ?: return null
        } else {
            percentDecode(v)
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    /** RFC 6266 `filename*=UTF-8''…` preferred over `filename=…`; the extension is dropped. */
    fun contentDispositionFilename(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val params = value.split(';').map { it.trim() }
        val star = params.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.substring(10)?.let { v ->
            val q = v.indexOf("''")
            percentDecode(if (q >= 0) v.substring(q + 2) else v)
        }
        val plain = params.firstOrNull { it.startsWith("filename=", ignoreCase = true) }?.substring(9)
            ?.trim()?.removeSurrounding("\"")?.let { percentDecode(it) }
        val name = (star ?: plain)?.trim()?.substringAfterLast('/') ?: return null
        val base = name.replace(EXT, "").trim()
        return base.ifEmpty { null }
    }

    /** Only links a UI may open: http(s) with a host, or tg://. */
    fun sanitizeLink(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = v.lowercase()
        return when {
            lower.startsWith("tg://") && v.length > 5 -> v
            (lower.startsWith("https://") || lower.startsWith("http://")) &&
                v.substringAfter("://").substringBefore('/').isNotBlank() -> v
            else -> null
        }
    }

    private fun splitUrls(value: String?): List<String> =
        value.orEmpty().split(',', '|', ' ', '\n', '\t').map { it.trim() }
            .filter { sanitizeLink(it) != null && !it.startsWith("tg://", ignoreCase = true) }

    private val USERINFO_KEYS = setOf("upload", "download", "total", "expire")
    private const val MS_THRESHOLD = 100_000_000_000L
    private val EXT = Regex("\\.(txt|ya?ml|json|conf|list|sub)$", RegexOption.IGNORE_CASE)
}
