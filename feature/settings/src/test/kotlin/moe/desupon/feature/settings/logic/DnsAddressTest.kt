package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DnsAddressTest {
    @Test
    fun acceptsSupportedForms() {
        for (ok in listOf(
            "", "https://1.1.1.1/dns-query", "https://dns.example/custom", "h3://dns.example", "tls://dns.example:853",
            "quic://dns.example", "tcp://9.9.9.9", "udp://8.8.8.8:5353", "8.8.8.8", "2606:4700::1111", "https://[2606:4700::1111]/dns-query",
        )) {
            assertTrue(DnsAddress.isValid(ok), ok)
        }
    }

    @Test
    fun rejectsUnsupported() {
        for (bad in listOf(
            "ftp://dns.example", "https://", "https://user@dns.example/dns-query", "https://dns.example/dns-query?x=1",
            "tls://dns.example/path", "https://dns.example:70000/", "https://bad_host-.example/", "not a url", ":::",
        )) {
            assertFalse(DnsAddress.isValid(bad), bad)
        }
    }
}
