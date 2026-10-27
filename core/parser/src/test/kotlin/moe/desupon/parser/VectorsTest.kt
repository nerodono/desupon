package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Vectors adapted from Husi fmt tests, v2rayNG `ShadowsocksFmtTest` and ray2sing tests, with synthetic hosts. */
class VectorsTest {
    @Test
    fun `every vector parses to its expected outcome`() {
        val failures = ArrayList<String>()
        for (v in Fx.vectors) {
            val r = Fx.parse(v.link)
            val got = when {
                r.nodes.size == 1 && r.nodes[0].capability == Capability.UNSUPPORTED -> "unsupported:${r.nodes[0].protocol}"
                r.nodes.size == 1 -> r.nodes[0].protocol.name
                r.nodes.isEmpty() && r.skipped.size == 1 -> "skip:${r.skipped[0].reason}"
                else -> "nodes=${r.nodes.size} skipped=${r.skipped}"
            }
            if (got != v.expect) failures += "${v.id}: expected ${v.expect}, got $got"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `husi vmess ducksoft url`() {
        val n = Fx.singleOf("husi.vmess_ducksoft")
        val j = n.json()
        assertEquals("test-vmess", n.displayName)
        assertAt("example.com", j, "server")
        assertAt(10086, j, "server_port")
        assertAt("uuid", j, "uuid")
        assertAt("auto", j, "security")
        assertAt("ws", j, "transport.type")
        assertAt("/path", j, "transport.path")
        assertAt("host.example.com", j, "transport.headers.Host")
        assertAt("sni.example.com", j, "tls.server_name")
    }

    @Test
    fun `husi vless grpc keeps flow and custom service path`() {
        val j = Fx.singleOf("husi.vless_grpc").json()
        assertAt("xtls-rprx-vision", j, "flow")
        assertAt("grpc", j, "transport.type")
        assertAt("/grpc", j, "transport.service_name")
    }

    @Test
    fun `v2rayN vmess json with numeric port and aid`() {
        val j = Fx.singleOf("husi.vmess_v2rayn_numeric").json()
        assertAt("192.0.2.140", j, "server")
        assertAt(38949, j, "server_port")
        assertAt("c7d456c6-fac6-41ae-988b-5fe46b951ec6", j, "uuid")
        assertNull(j["alter_id"])
        assertNull(j["tls"])
        assertNull(j["transport"])

        val r = Fx.singleOf("r2s.vmess_b64")
        assertAt("chacha20-poly1305", r.json(), "security")
        assertAt(8080, r.json(), "server_port")
        assertEquals("AU", r.country)
    }

    @Test
    fun `vmess with flattened xhttp fields`() {
        val j = Fx.singleOf("own.vmess_xhttp_flat").json()
        assertAt("xhttp", j, "transport.type")
        assertAt("packet-up", j, "transport.mode")
        assertAt("/vx", j, "transport.path")
        assertAt("vx.example.com", j, "transport.host")
        assertAt("300-600", j, "transport.x_padding_bytes")
    }

    @Test
    fun `trojan defaults to tls`() {
        val d = Fx.singleOf("husi.trojan_default").json()
        assertAt(true, d, "tls.enabled")
        assertNull(d.s("tls.server_name"))
        val q = Fx.singleOf("husi.trojan_query").json()
        assertAt("sni.example.com", q, "tls.server_name")
        assertAt(listOf("h2", "http/1.1"), q, "tls.alpn")
        assertAt(true, q, "tls.insecure")
        assertAt("peer.example.com", Fx.singleOf("husi.trojan_peer").json(), "tls.server_name")
        val ws = Fx.singleOf("r2s.trojan_ws").json()
        assertAt("/f1rocket", ws, "transport.path")
        assertAt("ws.example.com", ws, "transport.headers.Host")
    }

    @Test
    fun `shadowsocks forms`() {
        fun ss(id: String) = Fx.singleOf(id).json()
        assertAt("aes-256-gcm", ss("husi.ss_unpadded"), "method")
        assertAt("2022-blake3-aes-128-gcm", ss("own.ss2022_aes128"), "method")
        assertAt(Fx.b64("desupon-ss16-key"), ss("own.ss2022_aes128"), "password")
        assertAt("XKFKl2rULjIp74", ss("husi.ss_unpadded"), "password")
        assertAt("my-secret-password", ss("v2ng.ss_sip002_b64"), "password")
        assertAt("mypassword", ss("v2ng.ss_sip002_plain"), "password")
        assertAt("chacha20-ietf-poly1305", ss("v2ng.ss_sip002_chacha"), "method")
        assertAt("legacy.example.com", ss("v2ng.ss_legacy"), "server")
        assertAt("password123", ss("v2ng.ss_legacy"), "password")
        assertAt("partial.example.com", ss("v2ng.ss_legacy_partial"), "server")
        assertAt("pass:word:with:colons", ss("v2ng.ss_legacy_colons"), "password")
        assertAt("aes-256-gcm", ss("v2ng.ss_legacy_upper"), "method")
        assertAt("2001:db8::1", ss("v2ng.ss_ipv6"), "server")
        assertEquals("Legacy Server", Fx.singleOf("v2ng.ss_legacy").displayName)
        assertEquals("example.com:8388", Fx.singleOf("v2ng.ss_empty_remarks").displayName)
        assertEquals("IPv6 Server", Fx.singleOf("v2ng.ss_ipv6").displayName)
        assertEquals("RU", Fx.singleOf("r2s.ss_b64").country)
    }

    @Test
    fun `ss 2022 multi-user key in base64 and plain forms is the same node`() {
        val a = Fx.singleOf("own.ss2022_eih_b64")
        val b = Fx.singleOf("own.ss2022_eih_plain")
        assertAt(Fx.b64("synthetic-ss2022-ipsk-key-000001") + ":" + Fx.b64("synthetic-ss2022-upsk-key-000001"), a.json(), "password")
        assertEquals(a.id, b.id)
    }

    @Test
    fun `hysteria2 vectors`() {
        val h = Fx.singleOf("husi.hysteria2").json()
        assertAt("secret", h, "password")
        assertAt(true, h, "tls.insecure")
        assertAt("sni.example.com", h, "tls.server_name")
        assertNull(h["obfs"])
        assertAt("user:pass", Fx.singleOf("husi.hysteria2_userpass").json(), "password")

        val link = Fx.vector("r2s.hy2_noport_pin_insecure")
        val r = Fx.single(link).json()
        assertAt(443, r, "server_port")
        assertAt("gawrgura", r, "obfs.password")
        assertAt("real.example.com", r, "tls.server_name")
        assertTrue(Fx.reasonOf(link)!!.contains("pin"))
    }

    @Test
    fun `hysteria v1 vectors`() {
        val j = Fx.singleOf("r2s.hysteria").json()
        assertAt(100, j, "up_mbps")
        assertAt(100, j, "down_mbps")
        assertAt("123456", j, "obfs")
        assertAt("123456", j, "auth_str")
        assertAt(listOf("hysteria"), j, "tls.alpn")
        assertAt("sni.example.com", j, "tls.server_name")
        val d = Fx.singleOf("husi.hysteria1").json()
        assertAt(10, d, "up_mbps")
        assertAt(50, d, "down_mbps")
        assertNull(d["obfs"])
    }

    @Test
    fun `tuic vectors`() {
        val j = Fx.singleOf("own.tuic").json()
        assertAt("00000000-0000-4000-8000-0000000000e5", j, "uuid")
        assertAt("token", j, "password")
        assertAt("bbr", j, "congestion_control")
        assertAt("native", j, "udp_relay_mode")
        assertAt(listOf("h3"), j, "tls.alpn")
        assertAt(true, j, "tls.insecure")
        assertAt(true, j, "tls.disable_sni")
        assertAt(443, Fx.singleOf("own.tuic_hex_uuid").json(), "server_port")
        assertEquals("CA", Fx.singleOf("r2s.tuic").country)
    }

    @Test
    fun `ray2sing wg and awg query forms`() {
        val wg = Fx.singleOf("r2s.wg").json()
        assertAt(listOf("10.0.0.2/24"), wg, "address")
        assertAt("cGsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", wg, "private_key")
        assertAt("cHViAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", wg, "peers.0.public_key")
        assertAt("cHNrAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", wg, "peers.0.pre_shared_key")
        assertAt(1380, wg, "mtu")
        assertNull(wg.s("peers.0.reserved"))
        val awg = Fx.singleOf("r2s.awg")
        assertEquals(Protocol.AMNEZIAWG, awg.protocol)
        assertAt(4, awg.json(), "amnezia.jc")
        assertAt("1", awg.json(), "amnezia.h1")
    }

    @Test
    fun `vless ws early data in path`() {
        val n = Fx.singleOf("r2s.vless_ws_ed")
        val j = n.json()
        assertAt("/", j, "transport.path")
        assertAt(2048, j, "transport.max_early_data")
        assertAt("Sec-WebSocket-Protocol", j, "transport.early_data_header_name")
        assertAt("chrome", j, "tls.utls.fingerprint")
        assertEquals("US", n.country)
    }

    @Test
    fun `skips carry the scheme but no content`() {
        val r = Fx.parse(Fx.vector("own.happ_crypt"))
        assertEquals(SkippedEntry(0, "happ", SkipReason.ENCRYPTED), r.skipped.single())
        assertNotNull(Fx.parse(Fx.vector("own.freeturn")).skipped.single().scheme)
    }
}
