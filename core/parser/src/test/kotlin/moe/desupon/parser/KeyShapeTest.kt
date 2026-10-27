package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** Key shapes the core enforces at start: wrong sizes are refused here, other base64 spellings are canonicalized. */
class KeyShapeTest {
    private fun bytes(n: Int, b: Int = 0xfb) = ByteArray(n) { b.toByte() }
    private val std = Base64.getEncoder()
    private val url = Base64.getUrlEncoder().withoutPadding()
    private fun malformed(body: String) = assertEquals(listOf(SkipReason.MALFORMED), Fx.parse(body).skipped.map { it.reason }, body)

    private fun wg(priv: String, pub: String, psk: String? = null) =
        "wireguard://$priv@wg.example.com:51820?publickey=$pub&address=10.0.0.2" + (psk?.let { "&presharedkey=$it" } ?: "")

    @Test
    fun `wireguard keys must be 32 bytes and come out as padded standard base64`() {
        val k = std.encodeToString(bytes(32))
        val j = Fx.single(wg(url.encodeToString(bytes(32)), url.encodeToString(bytes(32, 1)))).json()
        assertAt(k, j, "private_key")
        assertAt(std.encodeToString(bytes(32, 1)), j, "peers.0.public_key")
        malformed(wg(std.encodeToString(bytes(31)), k))
        malformed(wg(k, std.encodeToString(bytes(33))))
        malformed(wg(k, k, std.encodeToString(bytes(16))))
        malformed(wg("not-a-key", k))
    }

    @Test
    fun `amnezia header protection key must be 32 bytes`() {
        val k = std.encodeToString(bytes(32))
        val conf = "[Interface]\nPrivateKey = $k\nAddress = 10.0.0.2\nJc = 3\nS1 = 20\nS2 = 20\nS3 = 20\nS4 = 20\nHeaderProtectionKey = %s\n[Peer]\nPublicKey = $k\nEndpoint = 192.0.2.1:51820\n"
        val ok = Fx.single(conf.format(url.encodeToString(bytes(32, 9))))
        assertEquals(Capability.SUPPORTED, ok.capability)
        assertAt(std.encodeToString(bytes(32, 9)), ok.json(), "amnezia.header_protection_key")
        malformed(conf.format(std.encodeToString(bytes(24))))
    }

    @Test
    fun `reality public key becomes unpadded base64url of 32 bytes`() {
        fun link(pbk: String) = "vless://00000000-0000-4000-8000-000000000001@r.example.com:443?security=reality&sni=www.example.org&pbk=${java.net.URLEncoder.encode(pbk, Charsets.UTF_8)}"
        val padded = std.encodeToString(bytes(32))
        assertAt(url.encodeToString(bytes(32)), Fx.single(link(padded)).json(), "tls.reality.public_key")
        malformed(link(url.encodeToString(bytes(31))))
        val singbox = """{"type":"vless","server":"r.example.com","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001",
            "tls":{"enabled":true,"reality":{"enabled":true,"public_key":"${url.encodeToString(bytes(30))}"}}}"""
        malformed(singbox)
    }

    @Test
    fun `shadowsocks 2022 key sizes follow the method`() {
        fun ss(method: String, vararg keys: ByteArray) =
            "ss://$method:" + keys.joinToString(":") { java.net.URLEncoder.encode(std.encodeToString(it), Charsets.UTF_8) } + "@s.example.com:8388"
        assertEquals(Capability.SUPPORTED, Fx.single(ss("2022-blake3-aes-128-gcm", bytes(16))).capability)
        assertEquals(Capability.SUPPORTED, Fx.single(ss("2022-blake3-aes-128-gcm", bytes(16), bytes(16, 1))).capability)
        assertEquals(Capability.SUPPORTED, Fx.single(ss("2022-blake3-aes-256-gcm", bytes(32))).capability)
        assertEquals(Capability.SUPPORTED, Fx.single(ss("2022-blake3-chacha20-poly1305", bytes(32))).capability)
        assertEquals(Capability.UNSUPPORTED, Fx.single(ss("2022-blake3-chacha20-poly1305", bytes(32), bytes(32, 1))).capability)
        malformed(ss("2022-blake3-aes-128-gcm", bytes(32)))
        malformed(ss("2022-blake3-aes-256-gcm", bytes(16)))
        malformed(ss("2022-blake3-aes-256-gcm", bytes(32), bytes(16)))
        malformed("ss://2022-blake3-aes-256-gcm:password@s.example.com:8388")
        assertEquals(Capability.SUPPORTED, Fx.single("ss://aes-256-gcm:password@s.example.com:8388").capability)
    }

    @Test
    fun `tuic uuid must be a uuid`() {
        Fx.single("tuic://00000000-0000-4000-8000-000000000001:pw@t.example.com:443")
        Fx.single("tuic://00000000000040008000000000000001:pw@t.example.com:443")
        malformed("tuic://uuid:pw@t.example.com:443")
        malformed("tuic://00000000-0000-4000-8000-00000000000:pw@t.example.com:443")
        val yaml = "proxies:\n  - {name: t, type: tuic, server: t.example.com, port: 443, uuid: nope, password: pw}\n"
        malformed(yaml)
    }
}
