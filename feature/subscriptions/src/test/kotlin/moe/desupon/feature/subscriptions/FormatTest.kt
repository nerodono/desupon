package moe.desupon.feature.subscriptions

import moe.desupon.model.node.Protocol
import moe.desupon.model.subscription.UserInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormatTest {
    private val gib = 1024L * 1024 * 1024
    private val dayMs = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun bytes() {
        assertEquals("0 Б", Format.bytes(0))
        assertEquals("1023 Б", Format.bytes(1023))
        assertEquals("1 КБ", Format.bytes(1024))
        assertEquals("1,5 КБ", Format.bytes(1536))
        assertEquals("12,3 ГБ", Format.bytes((12.3 * gib).toLong()))
        assertEquals("99,9 МБ", Format.bytes((99.94 * 1024 * 1024).toLong()))
        assertEquals("100 ГБ", Format.bytes(100 * gib))
        assertEquals("251 ГБ", Format.bytes((250.6 * gib).toLong()))
        assertEquals("2 ТБ", Format.bytes(2048 * gib))
    }

    @Test
    fun `traffic hidden without limit or usage`() {
        assertNull(Format.traffic(null))
        assertNull(Format.traffic(UserInfo(0, 0, 0, 0)))
        val unlimited = Format.traffic(UserInfo(upload = 10, download = 20, total = 0, expireEpochSec = 0))!!
        assertFalse(unlimited.limited)
        assertEquals(30, unlimited.used)
        val limited = Format.traffic(UserInfo(upload = gib, download = 3 * gib, total = 8 * gib, expireEpochSec = 0))!!
        assertTrue(limited.limited)
        assertEquals(0.5f, limited.fraction)
        assertEquals(1f, Format.traffic(UserInfo(0, 9 * gib, 8 * gib, 0))!!.fraction)
    }

    @Test
    fun `expiry and warning`() {
        fun info(expireMs: Long) = UserInfo(0, 0, 0, expireMs / 1000)
        assertNull(Format.expiry(null, now))
        assertNull(Format.expiry(UserInfo(0, 0, 0, 0), now))
        assertEquals(Expiry.Expired, Format.expiry(info(now - 1000), now))
        assertEquals(Expiry.Left(0), Format.expiry(info(now + dayMs / 2), now))
        assertEquals(Expiry.Left(2), Format.expiry(info(now + 2 * dayMs + 1000), now))
        assertEquals(Expiry.Left(3), Format.expiry(info(now + 3 * dayMs + 1000), now))
        assertEquals(Expiry.Left(30), Format.expiry(info(now + 30 * dayMs + 1000), now))

        assertFalse(Format.expiringSoon(null))
        assertTrue(Format.expiringSoon(Expiry.Expired))
        assertTrue(Format.expiringSoon(Expiry.Left(0)))
        assertTrue(Format.expiringSoon(Expiry.Left(2)))
        assertFalse(Format.expiringSoon(Expiry.Left(3)))
    }

    @Test
    fun flags() {
        assertEquals("🇳🇱", Format.flag("NL"))
        assertEquals("🇫🇮", Format.flag("fi"))
        assertNull(Format.flag(null))
        assertNull(Format.flag("NLD"))
        assertNull(Format.flag("1A"))
    }

    @Test
    fun `every protocol has a label`() {
        for (p in Protocol.entries) assertTrue(Format.protocol(p).isNotBlank(), p.name)
        assertEquals("AmneziaWG", Format.protocol(Protocol.AMNEZIAWG))
    }

    @Test
    fun `cleartext flags any plain http mirror`() {
        assertTrue(Format.cleartext("http://sub.example.net/s/token"))
        assertTrue(Format.cleartext("HTTP://sub.example.net/s/token"))
        assertTrue(Format.cleartext("https://a.example.net/s | http://b.example.net/s"))
        assertFalse(Format.cleartext("https://sub.example.net/s/token"))
        assertFalse(Format.cleartext(""))
    }

    @Test
    fun `ago buckets`() {
        assertEquals(Ago.JustNow, Format.ago(now - 59_000, now))
        assertEquals(Ago.JustNow, Format.ago(now + 60_000, now))
        assertEquals(Ago.Minutes(5), Format.ago(now - 5 * 60_000, now))
        assertEquals(Ago.Hours(1), Format.ago(now - 60 * 60_000, now))
        assertEquals(Ago.Hours(23), Format.ago(now - dayMs + 1, now))
        assertEquals(Ago.Days(3), Format.ago(now - 3 * dayMs, now))
    }

    @Test
    fun `stats break only after a separator`() {
        assertEquals("75\u00A0серверов\u00A0· нечитаемо:\u00A02", Format.stats(listOf("75 серверов", "нечитаемо: 2")))
        assertEquals("", Format.stats(emptyList()))
    }

    @Test
    fun `date in a given zone`() {
        assertEquals("15.01.2027", Format.date(1_800_000_000L, java.time.ZoneOffset.UTC))
    }
}
