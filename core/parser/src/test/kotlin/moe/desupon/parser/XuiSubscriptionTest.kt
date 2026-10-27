package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.at
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic subscription shaped like the owner's 3x-ui v3 response (r_subscription §1-2). */
class XuiSubscriptionTest {
    private val sub = SubscriptionParser.parse(
        Fx.text("xui/body.b64"),
        Fx.SUB,
        Fx.headers("xui/headers.txt"),
        sourceHost = "sub.example.com",
    )
    private val priv = Fx.b64("desupon-synthetic-wg-private-k01")

    @Test
    fun `body is base64 link list with eight supported nodes`() {
        assertEquals(BodyFormat.LINK_LIST, sub.format)
        assertTrue(sub.base64)
        assertEquals(emptyList(), sub.skipped)
        assertEquals(0, sub.duplicates)
        assertEquals(
            listOf(
                Protocol.VLESS, Protocol.VLESS,
                Protocol.HYSTERIA2, Protocol.HYSTERIA2, Protocol.HYSTERIA2,
                Protocol.AMNEZIAWG, Protocol.AMNEZIAWG,
                Protocol.WIREGUARD,
            ),
            sub.nodes.map { it.protocol },
        )
        assertTrue(sub.nodes.all { it.capability == Capability.SUPPORTED }, sub.unsupportedReasons.toString())
        assertEquals(8, sub.nodes.map { it.id }.toSet().size)
    }

    @Test
    fun `names come from fragments and the conf comment, countries from flags`() {
        assertEquals(
            listOf(
                "🇳🇱 VLESS-synthetic-user", "🇫🇮 VLESS",
                "🇳🇱 Hysteria", "🇫🇮 Hysteria", "🇷🇺 Hysteria",
                "🇳🇱 Amnezia", "🇫🇮 Amnezia",
                "FreeTurn",
            ),
            sub.nodes.map { it.displayName },
        )
        assertEquals(listOf("NL", "FI", "NL", "FI", "RU", "NL", "FI", null), sub.nodes.map { it.country })
    }

    @Test
    fun `endpoint and udp flags`() {
        assertEquals(listOf(false, false, false, false, false, true, true, true), sub.nodes.map { it.isEndpoint })
        assertEquals(listOf(false, false, true, true, true, true, true, true), sub.nodes.map { it.udpOnly })
    }

    @Test
    fun `headers give interval and unlimited traffic, title falls back to host`() {
        val m = sub.meta
        assertEquals(6, m.updateIntervalH)
        val ui = m.userInfo!!
        assertEquals(7811530, ui.upload)
        assertEquals(291126033, ui.download)
        assertFalse(ui.hasTrafficLimit)
        assertFalse(ui.hasExpiry)
        assertEquals("sub.example.com", m.title)
        assertNull(m.supportUrl)
        assertEquals(emptyList(), m.altEndpoints)
    }

