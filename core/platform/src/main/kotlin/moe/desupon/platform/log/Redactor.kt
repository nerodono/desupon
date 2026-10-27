package moe.desupon.platform.log

/** Masks credentials, keys and join codes in core text before it leaves this module; mirrors the core's own masking. */
object Redactor {
    private const val MASK = "***"

    private val joinLink = Regex("""(?i)(/call/join/)[^\s?#&"'/]+""")
    private val secretKey = Regex(
        """(?i)\b(private_key|preshared_key|public_key|password|passwd|pass|psk|pbk|sid|short_?id|token|access_token|secret|key|uuid|auth|client_?id|cid|link|links|join|obfs[-_]?password|header_protection_key)(["']?\s*[=:]\s*["']?)[^\s&"',;}\]]+""",
    )
    private val userInfo = Regex("""//[^/@\s]+@""")
    private val opaque = listOf(
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*"""),
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"""),
        Regex("""[A-Za-z0-9+/]{42,43}="""),
        // Hostnames and words stay under 32 characters; hex keys and base64url codes do not.
        Regex("""[A-Za-z0-9_-]{32,}"""),
    )

    fun mask(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s = joinLink.replace(text, "$1$MASK")
        s = secretKey.replace(s, "$1$2$MASK")
        s = userInfo.replace(s, "//$MASK@")
        for (p in opaque) s = p.replace(s, MASK)
        return s
    }

    fun message(error: Throwable): String = mask(error.message ?: error.javaClass.simpleName)
}

/** Core failure with a masked message and no cause chain, so the raw text cannot surface later. */
class CoreException(message: String) : Exception(message) {
    companion object {
        fun from(error: Throwable): CoreException = error as? CoreException ?: CoreException(Redactor.message(error))
    }
}
