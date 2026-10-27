package moe.desupon.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlMaskTest {
    @Test
    fun tokenPartIsMasked() {
        assertEquals("https://sub.example.com/sub/••••", UrlMask.mask("https://sub.example.com/sub/AbCdEfGh12345678"))
        assertEquals("https://example.com:2096/sub/••••", UrlMask.mask("https://example.com:2096/sub/user123abc"))
        assertEquals("https://example.com/api/v1/client/subscribe?••••", UrlMask.mask("https://example.com/api/v1/client/subscribe?token=abc"))
        assertEquals("https://example.com/s/••••", UrlMask.mask("https://example.com/s/alice"))
        assertEquals("http://example.com/••••", UrlMask.mask("http://user:pw@example.com/0f3c9a8e-1b2c-4d5e-8f90-123456789abc/extra"))
        assertEquals("https://example.com", UrlMask.mask("https://example.com/"))
        assertEquals("https://[2001:db8::1]:8443/••••", UrlMask.mask("https://[2001:db8::1]:8443/sub"))
        assertEquals(UrlMask.MASK, UrlMask.mask("not a url"))
    }
}
