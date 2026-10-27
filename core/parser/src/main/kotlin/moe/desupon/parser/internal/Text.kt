package moe.desupon.parser.internal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal object B64 {
    private val ALPHABET = BooleanArray(128).also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/-_=".forEach { t[it.code] = true }
    }

    /** Standard or url-safe alphabet, padded or not, whitespace ignored; null when not base64. */
    fun decode(input: String): ByteArray? {
        val clean = StringBuilder(input.length)
        for (c in input) {
            if (c.isWhitespace()) continue
            if (c.code >= 128 || !ALPHABET[c.code]) return null
            clean.append(
                when (c) {
                    '-' -> '+'
                    '_' -> '/'
                    else -> c
                },
            )
        }
        var s = clean.toString().trimEnd('=')
        if (s.isEmpty() || '=' in s || s.length % 4 == 1) return null
        s += "=".repeat((4 - s.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decodeUtf8(input: String): String? = decode(input)?.let(::strictUtf8)

    fun encodeUrlNoPad(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun strictUtf8(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

/** RFC 3986 percent-decoding; malformed escapes stay literal. [plusAsSpace] for form-encoded query values. */
internal fun percentDecode(s: String, plusAsSpace: Boolean = false): String {
    if ('%' !in s && !(plusAsSpace && '+' in s)) return s
    val out = ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0 -> {
                out.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
                continue
            }
            c == '+' && plusAsSpace -> out.write(' '.code)
            else -> {
                val cp = s.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
                continue
            }
        }
        i++
    }
    return strictUtf8(out.toByteArray()) ?: s
}

private fun hex(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

internal fun stripBom(s: String): String = s.removePrefix("﻿")

internal fun String.lines2(): List<String> = split('\n').map { it.removeSuffix("\r") }

/** `host:port`, bracketing IPv6. */
internal fun hostPort(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

internal fun isIpLiteral(host: String): Boolean =
    ':' in host || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

/** `a-b`, `a`, or a number, normalized for sing-box `badoption.Range`; null when not a range. */
internal fun normalizeRange(raw: String?): String? {
    val s = raw?.replace(" ", "")?.trim() ?: return null
    if (s.isEmpty()) return null
    val parts = s.split('-')
    return when {
        parts.size == 1 && parts[0].toLongOrNull() != null -> parts[0]
        parts.size == 2 && parts.all { it.toLongOrNull() != null } -> s
        else -> null
    }
}
