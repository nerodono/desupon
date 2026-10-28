package moe.desupon.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.desupon.config.Fixtures.byTag
import moe.desupon.config.Fixtures.objects
import moe.desupon.config.Fixtures.strings
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.Secret
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeRulesTest {
    private val gen = ConfigGenerator()

    private fun probe(vararg nodes: Node, dns: DnsSettings = DnsSettings()): JsonObject =
        Fixtures.parse(gen.probeConfig(nodes.toList(), dns).json.raw())

    private fun one(node: Node): JsonObject = probe(node).byTag(node.id.tag)

    private fun JsonObject.str(vararg path: String): String? {
        var cur: JsonObject = this
        for (key in path.dropLast(1)) cur = cur[key]?.jsonObject ?: return null
        return cur[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `xhttp gets default padding and loses the Host header to host`() {
        val t = one(Fixtures.vlessXhttp)["transport"]!!.jsonObject
        assertEquals(NodeRules.DEFAULT_X_PADDING, t.str("x_padding_bytes"))
        assertEquals("cdn.example.com", t.str("host"))
        assertEquals(setOf("User-Agent"), t["headers"]!!.jsonObject.keys)
        assertEquals("random", one(Fixtures.vlessXhttp).str("tls", "utls", "fingerprint"))
    }

    @Test
    fun `explicit xhttp padding is kept`() {
        assertEquals("200-800", one(Fixtures.vlessXhttpH3).str("transport", "x_padding_bytes"))
    }

    @Test
    fun `utls is removed on QUIC`() {
        for (node in listOf(Fixtures.vlessXhttpH3, Fixtures.hysteria2, Fixtures.hysteria)) {
            assertNull(one(node)["tls"]!!.jsonObject["utls"], node.displayName)
        }
        assertEquals("chrome", one(Fixtures.trojanGrpc).str("tls", "utls", "fingerprint"))
    }

    @Test
    fun `reality forces chrome and mlkem, flow udp443 maps to vision`() {
        val o = one(Fixtures.vlessReality)
        assertEquals("chrome", o.str("tls", "utls", "fingerprint"))
        assertEquals("true", o.str("tls", "utls", "enabled"))
        assertEquals("true", o.str("tls", "reality", "support_x25519mlkem768"))
        assertEquals(NodeRules.VISION, o.str("flow"))
        assertEquals(Fixtures.vlessReality.id.tag, o.str("tag"))
    }

    @Test
    fun `wireguard goes to endpoints without reserved, keepalive or listen port`() {
        val root = probe(Fixtures.wireguard, Fixtures.amneziawg, Fixtures.vmessWs)
        assertEquals(
            listOf(Fixtures.wireguard.id.tag, Fixtures.amneziawg.id.tag),
            root.objects("endpoints").map { it.str("tag") },
        )
        assertEquals(listOf(Fixtures.vmessWs.id.tag), root.objects("outbounds").map { it.str("tag") })
        val wg = root.byTag(Fixtures.wireguard.id.tag)
        assertNull(wg["listen_port"])
        val peer = wg.objects("peers").single()
        assertNull(peer["reserved"])
        assertNull(peer["persistent_keepalive_interval"])
        assertEquals(Fixtures.WG_PSK, peer.str("pre_shared_key"))
    }

    @Test
    fun `amnezia block passes through one to one`() {
        val source = Fixtures.parse(Fixtures.amneziawg.outboundJson.raw())["amnezia"]
        assertEquals(source, one(Fixtures.amneziawg)["amnezia"])
    }

    @Test
    fun `amnezia with header protection and small S is rejected`() {
        val cfg = gen.probeConfig(listOf(Fixtures.awgSmallPadding, Fixtures.vmessWs))
        assertTrue(Fixtures.awgSmallPadding.id in cfg.rejected)
        assertTrue(cfg.rejected.getValue(Fixtures.awgSmallPadding.id).contains("S1-S4"))
        assertFalse(Fixtures.awgSmallPadding.id.tag in cfg.nodeTags)
    }

    private var variants = 0

    private fun variant(base: Node, from: String, to: String) = base.copy(
        id = NodeId("%016x".format(++variants) + base.id.hash),
        outboundJson = Secret(base.outboundJson.raw().replace(from, to).also { check(it.contains(to)) }),
    )

    private fun assertRejected(cases: Map<Node, String>) {
        val cfg = gen.probeConfig(cases.keys.toList() + Fixtures.vmessWs)
        for ((node, reason) in cases) assertTrue(cfg.rejected[node.id]?.contains(reason) == true, "$reason: ${cfg.rejected[node.id]}")
        assertFalse(Fixtures.vmessWs.id in cfg.rejected)
    }

    @Test
    fun `amnezia settings the device crashes on or refuses are rejected`() {
        val awg = Fixtures.amneziawg
        assertRejected(
            mapOf(
                variant(awg, "\"jmax\":159", "\"jmax\":50") to "Jmin above Jmax",
                variant(awg, "\"jc\":3", "\"jc\":500") to "Jc above 128",
                variant(awg, "\"s1\":38", "\"s1\":70000") to "size above 65535",
                variant(awg, "\"h2\":\"200000000-200999999\"", "\"h2\":\"100500000-100600000\"") to "H1-H4 overlap",
                variant(awg, "\"h2\":\"200000000-200999999\"", "\"h2\":{\"from\":100500000,\"to\":100600000}") to "H1-H4 overlap",
                variant(awg, "\"h3\":\"300000000-300999999\"", "\"h3\":\"9-1\"") to "H3 is not a range",
                variant(awg, "\"h3\":\"300000000-300999999\"", "\"h3\":\"300000000 - 300999999\"") to "H3 is not a range",
                variant(awg, "\"i1\":\"<r 256>\"", "\"i1\":\"<b 0x1>\"") to "I1 is not a tag chain",
                variant(awg, "\"i1\":\"<r 256>\"", "\"i1\":\"<r 1>\\n<r 2>\"") to "I1 is not a tag chain",
            ),
        )
    }

    @Test
    fun `nodes whose outbound the core refuses to build are rejected one by one`() {
        assertRejected(
            mapOf(
                variant(Fixtures.hysteria, "\"up_mbps\":50,", "") to "hysteria upload rate missing",
                variant(Fixtures.anytls, "\"tls\":{\"enabled\":true,", "\"tls\":{\"enabled\":false,") to "anytls without TLS",
                variant(Fixtures.wireguard, "\"allowed_ips\":[\"0.0.0.0/0\",\"::/0\"]", "\"allowed_ips\":[]") to "peer allowed_ips missing",
                variant(Fixtures.tuic, Fixtures.UUID, "00000000-00004000-8000-00000000c0de") to "tuic uuid is not a UUID",
            ),
        )
    }

    @Test
    fun `nodes with key shapes the core refuses are rejected one by one`() {
        val short = "c3ludGhldGljLXNob3J0LWtleS0wMDAwMDAwMDAwMQ=="
        var n = 0
        fun variant(base: Node, from: String, to: String) = base.copy(
            id = NodeId("%016x".format(++n) + base.id.hash),
            outboundJson = Secret(base.outboundJson.raw().replace(from, to)),
        )
        val bad = mapOf(
            variant(Fixtures.wireguard, Fixtures.WG_PRIVATE, short) to "private_key",
            variant(Fixtures.amneziawg, Fixtures.WG_PUBLIC, short) to "peer public_key",
            variant(Fixtures.amneziawg, Fixtures.WG_PSK, short) to "peer pre_shared_key",
            variant(Fixtures.amneziawg, Fixtures.AWG_HPK, short) to "header_protection_key",
            variant(Fixtures.vlessReality, Fixtures.REALITY_PUBLIC, short.trimEnd('=')) to "REALITY public_key",
            variant(Fixtures.shadowsocks, Fixtures.SS_PASSWORD, Fixtures.WG_PSK) to "shadowsocks 2022",
            variant(Fixtures.tuic, Fixtures.UUID, "uuid") to "tuic uuid",
            variant(Fixtures.juicity, Fixtures.UUID, "uuid") to "juicity uuid",
            variant(Fixtures.juicity, Fixtures.JUICITY_PIN, "sha256value") to "juicity pinned_certchain_sha256",
        )
        val cfg = gen.probeConfig(bad.keys.toList() + Fixtures.socks)
        for ((node, reason) in bad) {
            assertTrue(cfg.rejected[node.id]?.contains(reason) == true, "$reason: ${cfg.rejected[node.id]}")
        }
        assertEquals(listOf(Fixtures.socks.id.tag), cfg.nodeTags)
    }

    @Test
    fun `every node resolves through local with the configured strategy`() {
        for (strategy in DnsStrategy.entries) {
            val root = probe(*Fixtures.all.toTypedArray(), dns = DnsSettings(strategy = strategy))
            val expected = ConfigGenerator.strategyName(strategy)
            for (o in root.objects("outbounds") + root.objects("endpoints")) {
                assertEquals("local", o.str("domain_resolver", "server"))
                assertEquals(expected, o.str("domain_resolver", "strategy"))
            }
        }
        assertNull(one(Fixtures.shadowsocks)["domain_strategy"])
    }

    @Test
    fun `non-nodes, FreeTurn, unsupported and malformed nodes are rejected`() {
        val cfg = gen.probeConfig(listOf(Fixtures.freeturn, Fixtures.unsupported, Fixtures.malformed, Fixtures.socks))
        assertEquals(setOf(Fixtures.freeturn.id, Fixtures.unsupported.id, Fixtures.malformed.id), cfg.rejected.keys)
        assertEquals(listOf(Fixtures.socks.id.tag), cfg.nodeTags)
        assertFailsWith<ConfigException> { gen.probeConfig(listOf(Fixtures.freeturn)) }
    }

    @Test
    fun `probe config has nodes only`() {
        val root = probe(*Fixtures.all.toTypedArray())
        assertEquals(setOf("log", "dns", "outbounds", "endpoints", "route"), root.keys)
        assertEquals(Fixtures.all.size, root.objects("outbounds").size + root.objects("endpoints").size)
        val route = root["route"]!!.jsonObject
        assertEquals(setOf("auto_detect_interface", "default_domain_resolver"), route.keys)
        assertEquals(listOf("local"), root["dns"]!!.jsonObject.objects("servers").map { it.str("tag") })
        assertTrue(root.objects("outbounds").none { it.str("type") in setOf("selector", "urltest", "direct") })
    }

    @Test
    fun `duplicate nodes are emitted once`() {
        val cfg = gen.probeConfig(listOf(Fixtures.socks, Fixtures.socks))
        assertEquals(1, cfg.nodeTags.size)
        assertEquals(listOf(Fixtures.socks.id.tag), Fixtures.parse(cfg.json.raw()).objects("outbounds").map { it.str("tag") })
    }

    @Test
    fun `h3 leaves the ALPN of TCP transports but stays first on xhttp over QUIC`() {
        val tcp = one(Fixtures.vlessXhttp)["tls"]!!.jsonObject["alpn"]!!.strings()
        assertEquals(listOf("h2", "http/1.1"), tcp)
        val quic = one(Fixtures.vlessXhttpH3)["tls"]!!.jsonObject["alpn"]!!.strings()
        assertEquals(listOf("h3", "h2"), quic)
        assertEquals(listOf("h3"), one(Fixtures.hysteria2)["tls"]!!.jsonObject["alpn"]!!.strings())
    }

    @Test
    fun `h3 on a TCP transport is a violation`() {
        val o = Fixtures.parse(
            """{"type":"trojan","tag":"n-0000000000000001","server":"203.0.113.9","server_port":443,"password":"synthetic",
               "tls":{"enabled":true,"server_name":"t.example.com","alpn":["h2","h3"]},"domain_resolver":"local"}""",
        )
        assertTrue("h3 ALPN on a TCP transport" in NodeRules.violations(o, endpoint = false))
    }

    @Test
    fun `a gecko range the core refuses is a violation`() {
        fun gecko(sizes: String) = Fixtures.parse(
            """{"type":"hysteria2","tag":"n-0000000000000002","server":"203.0.113.9","server_port":443,"password":"synthetic",
               "obfs":{"type":"gecko","password":"o"$sizes},"tls":{"enabled":true,"server_name":"h.example.com"},"domain_resolver":"local"}""",
        )
        assertTrue("gecko packet size range" in NodeRules.violations(gecko(""","min_packet_size":1300"""), endpoint = false))
        assertTrue("gecko packet size range" in NodeRules.violations(gecko(""","max_packet_size":3000"""), endpoint = false))
        assertFalse("gecko packet size range" in NodeRules.violations(gecko(""), endpoint = false))
        assertFalse("gecko packet size range" in NodeRules.violations(gecko(""","min_packet_size":300,"max_packet_size":1400"""), endpoint = false))
    }
}
