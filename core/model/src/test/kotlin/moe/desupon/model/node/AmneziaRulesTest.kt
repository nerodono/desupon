package moe.desupon.model.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmneziaRulesTest {
    @Test
    fun `ranges are single numbers or ordered uint32 pairs`() {
        assertEquals(5L..5L, AmneziaRules.range("5"))
        assertEquals(100L..200L, AmneziaRules.range("100-200"))
        assertEquals(5L..5L, AmneziaRules.range("+5"))
        assertNull(AmneziaRules.range(" 100-200"), "the core does not trim")
        assertNull(AmneziaRules.range("100 - 200"))
        assertNull(AmneziaRules.range(""))
        assertEquals("100-200", AmneziaRules.canonical(100L..200L))
        assertEquals("7", AmneziaRules.canonical(7L..7L))
        assertEquals(0L..4294967295L, AmneziaRules.range("0-4294967295"))
        assertNull(AmneziaRules.range("200-100"))
        assertNull(AmneziaRules.range("4294967296"))
        assertNull(AmneziaRules.range("1-2-3"))
        assertNull(AmneziaRules.range("-1"))
        assertNull(AmneziaRules.range("abc"))
    }

    @Test
    fun `unset headers keep their WireGuard message type`() {
        assertNull(AmneziaRules.overlap(listOf(null, null, null, null)))
        assertNull(AmneziaRules.overlap(listOf(100L..200L, 300L..400L, 500L..600L, 700L..800L)))
        assertEquals(0 to 1, AmneziaRules.overlap(listOf(2L..2L, null, null, null)))
        assertEquals(1 to 3, AmneziaRules.overlap(listOf(10L..20L, 30L..40L, 50L..60L, 35L..36L)))
    }

    @Test
    fun `tag chains follow the device parser`() {
        assertTrue(AmneziaRules.validChain(""))
        assertTrue(AmneziaRules.validChain("<b 0xaabb><r 16><rc 4><rd 2><c><t><dz 8>"))
        assertTrue(AmneziaRules.validChain("text <b AABB> between"))
        assertFalse(AmneziaRules.validChain("<b 0xabc>"))
        assertFalse(AmneziaRules.validChain("<b 0x>"))
        assertFalse(AmneziaRules.validChain("<r>"))
        assertFalse(AmneziaRules.validChain("<r -1>"))
        assertFalse(AmneziaRules.validChain("<x 1>"))
        assertFalse(AmneziaRules.validChain("<>"))
        assertFalse(AmneziaRules.validChain("<r 16"))
        assertFalse(AmneziaRules.validChain("<r 16>\n<r 4>"), "the device reads its settings line by line")
        assertTrue(AmneziaRules.validChain("<r\u00A016>"), "any whitespace separates, as in Go strings.Fields")
        assertFalse(AmneziaRules.validChain("<r 70000>"))
        assertFalse(AmneziaRules.validChain("<r ٣>"), "Go reads ASCII digits only")
    }

    @Test
    fun `violations name the setting`() {
        fun of(vararg pairs: Pair<String, String>) = AmneziaRules.violation(mapOf(*pairs)::get)
        assertNull(of("jc" to "4", "jmin" to "40", "jmax" to "70", "h1" to "100-200", "i1" to "<r 16>"))
        assertEquals("AmneziaWG Jmin above Jmax", of("jc" to "4", "jmin" to "80", "jmax" to "70"))
        assertEquals("AmneziaWG Jmin above Jmax", of("jc" to "4", "jmin" to "10"))
        assertNull(of("jc" to "0", "jmin" to "80", "jmax" to "70"))
        assertEquals("AmneziaWG H2 is not a range", of("h2" to "9-1"))
        assertEquals("AmneziaWG H1-H4 overlap", of("h1" to "3"))
        assertEquals("AmneziaWG I3 is not a tag chain", of("i3" to "<b 0x1>"))
        assertEquals("AmneziaWG Jc above 128", of("jc" to "200"))
        assertEquals("AmneziaWG size above 65535", of("s3" to "70000"))
        assertEquals("AmneziaWG H4 is not a range", of("h4" to ""))
    }
}
