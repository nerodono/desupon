package moe.desupon.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainTest {
    private fun Node.chain(): List<JsonObject> = (json()[Node.CHAIN_KEY] as? JsonArray)?.map { it as JsonObject }.orEmpty()

    private fun ParsedSubscription.byName(): Map<String, Node> = nodes.associateBy { it.displayName }

    private fun ParsedSubscription.reason(name: String): String? = unsupportedReasons[byName().getValue(name).id]

    @Test
    fun `xray dialerProxy to a proxy outbound becomes a chain hop`() {
        val r = Fx.parse(Fx.text("chains/xray.json"))
        val n = r.byName()
        val ru = n.getValue("🇩🇪 relay via RU")
        assertEquals(Capability.SUPPORTED, ru.capability)
        val hop = ru.chain().single()
        assertAt("vless", hop, "type")
        assertAt("ru-entry.example.com", hop, "server")
        assertAt(true, hop, "tls.reality.support_x25519mlkem768")
        assertNull(hop["tag"])
        assertNull(hop["detour"])
        assertNull(ru.json()["detour"])
        assertEquals(Capability.SUPPORTED, n.getValue("🇩🇪 relay via KZ").capability)
        assertAt("/relay", n.getValue("🇩🇪 relay via KZ").chain().single(), "transport.path")
    }

    @Test
    fun `node ids cover the chain`() {
        val n = Fx.parse(Fx.text("chains/xray.json")).byName()
        val ru = n.getValue("🇩🇪 relay via RU")
        val kz = n.getValue("🇩🇪 relay via KZ")
        assertNotEquals(ru.id, kz.id)
        val direct = Fx.parse(Fx.text("chains/xray.json").replace("\"sockopt\": {\"dialerProxy\": \"ru-relay\"}", "\"sockopt\": {}"))
            .byName().getValue("🇩🇪 relay via RU")
        assertTrue(direct.chain().isEmpty())
        assertNotEquals(direct.id, ru.id)
    }

    @Test
    fun `freedom fragment maps to the node's TLS ClientHello splitting`() {
        val r = Fx.parse(Fx.text("chains/xray.json"))
        val n = r.byName()
        n.getValue("🇫🇮 trojan fragment").let {
            assertEquals(Capability.SUPPORTED, it.capability)
            assertTrue(it.chain().isEmpty())
            assertAt(true, it.json(), "tls.record_fragment")
            assertAt(true, it.json(), "tls.fragment")
        }
        n.getValue("🇵🇱 trojan tlshello").json().let {
            assertAt(true, it, "tls.record_fragment")
            assertNull(it.s("tls.fragment"))
        }
        n.getValue("🇫🇮 vless ws segments").json().let {
            assertAt(true, it, "tls.fragment")
            assertNull(it.s("tls.record_fragment"))
        }
        n.getValue("🇳🇱 reality fragment").let {
            assertEquals(Capability.SUPPORTED, it.capability)
            assertAt(true, it.json(), "tls.reality.enabled")
            assertAt(true, it.json(), "tls.record_fragment")
            assertNull(it.json().s("tls.fragment"))
        }
        val plain = n.getValue("plain freedom")
        assertEquals(Capability.SUPPORTED, plain.capability)
        assertTrue(plain.chain().isEmpty())
        assertNull(plain.json().s("tls.fragment"))
    }

    @Test
    fun `fragmented and plain variants of one server stay apart`() {
        val body = Fx.text("chains/xray.json")
        val fragmented = Fx.parse(body).byName().getValue("🇫🇮 trojan fragment")
        val plain = Fx.parse(body.replace("\"dialerProxy\": \"fragment\", ", "")).byName().getValue("🇫🇮 trojan fragment")
        assertNull(plain.json().s("tls.record_fragment"))
        assertNotEquals(fragmented.id, plain.id)
    }

    @Test
    fun `three hops nest, a fourth is unsupported, loops and missing targets are malformed`() {
        val r = Fx.parse(Fx.text("chains/xray.json"))
        val hops = r.byName().getValue("🇺🇸 three hops").chain()
        assertEquals(listOf("shadowsocks", "trojan", "vless"), hops.map { it.s("type") })
        assertAt(true, hops.last(), "tls.record_fragment")
        assertEquals("dialer proxy chain longer than 3", r.reason("too deep"))
        assertEquals(
            listOf(
                SkippedEntry(9, "trojan", SkipReason.MALFORMED, "dialer proxy loop"),
                SkippedEntry(10, "trojan", SkipReason.MALFORMED, "dialer proxy target missing"),
            ),
            r.skipped,
        )
    }

    @Test
    fun `wireguard hop stays an endpoint object`() {
        val node = Fx.parse(Fx.text("chains/xray.json")).byName().getValue("🇸🇪 via wireguard")
        assertEquals(Capability.SUPPORTED, node.capability)
        val wg = node.chain().single()
        assertAt("wireguard", wg, "type")
        assertAt("wg-entry.example.com", wg, "peers.0.address")
    }

    @Test
    fun `a hop with a bad key rejects the node, an unsupported hop marks it`() {
        val body = Fx.text("chains/xray.json")
        val badKey = Fx.parse(body.replace("c3ludGhldGljLXJlbGF5LXJlYWxpdHkta2V5LTAwMDE", "c2hvcnQ"))
        assertTrue(badKey.nodes.none { it.displayName == "🇩🇪 relay via RU" })
        assertTrue(SkippedEntry(0, "vless", SkipReason.MALFORMED, "REALITY public key is not 32 bytes") in badKey.skipped, "${badKey.skipped}")

        val badFlow = Fx.parse(body.replace("\"id\": \"00000000-0000-4000-8000-0000000000e2\", \"flow\": \"xtls-rprx-vision\"", "\"id\": \"00000000-0000-4000-8000-0000000000e2\", \"flow\": \"xtls-rprx-direct\""))
        assertEquals("dialer proxy: flow xtls-rprx-direct", badFlow.reason("🇩🇪 relay via RU"))
    }

    @Test
    fun `xray UDP nodes cannot take freedom noises`() {
        val body = """
            [{"remarks": "wg noise", "outbounds": [
              {"tag": "proxy", "protocol": "wireguard", "settings": {"secretKey": "c3ludGhldGljLWNoYWluLXdnLXByaXZhdGUta2V5MDE=", "address": ["10.0.0.2/32"],
                "peers": [{"endpoint": "wg.example.com:51820", "publicKey": "c3ludGhldGljLWNoYWluLXdnLXB1YmxpYy1rZXktMDE="}]},
                "streamSettings": {"sockopt": {"dialerProxy": "noise"}}},
              {"tag": "noise", "protocol": "freedom", "settings": {"noises": [{"type": "rand", "packet": "10-20"}]}}]}]
        """
        assertEquals("xray UDP noises", Fx.reasonOf(body))
    }

    @Test
    fun `mihomo dialer-proxy resolves proxies by name`() {
        val r = Fx.parse(Fx.text("chains/clash.yaml"))
        val n = r.byName()
        val de = n.getValue("🇩🇪 DE via RU")
        assertEquals(Capability.SUPPORTED, de.capability)
        assertAt("ru-entry.example.com", de.chain().single(), "server")
        assertEquals(listOf("trojan", "vless"), n.getValue("🇸🇪 SE two hops").chain().map { it.s("type") })
        assertEquals("dialer proxy through a proxy group", r.reason("🇳🇱 NL via group"))
        assertTrue(n.getValue("🇫🇮 FI direct dialer").chain().isEmpty())
        assertEquals(Capability.SUPPORTED, n.getValue("ru-entry").capability)
        assertEquals(listOf(SkippedEntry(5, "trojan", SkipReason.MALFORMED, "dialer proxy target missing")), r.skipped)
    }

    @Test
    fun `sing-box detour resolves outbounds and endpoints by tag`() {
        val r = Fx.parse(Fx.text("chains/singbox.json"))
        val n = r.byName()
        val de = n.getValue("🇩🇪 de-exit")
        assertEquals(Capability.SUPPORTED, de.capability)
        assertNull(de.json()["detour"])
        assertAt("ru-entry.example.com", de.chain().single(), "server")
        assertTrue(n.getValue("🇫🇮 fi-direct").chain().isEmpty())
        assertNull(n.getValue("🇫🇮 fi-direct").json()["detour"])
        assertAt("wireguard", n.getValue("🇸🇪 se-via-wg").chain().single(), "type")
        assertAt("trojan", n.getValue("🇯🇵 wg-via-ru").chain().single(), "type")
        assertEquals("detour through an outbound group", r.reason("🇳🇱 nl-via-group"))
        assertEquals(
            listOf(SkippedEntry(7, "trojan", SkipReason.MALFORMED, "dialer proxy loop"), SkippedEntry(8, "trojan", SkipReason.MALFORMED, "dialer proxy loop")),
            r.skipped,
        )
    }

    @Test
    fun `a lone sing-box outbound with a detour stays unsupported`() {
        val reason = Fx.reasonOf("""{"type": "trojan", "server": "x.example.com", "server_port": 443, "password": "p", "detour": "elsewhere"}""")
        assertEquals("detour chain", reason)
    }
}
