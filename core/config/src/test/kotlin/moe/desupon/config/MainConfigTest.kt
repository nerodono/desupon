package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.desupon.config.Fixtures.byTag
import moe.desupon.config.Fixtures.objects
import moe.desupon.config.Fixtures.strings
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Tier
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRuleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainConfigTest {
    private val gen = ConfigGenerator()

    private val request = MainConfigRequest(
        selection = mapOf(
            Tier.RU to listOf(Fixtures.hysteria2, Fixtures.amneziawg),
            Tier.DIRECT to listOf(
                Fixtures.vlessXhttp, Fixtures.vlessReality, Fixtures.vmessWs, Fixtures.trojanGrpc,
                Fixtures.shadowsocks, Fixtures.anytls,
            ),
            Tier.TURN to listOf(Fixtures.wireguard),
        ),
        mode = NetworkMode.OPEN,
        rules = Fixtures.rules,
        ruleSets = Fixtures.ruleSets,
    )

    private fun build(r: MainConfigRequest = request): Pair<GeneratedConfig, JsonObject> =
        gen.mainConfig(r).let { it to Fixtures.parse(it.json.raw()) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.sub(key: String): JsonObject = this[key]!!.jsonObject

    @Test
    fun `selector proxy holds every node, defaults to the first and there is no urltest`() {
        val (cfg, root) = build()
        val proxy = root.byTag("proxy")
        assertEquals("selector", proxy.str("type"))
        assertEquals("true", proxy.str("interrupt_exist_connections"))
        val direct = request.selection.getValue(Tier.DIRECT).map { it.id.tag }
        val ru = request.selection.getValue(Tier.RU).map { it.id.tag }
        val turn = request.selection.getValue(Tier.TURN).map { it.id.tag }
        assertEquals(direct + ru + turn, proxy["outbounds"]!!.strings())
        assertEquals(direct + ru + turn, cfg.nodeTags)
        assertEquals(direct.first(), proxy.str("default"))
        assertTrue(root.objects("outbounds").none { it.str("type") == "urltest" || it.str("tag") == "auto" })
    }

    @Test
    fun `selector defaults to the preferred node when it is built`() {
        val (_, root) = build(request.copy(preferred = Fixtures.hysteria2.id))
        assertEquals(Fixtures.hysteria2.id.tag, root.byTag("proxy").str("default"))
        val (_, fallback) = build(request.copy(preferred = moe.desupon.model.node.NodeId("0000000000000000")))
        assertEquals(request.selection.getValue(Tier.DIRECT).first().id.tag, fallback.byTag("proxy").str("default"))
    }

    @Test
    fun `whitelist mode puts RU first`() {
        val (cfg, root) = build(request.copy(mode = NetworkMode.WHITELIST))
        assertEquals(request.selection.getValue(Tier.RU).map { it.id.tag }, cfg.nodeTags.take(2))
        assertEquals(Fixtures.hysteria2.id.tag, root.byTag("proxy").str("default"))
    }

    @Test
    fun `only TURN nodes means a node default`() {
        val (_, root) = build(MainConfigRequest(selection = mapOf(Tier.TURN to listOf(Fixtures.wireguard)), mode = NetworkMode.OFFLINE))
        assertEquals(Fixtures.wireguard.id.tag, root.byTag("proxy").str("default"))
    }

    @Test
    fun `direct outbound exists and nothing listens`() {
        val (_, root) = build()
        assertEquals("direct", root.byTag("direct").str("type"))
        val inbound = root.objects("inbounds").single()
        assertEquals("tun", inbound.str("type"))
        assertEquals("gvisor", inbound.str("stack"))
        assertEquals(ConfigGenerator.DEFAULT_MTU.toString(), inbound.str("mtu"))
        assertEquals("true", inbound.str("auto_route"))
        assertEquals(ConfigGenerator.TUN_ADDRESS, inbound["address"]!!.strings())
        for (key in listOf("include_package", "exclude_package", "platform", "route_exclude_address")) assertNull(inbound[key], key)
    }

    @Test
    fun `no apis, debug or listeners, cache_file enabled`() {
        val (_, root) = build()
        val experimental = root.sub("experimental")
        assertEquals(setOf("cache_file"), experimental.keys)
        assertEquals("true", experimental.sub("cache_file").str("enabled"))
        assertFalse(root.json().contains("listen"))
        assertFalse(root.json().contains("clash_api"))
        assertFalse(root.json().contains("http_proxy"))
    }

    private fun JsonObject.json(): String = toString()

    @Test
    fun `route rules keep the fixed head, then user rules by order, final proxy`() {
        val (cfg, root) = build()
        val route = root.sub("route")
        val rules = route.objects("rules")
        assertEquals(JsonObject(mapOf("action" to JsonPrimitive("sniff"))), rules[0])
        assertEquals("dns", rules[1].str("protocol"))
        assertEquals("hijack-dns", rules[1].str("action"))
        assertEquals(listOf(ConfigGenerator.SELF_PACKAGE), rules[2]["package_name"]!!.strings())
        assertEquals("proxy", rules[2].str("outbound"))
        val user = rules.drop(3)
        assertEquals(
            listOf(
                "domain" to "reject",
                "domain" to "proxy",
                "domain_suffix" to "direct",
                "domain_keyword" to "direct",
                "ip_cidr" to "direct",
                "ip_cidr" to "direct",
                "rule_set" to "direct",
                "rule_set" to "direct",
            ),
            user.map { r -> r.keys.first() to (r.str("outbound") ?: r.str("action")!!) },
        )
        assertEquals(listOf("ru"), user[2]["domain_suffix"]!!.strings())
        assertEquals("reject", user[0].str("action"))
        assertNull(user[0]["outbound"])
        assertEquals("proxy", route.str("final"))
        assertEquals("true", route.str("auto_detect_interface"))
        assertEquals("local", route.sub("default_domain_resolver").str("server"))
        assertTrue(cfg.skippedRules.isEmpty())
    }

    @Test
    fun `rule-sets are local files`() {
        val (_, root) = build()
        val sets = root.sub("route").objects("rule_set")
        assertEquals(listOf("geosite-ru", "geoip-ru"), sets.map { it.str("tag") })
        for (set in sets) {
            assertEquals("local", set.str("type"))
            assertEquals("binary", set.str("format"))
            assertTrue(set.str("path")!!.endsWith(".srs"))
        }
    }

    @Test
    fun `dns has local and remote through proxy and mirrors domain rules`() {
        val (_, root) = build()
        val dns = root.sub("dns")
        val servers = dns.objects("servers")
        assertEquals(listOf("local", "remote"), servers.map { it.str("tag") })
        assertEquals("local", servers[0].str("type"))
        assertEquals("https", servers[1].str("type"))
        assertEquals("1.1.1.1", servers[1].str("server"))
        assertEquals("proxy", servers[1].str("detour"))
        assertNull(servers[1]["path"])
        assertEquals("remote", dns.str("final"))
        assertEquals("prefer_ipv4", dns.str("strategy"))
        val rules = dns.objects("rules")
        assertEquals(
            listOf("reject", "remote", "local", "local", "local"),
            rules.map { it.str("server") ?: it.str("action")!! },
        )
        assertTrue(rules.none { it.containsKey("ip_cidr") })
        assertEquals(listOf("geosite-ru"), rules.last()["rule_set"]!!.strings())
    }

    @Test
    fun `remote dns address forms`() {
        fun remote(spec: String) = Fixtures.parse(gen.mainConfig(request.copy(dns = DnsSettings(spec))).json.raw())
            .sub("dns").objects("servers")[1]
        remote("tls://dns.example.net").let {
            assertEquals("tls", it.str("type"))
            assertEquals("dns.example.net", it.str("server"))
        }
        remote("https://dns.example.net:8443/q").let {
            assertEquals("8443", it.str("server_port"))
            assertEquals("/q", it.str("path"))
        }
        remote("8.8.8.8").let { assertEquals("udp", it.str("type")) }
        remote("udp://[2001:db8::53]:5353").let {
            assertEquals("2001:db8::53", it.str("server"))
            assertEquals("5353", it.str("server_port"))
        }
        val bad = gen.mainConfig(request.copy(dns = DnsSettings("ftp://x")))
        assertEquals(1, bad.warnings.size)
        assertEquals("1.1.1.1", Fixtures.parse(bad.json.raw()).sub("dns").objects("servers")[1].str("server"))
    }

    @Test
    fun `ipv4 only applies to dns and every resolver`() {
        val (_, root) = build(request.copy(dns = DnsSettings(strategy = DnsStrategy.IPV4_ONLY)))
        assertEquals("ipv4_only", root.sub("dns").str("strategy"))
        assertEquals("ipv4_only", root.sub("route").sub("default_domain_resolver").str("strategy"))
        for (o in root.objects("outbounds").filter { it.str("tag")!!.startsWith("n-") } + root.objects("endpoints")) {
            assertEquals("ipv4_only", o.sub("domain_resolver").str("strategy"))
        }
    }

    @Test
    fun `ipv4 only leaves the IPv6 address out of the tun but keeps IPv6 LAN excludes, placeholder included`() {
        val ipv4 = request.copy(dns = DnsSettings(strategy = DnsStrategy.IPV4_ONLY), lanBypass = true)
        val tun = build(ipv4).second.objects("inbounds").single()
        assertEquals(listOf("198.19.250.1/30"), tun["address"]!!.strings())
        assertEquals(ConfigGenerator.LAN_RANGES, tun["route_exclude_address"]!!.strings())
        val placeholder = gen.placeholderConfig(lanBypass = true, ipv4Only = true).raw()
        val root = Fixtures.parse(placeholder)
        assertEquals(tun, root.objects("inbounds").single())
        assertEquals("ipv4_only", root.sub("dns").str("strategy"))
        assertEquals("ipv4_only", root.sub("route").sub("default_domain_resolver").str("strategy"))
        assertFalse("prefer_ipv4" in placeholder)
    }

    @Test
    fun `lan bypass excludes private ranges from the tun`() {
        val (_, root) = build(request.copy(lanBypass = true))
        assertEquals(ConfigGenerator.LAN_RANGES, root.objects("inbounds").single()["route_exclude_address"]!!.strings())
    }

    @Test
    fun `tun address stays outside the bypassed ranges`() {
        val v4 = ConfigGenerator.TUN_ADDRESS.first().substringBefore('/')
        assertFalse(v4.startsWith("10.") || v4.startsWith("192.168.") || v4.startsWith("169.254."))
        assertFalse(v4.split('.')[0] == "172" && v4.split('.')[1].toInt() in 16..31)
    }

    @Test
    fun `invalid split rules are skipped, not fatal`() {
        val rules = listOf(
            Fixtures.rule(1, SplitRuleKind.IP_CIDR, "300.1.1.1/8", SplitAction.DIRECT),
            Fixtures.rule(2, SplitRuleKind.IP_CIDR, "example.com", SplitAction.DIRECT),
            Fixtures.rule(3, SplitRuleKind.RULE_SET, "missing-set", SplitAction.DIRECT),
            Fixtures.rule(4, SplitRuleKind.DOMAIN, "bad domain", SplitAction.DIRECT),
            Fixtures.rule(5, SplitRuleKind.IP_CIDR, "192.0.2.1", SplitAction.BLOCK),
        )
        val (cfg, root) = build(request.copy(rules = rules))
        assertEquals(listOf(1L, 2L, 3L, 4L), cfg.skippedRules.map { it.rule.id })
        val user = root.sub("route").objects("rules").drop(3)
        assertEquals(listOf("192.0.2.1/32"), user.single()["ip_cidr"]!!.strings())
        assertNull(root.sub("route")["rule_set"])
    }

    @Test
    fun `mtu outside 1280-1500 is refused`() {
        assertFailsWith<ConfigException> { gen.mainConfig(request.copy(mtu = 9000)) }
        assertFailsWith<ConfigException> { gen.mainConfig(request.copy(mtu = 1279)) }
    }

    @Test
    fun `empty selection is refused`() {
        assertFailsWith<ConfigException> { gen.mainConfig(MainConfigRequest(selection = emptyMap(), mode = NetworkMode.OPEN)) }
    }

    @Test
    fun `endpoints carry WG and AWG only`() {
        val (_, root) = build()
        assertEquals(
            setOf(Fixtures.amneziawg.id.tag, Fixtures.wireguard.id.tag),
            root.objects("endpoints").map { it.str("tag") }.toSet(),
        )
        assertTrue(root.objects("outbounds").none { it.str("type") == "wireguard" })
        assertTrue((root["endpoints"] as JsonArray).isNotEmpty())
    }

    @Test
    fun `node outbound JSON keeps type first and tag second`() {
        val (_, root) = build()
        val node = root.byTag(Fixtures.vmessWs.id.tag)
        assertEquals(listOf("type", "tag"), node.keys.take(2))
        assertEquals("domain_resolver", node.keys.last())
        assertEquals(Fixtures.vmessWs.id.tag, node["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `own package of the running build heads the rules`() {
        val (_, root) = build(request.copy(selfPackage = "moe.desupon.app.debug"))
        assertEquals(listOf("moe.desupon.app.debug"), root.sub("route").objects("rules")[2]["package_name"]!!.strings())
        assertFailsWith<ConfigException> { gen.mainConfig(request.copy(selfPackage = "not a package")) }
    }

    @Test
    fun `placeholder is a valid MAIN config that rejects everything`() {
        val json = gen.placeholderConfig(lanBypass = false).raw()
        assertEquals(emptyList(), gen.validate(json, ConfigKind.MAIN))
        val root = Fixtures.parse(json)
        assertEquals("block", root.sub("route").str("final"))
        assertNull(root.sub("route")["rules"])
        assertEquals(listOf("block"), root.objects("outbounds").map { it.str("type") })
        assertEquals("tun", root.objects("inbounds").single().str("type"))
        assertNull(root.objects("inbounds").single()["route_exclude_address"])
        assertEquals(ConfigGenerator.LAN_RANGES, Fixtures.parse(gen.placeholderConfig(lanBypass = true).raw()).objects("inbounds").single()["route_exclude_address"]!!.strings())
    }

    @Test
    fun `a block-final config with nodes is not a placeholder`() {
        val root = Fixtures.parse(gen.placeholderConfig().raw()).toMutableMap()
        val node = Fixtures.parse(gen.probeConfig(listOf(Fixtures.shadowsocks)).json.raw()).objects("outbounds").single()
        root["outbounds"] = JsonArray(root["outbounds"]!!.let { it as JsonArray } + node)
        val violations = gen.validate(JsonObject(root).toString(), ConfigKind.MAIN)
        assertTrue(violations.any { it.startsWith("route.rules must start") }, violations.toString())
    }
}
