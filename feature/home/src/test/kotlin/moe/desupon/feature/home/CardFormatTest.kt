package moe.desupon.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardFormatTest {
    @Test
    fun bytesBelowOneKibStayInBytes() {
        assertEquals(ByteAmount("0", ByteUnit.B), formatBytes(0))
        assertEquals(ByteAmount("1023", ByteUnit.B), formatBytes(1023))
        assertEquals(ByteAmount("0", ByteUnit.B), formatBytes(-5))
    }

    @Test
    fun bytesUseBinaryUnitsWithRussianDecimalComma() {
        assertEquals(ByteAmount("1", ByteUnit.KB), formatBytes(1024))
        assertEquals(ByteAmount("1,5", ByteUnit.KB), formatBytes(1536))
        assertEquals(ByteAmount("512", ByteUnit.MB), formatBytes(512L * 1024 * 1024))
        assertEquals(ByteAmount("65,6", ByteUnit.GB), formatBytes((65.6 * GIB).toLong()))
        assertEquals(ByteAmount("150", ByteUnit.GB), formatBytes(150 * GIB))
        assertEquals(ByteAmount("2", ByteUnit.TB), formatBytes(2048 * GIB))
    }

    @Test
    fun terabytesAreTheLargestUnit() {
        assertEquals(ByteAmount("4096", ByteUnit.TB), formatBytes(4096L * 1024 * GIB))
    }

    @Test
    fun zeroTotalMeansUnlimited() {
        assertEquals(TrafficCard(ByteAmount("3", ByteUnit.GB), null), trafficCard(3 * GIB, 0))
        assertEquals(TrafficCard(ByteAmount("0", ByteUnit.B), ByteAmount("50", ByteUnit.GB)), trafficCard(0, 50 * GIB))
    }

    @Test
    fun noUsageAndNoLimitHidesTrafficCard() {
        assertNull(trafficCard(0, 0))
    }

    @Test
    fun daysLeftCountsAStartedDayAsWhole() {
        val exactly21 = (NOW_MS + 21 * DAY_MILLIS) / 1000
        assertEquals(21, daysLeft(exactly21, NOW_MS))
        assertEquals(21, daysLeft(exactly21, NOW_MS + 1000))
        assertEquals(1, daysLeft((NOW_MS + 60_000) / 1000, NOW_MS))
        assertEquals(0, daysLeft(NOW_MS / 1000, NOW_MS))
        assertEquals(0, daysLeft(NOW_MS / 1000 - 3600, NOW_MS))
    }

    @Test
    fun expiryCardFlags() {
        assertTrue(ExpiryCard(0).expired)
        assertFalse(ExpiryCard(0).endingSoon)
        assertTrue(ExpiryCard(3).endingSoon)
        assertFalse(ExpiryCard(4).endingSoon)
        assertFalse(ExpiryCard(4).expired)
    }

    @Test
    fun flagEmojiFromIsoCode() {
        assertEquals("🇸🇬", flagEmoji("SG"))
        assertEquals("🇸🇬", flagEmoji("sg"))
        assertNull(flagEmoji(null))
        assertNull(flagEmoji("SGP"))
        assertNull(flagEmoji("1A"))
    }
}
