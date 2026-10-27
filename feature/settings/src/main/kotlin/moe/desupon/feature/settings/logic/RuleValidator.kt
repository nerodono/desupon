package moe.desupon.feature.settings.logic

import moe.desupon.model.routing.SplitRuleKind
import java.net.IDN
import java.util.Locale

enum class RuleError { EMPTY, INVALID_DOMAIN, INVALID_KEYWORD, INVALID_CIDR, UNKNOWN_RULE_SET }

sealed interface RuleValidation {
    /** [value] is the normalized form to store. */
    data class Valid(val value: String) : RuleValidation
    data class Invalid(val error: RuleError) : RuleValidation
}

/** Accepts a subset of what the config generator accepts, so a saved rule is never skipped there. */
object RuleValidator {
    private val LABEL = Regex("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?")
    private val KEYWORD = Regex("[a-z0-9._-]+")
    private const val MAX_DOMAIN = 253

    fun validate(kind: SplitRuleKind, raw: String, ruleSetNames: Collection<String>): RuleValidation {
        val text = raw.trim()
        if (text.isEmpty()) return RuleValidation.Invalid(RuleError.EMPTY)
        val value = when (kind) {
            SplitRuleKind.DOMAIN -> normalizeDomain(text) ?: return RuleValidation.Invalid(RuleError.INVALID_DOMAIN)
            SplitRuleKind.DOMAIN_SUFFIX -> {
                val dot = text.startsWith('.')
                val body = normalizeDomain(if (dot) text.substring(1) else text)
                    ?: return RuleValidation.Invalid(RuleError.INVALID_DOMAIN)
                if (dot) ".$body" else body
            }
            SplitRuleKind.DOMAIN_KEYWORD -> text.lowercase(Locale.ROOT).takeIf { KEYWORD.matches(it) }
                ?: return RuleValidation.Invalid(RuleError.INVALID_KEYWORD)
            SplitRuleKind.IP_CIDR -> normalizeCidr(text) ?: return RuleValidation.Invalid(RuleError.INVALID_CIDR)
            SplitRuleKind.RULE_SET -> text.takeIf { it in ruleSetNames }
                ?: return RuleValidation.Invalid(RuleError.UNKNOWN_RULE_SET)
        }
        return RuleValidation.Valid(value)
    }

    /** Lowercase ASCII (punycode) domain without a trailing dot; null if malformed or IP-like. */
    fun normalizeDomain(text: String): String? {
        val trimmed = text.trim().removeSuffix(".")
        if (trimmed.isEmpty()) return null
        val ascii = try {
            IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED)
        } catch (_: IllegalArgumentException) {
            return null
        }.lowercase(Locale.ROOT)
        if (ascii.length > MAX_DOMAIN) return null
        val labels = ascii.split('.')
        if (labels.any { !LABEL.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null
        return ascii
    }

    /** IP literal or prefix as `addr/len` with lowercase hex; bare addresses get a host-length prefix. */
    fun normalizeCidr(text: String): String? {
        val t = text.trim()
        val slash = t.indexOf('/')
        val addr = if (slash < 0) t else t.substring(0, slash)
        val prefix = if (slash < 0) {
            null
        } else {
            val p = t.substring(slash + 1)
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            p.toInt()
        }
        if (parseIpv4(addr) != null) {
            if (prefix != null && prefix > 32) return null
            return "$addr/${prefix ?: 32}"
        }
        if (parseIpv6(addr) != null) {
            if (prefix != null && prefix > 128) return null
            return "${addr.lowercase(Locale.ROOT)}/${prefix ?: 128}"
        }
        return null
    }

    /** Dotted quad without leading zeros, as Go's netip requires. */
    fun parseIpv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    /** Eight 16-bit groups; accepts `::` compression and a trailing dotted quad, rejects zones. */
    fun parseIpv6(s: String): IntArray? {
        if (s.count { it == ':' } < 2) return null
        val gap = s.indexOf("::")
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) return null
        val head: List<Int>
        val tail: List<Int>
        if (gap >= 0) {
            head = groups(s.substring(0, gap), allowV4 = false) ?: return null
            tail = groups(s.substring(gap + 2), allowV4 = true) ?: return null
            if (head.size + tail.size > 7) return null
        } else {
            head = groups(s, allowV4 = true) ?: return null
            tail = emptyList()
            if (head.size != 8) return null
        }
        val out = IntArray(8)
        head.forEachIndexed { i, v -> out[i] = v }
        tail.forEachIndexed { i, v -> out[8 - tail.size + i] = v }
        return out
    }

    private fun groups(part: String, allowV4: Boolean): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val pieces = part.split(':')
        val out = ArrayList<Int>(8)
        for ((i, p) in pieces.withIndex()) {
            if ('.' in p) {
                if (!allowV4 || i != pieces.lastIndex) return null
                val v4 = parseIpv4(p) ?: return null
                out += (v4[0] shl 8) or v4[1]
                out += (v4[2] shl 8) or v4[3]
            } else {
                if (p.isEmpty() || p.length > 4 || !p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                out += p.toInt(16)
            }
        }
        return out
    }
}
