package moe.desupon.parser.internal

import java.util.Base64

/** Key-material shapes the core enforces at start; any base64 spelling is accepted and re-emitted canonically. */
internal object Keys {
    // All dashes or none: the core's UUID decoder refuses anything in between.
    private val UUID = Regex("[0-9a-fA-F]{8}(-?)[0-9a-fA-F]{4}\\1[0-9a-fA-F]{4}\\1[0-9a-fA-F]{4}\\1[0-9a-fA-F]{12}")

    /** Standard padded base64 of exactly [size] bytes. */
    fun std(raw: String, size: Int, what: String): String {
        val bytes = B64.decode(raw.trim()) ?: malformed("$what is not base64")
        if (bytes.size != size) malformed("$what is not $size bytes")
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** REALITY X25519 public key as unpadded base64url. */
    fun reality(raw: String): String {
        val bytes = B64.decode(raw.trim()) ?: malformed("REALITY public key is not base64")
        if (bytes.size != 32) malformed("REALITY public key is not 32 bytes")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun uuid(raw: String?, what: String): String {
        val v = raw?.trim().orEmpty()
        if (!UUID.matches(v)) malformed("$what is not a UUID")
        return v
    }

    /** SS-2022 `iPSK:…:uPSK`, each key 16 bytes for aes-128 and 32 for the rest; chacha20 has no multi-user form. */
    fun ss2022(method: String, password: String, issues: Issues): String {
        val size = if (method == "2022-blake3-aes-128-gcm") 16 else 32
        val keys = password.split(':')
        if (keys.size > 1 && method == "2022-blake3-chacha20-poly1305") issues.unsupported("shadowsocks 2022 chacha20 multi-user keys")
        return keys.joinToString(":") { std(it, size, "shadowsocks 2022 key") }
    }
}
