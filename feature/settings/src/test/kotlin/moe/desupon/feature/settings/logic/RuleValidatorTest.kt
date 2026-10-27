package moe.desupon.feature.settings.logic

import moe.desupon.model.routing.SplitRuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleValidatorTest {
    private val sets = listOf("geosite-category-ru", "geoip-ru")

    private fun valid(kind: SplitRuleKind, raw: String): String =
        assertNotNull(RuleValidator.validate(kind, raw, sets) as? RuleValidation.Valid, "expected valid: $raw").value

    private fun error(kind: SplitRuleKind, raw: String): RuleError =
        assertNotNull(RuleValidator.validate(kind, raw, sets) as? RuleValidation.Invalid, "expected invalid: $raw").error

    @Test
    fun domainsNormalize() {
        assertEquals("example.com", valid(SplitRuleKind.DOMAIN, "  Example.COM. "))
        assertEquals("xn--d1acpjx3f.xn--p1ai", valid(SplitRuleKind.DOMAIN, "яндекс.рф"))
        assertEquals("a-b.c_d.example", valid(SplitRuleKind.DOMAIN, "a-b.c_d.example"))
        assertEquals("localhost", valid(SplitRuleKind.DOMAIN, "localhost"))
    }

    @Test
    fun malformedDomainsRejected() {
        for (bad in listOf("-bad.com", "bad-.com", "a..b", "http://x.com", "x.com/path", "a b.com", "1.2.3.4", "*.example.com")) {
            assertEquals(RuleError.INVALID_DOMAIN, error(SplitRuleKind.DOMAIN, bad), bad)
        }
        assertEquals(RuleError.INVALID_DOMAIN, error(SplitRuleKind.DOMAIN, "a".repeat(64) + ".com"))
        assertEquals(RuleError.INVALID_DOMAIN, error(SplitRuleKind.DOMAIN, List(60) { "abcd" }.joinToString(".")))
        assertEquals(RuleError.EMPTY, error(SplitRuleKind.DOMAIN, "   "))
    }

    @Test
    fun suffixKeepsLeadingDot() {
        assertEquals("ru", valid(SplitRuleKind.DOMAIN_SUFFIX, "RU"))
        assertEquals(".example.com", valid(SplitRuleKind.DOMAIN_SUFFIX, ".example.com"))
        assertEquals(RuleError.INVALID_DOMAIN, error(SplitRuleKind.DOMAIN_SUFFIX, ".."))
        assertEquals(RuleError.INVALID_DOMAIN, error(SplitRuleKind.DOMAIN_SUFFIX, "."))
    }

    @Test
    fun keywords() {
        assertEquals("google", valid(SplitRuleKind.DOMAIN_KEYWORD, "Google"))
        assertEquals("cdn-1.x_y", valid(SplitRuleKind.DOMAIN_KEYWORD, "cdn-1.x_y"))
        assertEquals(RuleError.INVALID_KEYWORD, error(SplitRuleKind.DOMAIN_KEYWORD, "two words"))
        assertEquals(RuleError.INVALID_KEYWORD, error(SplitRuleKind.DOMAIN_KEYWORD, "a,b"))
    }

    @Test
    fun ipv4Cidr() {
        assertEquals("10.0.0.0/8", valid(SplitRuleKind.IP_CIDR, "10.0.0.0/8"))
        assertEquals("1.2.3.4/32", valid(SplitRuleKind.IP_CIDR, " 1.2.3.4 "))
        assertEquals("0.0.0.0/0", valid(SplitRuleKind.IP_CIDR, "0.0.0.0/0"))
        assertEquals("255.255.255.255/32", valid(SplitRuleKind.IP_CIDR, "255.255.255.255/32"))
        for (bad in listOf("256.0.0.0/8", "10.0.0/8", "10.0.0.0/33", "10.0.0.0/", "10.0.0.0/-1", "01.2.3.4", "1.2.3.4.5", "a.b.c.d", "10.0.0.0/8/8", "")) {
            assertNull(RuleValidator.normalizeCidr(bad), bad)
        }
        assertEquals(RuleError.INVALID_CIDR, error(SplitRuleKind.IP_CIDR, "10.0.0.0/33"))
    }

    @Test
    fun ipv6Cidr() {
        assertEquals("2001:db8::/32", valid(SplitRuleKind.IP_CIDR, "2001:DB8::/32"))
        assertEquals("::1/128", valid(SplitRuleKind.IP_CIDR, "::1"))
        assertEquals("::/0", valid(SplitRuleKind.IP_CIDR, "::/0"))
        assertEquals("fe80::/10", valid(SplitRuleKind.IP_CIDR, "fe80::/10"))
        assertEquals("1:2:3:4:5:6:7:8/64", valid(SplitRuleKind.IP_CIDR, "1:2:3:4:5:6:7:8/64"))
        assertEquals("::ffff:10.0.0.1/128", valid(SplitRuleKind.IP_CIDR, "::ffff:10.0.0.1"))
        assertEquals("1:2:3:4:5:6:7::/128", valid(SplitRuleKind.IP_CIDR, "1:2:3:4:5:6:7::"))
        for (bad in listOf(
            "2001:db8::/129", ":::", "1::2::3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "12345::", "g::1",
            "fe80::1%eth0", ":1::2", "1::2:", "1.2.3.4::", "::1.2.3.4:5", "1:2:3:4:5:6:7:8::",
        )) {
            assertNull(RuleValidator.normalizeCidr(bad), bad)
        }
    }

    @Test
    fun ipv6Groups() {
        assertEquals(listOf(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1), RuleValidator.parseIpv6("2001:db8::1")?.toList())
        assertEquals(listOf(0, 0, 0, 0, 0, 0xffff, 0x0a00, 0x0001), RuleValidator.parseIpv6("::ffff:10.0.0.1")?.toList())
    }

    @Test
    fun ruleSetMustBeAvailable() {
        assertEquals("geoip-ru", valid(SplitRuleKind.RULE_SET, "geoip-ru"))
        assertEquals(RuleError.UNKNOWN_RULE_SET, error(SplitRuleKind.RULE_SET, "geosite-cn"))
    }
}
