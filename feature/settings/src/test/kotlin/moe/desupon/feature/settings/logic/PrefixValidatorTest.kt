package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class PrefixValidatorTest {
    private fun check(raw: String, existing: Set<String> = emptySet()) = PrefixValidator.validate(raw, existing)

    @Test
    fun acceptsWholeSegmentsAndNormalizes() {
        assertEquals(PrefixValidation.Valid("ru."), check("ru."))
        assertEquals(PrefixValidation.Valid("com.vendor_x.app2."), check("  com.vendor_x.app2. "))
        assertEquals(PrefixValidation.Valid("su."), check("SU."))
    }

    @Test
    fun rejectsMalformed() {
        assertEquals(PrefixValidation.Invalid(PrefixError.EMPTY), check("   "))
        assertEquals(PrefixValidation.Invalid(PrefixError.MISSING_DOT), check("ru"))
        assertEquals(PrefixValidation.Invalid(PrefixError.MISSING_DOT), check("com.vendor"))
        for (bad in listOf(".", ".ru.", "ru..", "1ru.", "ru.1x.", "r-u.", "ру.", "ru.*", "ru .")) {
            assertEquals(PrefixValidation.Invalid(PrefixError.INVALID), check(bad), bad)
        }
    }

    @Test
    fun rejectsDuplicateAfterNormalizing() {
        assertEquals(PrefixValidation.Invalid(PrefixError.DUPLICATE), check(" RU. ", setOf("ru.", "su.")))
    }
}