    @Test
    fun `vless xhttp maps to extended transport`() {
        val j = sub.nodes[0].json()
        assertAt("vless", j, "type")
        assertAt("nl.example.com", j, "server")
        assertAt(443, j, "server_port")
        assertAt("00000000-0000-4000-8000-0000000000a1", j, "uuid")
        assertNull(j["tag"])
        assertNull(j["flow"])
        assertNull(j["encryption"])
        assertAt(true, j, "tls.enabled")
        assertAt("nl.example.com", j, "tls.server_name")
        assertAt(listOf("h2", "http/1.1", "h3"), j, "tls.alpn")
        assertAt("random", j, "tls.utls.fingerprint")
        assertAt("xhttp", j, "transport.type")
        assertAt("stream-one", j, "transport.mode")
        assertAt("/api/v2", j, "transport.path")
        assertAt("100-1000", j, "transport.x_padding_bytes")
        assertNull(j.s("transport.host"))
        assertTrue(j.s("transport.headers.User-Agent")!!.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
        assertNull(j.s("transport.download"))
    }

    @Test
    fun `hysteria2 drops utls and narrows alpn`() {
        val j = sub.nodes[2].json()
        assertAt("hysteria2", j, "type")
        assertAt("synthetic-hy2-auth", j, "password")
        assertAt("salamander", j, "obfs.type")
        assertAt("obfs-nl-synthetic", j, "obfs.password")
        assertAt("nl.example.com", j, "tls.server_name")
        assertAt(listOf("h3"), j, "tls.alpn")
        assertNull(j.s("tls.utls"))
        assertAt("obfs-ru-synthetic", sub.nodes[4].json(), "obfs.password")
    }

    @Test
    fun `amneziawg 3 conf maps every key to the amnezia block`() {
        val j = sub.nodes[5].json()
        assertAt("wireguard", j, "type")
        assertAt(1399, j, "mtu")
        assertAt(listOf("10.8.1.30/32"), j, "address")
        assertAt(priv, j, "private_key")
        assertAt("nl.example.com", j, "peers.0.address")
        assertAt(10481, j, "peers.0.port")
        assertAt(Fx.b64("desupon-synthetic-awg-pubkey-nl1"), j, "peers.0.public_key")
        assertAt(Fx.b64("desupon-synthetic-awg-presharedk"), j, "peers.0.pre_shared_key")
        assertAt(listOf("0.0.0.0/0", "::/0"), j, "peers.0.allowed_ips")
        assertAt(25, j, "peers.0.persistent_keepalive_interval")
        val expected = mapOf(
            "jc" to "3", "jmin" to "87", "jmax" to "159", "s1" to "38", "s2" to "22", "s3" to "23", "s4" to "21",
            "h1" to "1020304050-1020304999", "h2" to "2030405060-2030405999",
            "h3" to "3040506070-3040506999", "h4" to "4050607080-4050607999",
            "i1" to "<r 256>",
            "header_protection_key" to Fx.b64("desupon-synthetic-awg-hpk-key-01"),
            "content_padding_addition" to "0-16",
            "rekey_after_time" to "110-130", "rekey_timeout" to "4-6", "reject_after_time" to "170-190",
            "keepalive_timeout" to "9-11", "max_handshake_attempts" to "18-22",
            "random_trailers" to "true", "disable_cookies" to "true",
        )
        expected.forEach { (k, v) -> assertAt(v, j, "amnezia.$k") }
        assertEquals(expected.keys, (j.at("amnezia") as JsonObject).keys)
        assertNull(j["dns"])

        val fi = sub.nodes[6].json()
        assertAt(1406, fi, "mtu")
        assertAt("5", fi, "amnezia.rekey_timeout")
        assertAt("<r 72>", fi, "amnezia.i1")
        assertAt(51042, fi, "peers.0.port")
    }

    @Test
    fun `plain wireguard link has no amnezia block`() {
        val n = sub.nodes[7]
        val j = n.json()
        assertNull(j["amnezia"])
        assertAt(1420, j, "mtu")
        assertAt(priv, j, "private_key")
        assertAt(51307, j, "peers.0.port")
        assertAt(Fx.b64("desupon-synthetic-wg0-pubkey-nl2"), j, "peers.0.public_key")
        assertAt(25, j, "peers.0.persistent_keepalive_interval")
    }

    @Test
    fun `plain and re-encoded bodies give the same node ids`() {
        val plain = Fx.parse(Fx.text("xui/links.txt"))
        assertEquals(BodyFormat.LINK_LIST, plain.format)
        assertFalse(plain.base64)
        val raw = Fx.text("xui/links.txt").toByteArray()
        val urlSafeWrapped = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(raw)
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val variants = listOf(plain, Fx.parse(urlSafeWrapped), Fx.parse(Base64.getEncoder().withoutPadding().encodeToString(raw)))
        for (v in variants) assertEquals(sub.nodes.map { it.id }, v.nodes.map { it.id })
    }

    @Test
    fun `secrets never render through toString`() {
        val s = sub.nodes.joinToString { it.toString() }
        assertFalse("00000000-0000-4000-8000-0000000000a1" in s)
        assertFalse(priv in s)
        assertFalse("synthetic-hy2-auth" in s)
    }
}
