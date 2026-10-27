package moe.desupon.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Header vectors adapted from v2rayNG `SubscriptionInfoParserTest`, plus Happ-style body directives. */
class HeadersTest {
    private fun meta(vararg h: Pair<String, String>, host: String? = null) = SubscriptionHeaders.parse(mapOf(*h), sourceHost = host)

    @Test
    fun `no headers gives empty meta`() {
        assertEquals(SubscriptionMeta.EMPTY, meta())
        assertEquals(SubscriptionMeta.EMPTY, meta("subscription-userinfo" to "  ", "profile-title" to "", "support-url" to "ftp://x", "profile-web-page-url" to "javascript:alert(1)"))
    }

    @Test
    fun `standard and tolerant userinfo`() {
        val std = SubscriptionHeaders.parseUserInfo("upload=100; download=200; total=1000; expire=1893456000")!!
        assertEquals(listOf(100L, 200L, 1000L, 1893456000L), listOf(std.upload, std.download, std.total, std.expireEpochSec))
        val loose = SubscriptionHeaders.parseUserInfo("Upload=1.5e3;DOWNLOAD = 10 ;foo=bar;total=-5;expire=")!!
        assertEquals(listOf(1500L, 10L, 0L, 0L), listOf(loose.upload, loose.download, loose.total, loose.expireEpochSec))
        assertEquals(1893456000, SubscriptionHeaders.parseUserInfo("expire=1893456000000")!!.expireEpochSec)
        assertEquals(5, SubscriptionHeaders.parseUserInfo("total=5")!!.total)
        assertNull(SubscriptionHeaders.parseUserInfo("hello world"))
        assertNull(SubscriptionHeaders.parseUserInfo(null))
    }

    @Test
    fun `header names are case-insensitive`() {
        val m = meta("Subscription-Userinfo" to "upload=1; download=2; total=3; expire=4", "Profile-Update-Interval" to " 12 ")
        assertEquals(3, m.userInfo!!.total)
        assertEquals(12, m.updateIntervalH)
    }

    @Test
    fun `update interval`() {
        assertEquals(6, SubscriptionHeaders.parseUpdateInterval("6"))
        assertEquals(1, SubscriptionHeaders.parseUpdateInterval("0.5"))
        assertNull(SubscriptionHeaders.parseUpdateInterval("0"))
        assertNull(SubscriptionHeaders.parseUpdateInterval("-3"))
        assertNull(SubscriptionHeaders.parseUpdateInterval("abc"))
    }

    @Test
    fun `titles and announce are base64 prefixed, plain or percent-encoded`() {
        assertEquals("اشتراک من", meta("profile-title" to "base64:" + Fx.b64("اشتراک من")).title)
        assertEquals("My Plan", meta("profile-title" to "My Plan").title)
        assertEquals("My Plan", meta("profile-title" to "My%20Plan").title)
        assertEquals("Сервис работает", meta("announce" to "base64:" + Fx.b64("Сервис работает")).announce)
        assertEquals("plain note", meta("announce" to "plain note").announce)
        assertNull(meta("profile-title" to "base64:!!!").title)
    }

    @Test
    fun `title falls back to content-disposition then host`() {
        assertEquals("My VPN", meta("content-disposition" to "attachment; filename=\"My VPN.txt\"").title)
        assertEquals("Мой VPN", meta("content-disposition" to "attachment; filename=x.txt; filename*=UTF-8''%D0%9C%D0%BE%D0%B9%20VPN.yaml").title)
        assertEquals("sub.example.com", meta(host = "sub.example.com").title)
        assertEquals("Header", meta("profile-title" to "Header", "content-disposition" to "attachment; filename=f.txt", host = "h").title)
    }

    @Test
    fun `links are sanitized`() {
        assertEquals("https://t.me/support", meta("support-url" to " https://t.me/support ").supportUrl)
        assertEquals("tg://resolve?domain=x", meta("support-url" to "tg://resolve?domain=x").supportUrl)
        assertEquals("https://panel.example.com/info", meta("profile-web-page-url" to "https://panel.example.com/info").webPageUrl)
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "https://", null)) {
            assertNull(SubscriptionHeaders.sanitizeLink(bad), bad)
        }
    }

    @Test
    fun `alt endpoints are split, filtered and secret`() {
        val m = meta(
            "Subscription-Alt-Endpoints" to "https://a.example.com/sub/x, https://b.example.com/sub/x|javascript:1 https://a.example.com/sub/x",
            "fallback-url" to "https://d.example.com/s",
        )
        assertEquals(
            listOf("https://a.example.com/sub/x", "https://b.example.com/sub/x", "https://d.example.com/s"),
            m.altEndpoints.map { it.raw() },
        )
        assertEquals("***", m.altEndpoints[0].toString())
    }

    @Test
    fun `body directives fill meta, headers win`() {
        val body = listOf(
            "#profile-title: base64:" + Fx.b64("Body Title"),
            "#subscription-userinfo: upload=1; download=2; total=3; expire=4",
            "# profile-update-interval: 12",
            "#announce: hello",
            "#just a comment: not a directive",
            Fx.vector("husi.trojan_default"),
        ).joinToString("\n")
        val r = Fx.parse(body)
        assertEquals("Body Title", r.meta.title)
        assertEquals(4, r.meta.userInfo!!.expireEpochSec)
        assertEquals(12, r.meta.updateIntervalH)
        assertEquals("hello", r.meta.announce)
        assertEquals(1, r.nodes.size)

        val overridden = SubscriptionParser.parse(body, Fx.SUB, mapOf("Profile-Title" to "Header Title"))
        assertEquals("Header Title", overridden.meta.title)

        val wrapped = Fx.parse(Fx.b64(body))
        assertEquals("Body Title", wrapped.meta.title)
    }
}
