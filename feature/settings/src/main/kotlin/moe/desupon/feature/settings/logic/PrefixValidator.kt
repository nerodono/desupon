package moe.desupon.feature.settings.logic

import java.util.Locale

enum class PrefixError { EMPTY, INVALID, MISSING_DOT, DUPLICATE }

sealed interface PrefixValidation {
    /** [value] is the normalized form to store. */
    data class Valid(val value: String) : PrefixValidation
    data class Invalid(val error: PrefixError) : PrefixValidation
}

/** A bypass prefix is one or more whole package-name segments, each followed by a dot, e.g. `ru.` or `com.vendor.`. */
object PrefixValidator {
    private val PREFIX = Regex("([a-z][a-z0-9_]*\\.)+")

    fun validate(raw: String, existing: Collection<String>): PrefixValidation {
        val text = raw.trim().lowercase(Locale.ROOT)
        val error = when {
            text.isEmpty() -> PrefixError.EMPTY
            !text.endsWith('.') && PREFIX.matches("$text.") -> PrefixError.MISSING_DOT
            !PREFIX.matches(text) -> PrefixError.INVALID
            text in existing -> PrefixError.DUPLICATE
            else -> return PrefixValidation.Valid(text)
        }
        return PrefixValidation.Invalid(error)
    }
}
