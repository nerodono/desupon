package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.at
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkDetailTest {
    private val uuid = "00000000-0000-4000-8000-0000000000f1"
    private fun vless(q: String, host: String = "v.example.com") = "vless://$uuid@$host:443?$q#n"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun key(b: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { b.toByte() })
    private val pbk = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })

    @Test
    fun `reality forces chrome and ML-KEM, vision udp443 becomes vision`() {
        val j = Fx.single(vless("security=reality&sni=www.example.org&fp=firefox&pbk=$pbk&sid=ab12&spx=%2F&flow=xtls-rprx-vision-udp443")).json()
        assertAt("xtls-rprx-vision", j, "flow")
        assertAt("chrome", j, "tls.utls.fingerprint")
        assertAt(true, j, "tls.reality.enabled")
        assertAt(pbk, j, "tls.reality.public_key")
        assertAt("ab12", j, "tls.reality.short_id")
        assertAt(true, j, "tls.reality.support_x25519mlkem768")
        assertAt("www.example.org", j, "tls.server_name")
    }

    @Test
    fun `unsupported vless features are flagged, not dropped`() {
        val cases = mapOf(
            "security=reality&pbk=$pbk&pqv=abc" to "pqv",
            "security=tls&encryption=aes-x" to "encryption",
            "security=tls&type=tcp&headerType=http" to "HTTP header",
            "security=tls&type=quic" to "quic",
            "security=tls&type=xhttp&mode=packet-up&extra=" + enc("""{"downloadSettings":{"address":"d.example.com"}}""") to "downloadSettings",
            "security=tls&flow=xtls-rprx-direct" to "flow",
            "security=tls&type=xhttp&mode=bogus" to "mode",
        )
        for ((q, why) in cases) {
            val r = Fx.parse(vless(q))
            val n = r.nodes.single()
            assertEquals(Capability.UNSUPPORTED, n.capability, q)
            assertTrue(r.unsupportedReasons[n.id]!!.contains(why, ignoreCase = true), "$q -> ${r.unsupportedReasons[n.id]}")
        }
    }

    @Test
    fun `vless encryption mlkem is supported`() {
        val e = "mlkem768x25519plus.native.0rtt.c3ludGhldGljLWtleQ"
        val n = Fx.single(vless("security=none&encryption=$e&flow=xtls-rprx-vision"))
        assertEquals(Capability.SUPPORTED, n.capability)
        assertAt(e, n.json(), "encryption")
        assertNull(n.json()["tls"])
    }

    @Test
    fun `transports map to sing-box names`() {
        val kcp = Fx.single(vless("type=kcp&headerType=srtp&seed=s33d")).json()
        assertAt("mkcp", kcp, "transport.type")
        assertAt("srtp", kcp, "transport.header_type")
        assertAt("s33d", kcp, "transport.seed")
        val hu = Fx.single(vless("type=httpupgrade&host=h.example.com&path=%2Fup")).json()
        assertAt("httpupgrade", hu, "transport.type")
        assertAt("h.example.com", hu, "transport.host")
        assertAt("/up", hu, "transport.path")
        val h2 = Fx.single(vless("security=tls&type=h2&host=a.example.com,b.example.com&path=%2Fh2")).json()
        assertAt("http", h2, "transport.type")
        assertAt(listOf("a.example.com", "b.example.com"), h2, "transport.host")
        val ws = Fx.single(vless("type=ws&path=%2Fws%3Fed%3D1024%26eh%3DX-Early%26k%3Dv")).json()
        assertAt("/ws?k=v", ws, "transport.path")
        assertAt(1024, ws, "transport.max_early_data")
        assertAt("X-Early", ws, "transport.early_data_header_name")
        val split = Fx.single(vless("type=splithttp&path=%2Fs")).json()
        assertAt("xhttp", split, "transport.type")
        assertAt("auto", split, "transport.mode")
        assertAt("100-1000", split, "transport.x_padding_bytes")
    }

    @Test
    fun `xhttp extra maps xray keys and strips the Host header`() {
        val extra = """{"headers":{"Host":"front.example.com","X-A":"1"},"xPaddingBytes":{"from":10,"to":20},
            |"noGRPCHeader":true,"scMaxEachPostBytes":"500000-1000000","xmux":{"maxConcurrency":"8-16","cMaxReuseTimes":0,"hKeepAlivePeriod":15},
            |"sessionIDPlacement":"header","uplinkHTTPMethod":"PUT","downloadSettings":{"address":"x"}}""".trimMargin()
        val j = Fx.single(vless("security=tls&alpn=h3&type=xhttp&mode=stream-one&path=%2Fx&extra=${enc(extra)}")).json()
        assertAt("front.example.com", j, "transport.host")
        assertAt("1", j, "transport.headers.X-A")
        assertNull(j.s("transport.headers.Host"))
        assertAt("10-20", j, "transport.x_padding_bytes")
        assertAt(true, j, "transport.no_grpc_header")
        assertAt("500000-1000000", j, "transport.sc_max_each_post_bytes")
        assertAt("8-16", j, "transport.xmux.max_concurrency")
        assertAt(0, j, "transport.xmux.c_max_reuse_times")
        assertAt(15, j, "transport.xmux.h_keep_alive_period")
        assertAt("header", j, "transport.session_placement")
        assertAt("PUT", j, "transport.uplink_http_method")
        assertNull(j["download"])
        assertNull(j.s("tls.utls"))
    }

    @Test
    fun `xhttp over h3 is udp only and has no utls`() {
        val n = Fx.single(vless("security=tls&fp=chrome&alpn=h3,h2&type=xhttp"))
        assertTrue(n.udpOnly)
        assertNull(n.json().s("tls.utls"))
        val h2 = Fx.single(vless("security=tls&fp=chrome&alpn=h2,h3&type=xhttp"))
        assertFalse(h2.udpOnly)
        assertAt("chrome", h2.json(), "tls.utls.fingerprint")
    }

    @Test
    fun `fingerprints and ech`() {
        assertAt("randomized", Fx.single(vless("security=tls&fp=randomizednoalpn")).json(), "tls.utls.fingerprint")
        assertAt("chrome", Fx.single(vless("security=tls&fp=weird")).json(), "tls.utls.fingerprint")
        assertNull(Fx.single(vless("security=tls&fp=none")).json().s("tls.utls"))
        val ech = Fx.single(vless("security=tls&ech=AAj%2BDQAEAAAAAA%3D%3D")).json()
        assertAt(true, ech, "tls.ech.enabled")
        assertAt(listOf("-----BEGIN ECH CONFIGS-----", "AAj+DQAEAAAAAA==", "-----END ECH CONFIGS-----"), ech, "tls.ech.config")
        val dns = Fx.single(vless("security=tls&ech=udp%3A%2F%2F1.1.1.1")).json()
        assertAt(true, dns, "tls.ech.enabled")
        assertNull(dns.s("tls.ech.config"))
    }

    @Test
    fun `ipv6 host and fragment without happ description`() {
        val n = Fx.single("vless://$uuid@[2001:db8::7]:8443?security=tls#%F0%9F%87%A9%F0%9F%87%AA%20Node?serverDescription=c3ViDQ==")
        assertAt("2001:db8::7", n.json(), "server")
        assertEquals("🇩🇪 Node", n.displayName)
        assertEquals("DE", n.country)
    }

    @Test
    fun `hysteria2 ports, hop interval, gecko and alias`() {
        val j = Fx.single("hy2://pw@h.example.com:443,20000-30000?mport=40000-41000&hopInterval=30&obfs=gecko&obfs-password=o&minPacketSize=100&maxPacketSize=1200&upmbps=20").json()
        assertAt("hysteria2", j, "type")
        assertAt(443, j, "server_port")
        assertAt(listOf("443:443", "20000:30000", "40000:41000"), j, "server_ports")
        assertAt("30s", j, "hop_interval")
        assertAt("gecko", j, "obfs.type")
        assertAt(100, j, "obfs.min_packet_size")
        assertAt(1200, j, "obfs.max_packet_size")
        assertAt(20, j, "up_mbps")
        val pinned = Fx.single("hysteria2://pw@h.example.com:443?pinSHA256=ab&sni=h.example.com")
        assertEquals(Capability.SUPPORTED, pinned.capability)
        val r = Fx.parse("hysteria2://pw@h.example.com:443?obfs=xor&obfs-password=x")
        assertEquals(Capability.UNSUPPORTED, r.nodes.single().capability)
        assertEquals(SkipReason.MALFORMED, Fx.parse("hysteria2://pw@h.example.com:443?obfs=salamander").skipped.single().reason)
    }

    @Test
    fun `hysteria2 xray finalmask carries gecko, hopping and brutal rates`() {
        val gecko = Fx.singleOf("own.hy2_fm_gecko").json()
        assertAt("gecko", gecko, "obfs.type")
        assertAt("synthetic-obfs", gecko, "obfs.password")
        assertAt(512, gecko, "obfs.min_packet_size")
        assertAt(1400, gecko, "obfs.max_packet_size")
        assertAt(listOf("20000:30000"), gecko, "server_ports")
        assertAt("10s", gecko, "hop_interval")
        assertAt("30s", gecko, "hop_interval_max")
        assertAt(50, gecko, "up_mbps")
        assertAt(200, gecko, "down_mbps")
        assertAt("aggressive", gecko, "bbr_profile")
        assertAt("hy2.example.com", gecko, "tls.server_name")

        val hop = Fx.singleOf("own.hy2_fm_udphop").json()
        assertAt("salamander", hop, "obfs.type")
        assertNull(hop.at("obfs.min_packet_size"))
        assertAt(listOf("40000:41000"), hop, "server_ports")
        assertAt("20s", hop, "hop_interval")
        assertNull(hop["hop_interval_max"])

        assertEquals("xray finalmask noise", Fx.reasonOf(Fx.vector("own.hy2_fm_noise")))
        val badSize = "hysteria2://pw@h.example.com:443?fm=" + enc("""{"udp":[{"type":"salamander","settings":{"password":"o","packetSize":"0-4096"}}]}""")
        assertEquals("gecko packet size out of range", Fx.reasonOf(badSize))
        assertEquals(SkipReason.MALFORMED, Fx.parse("hysteria2://pw@h.example.com:443?fm=" + enc("not json")).skipped.single().reason)
    }

    @Test
    fun `hysteria2 gecko link keeps packet sizes`() {
        val j = Fx.singleOf("own.hy2_gecko").json()
        assertAt("gecko", j, "obfs.type")
        assertAt(512, j, "obfs.min_packet_size")
        assertAt(1400, j, "obfs.max_packet_size")
        val salamander = Fx.single("hysteria2://pw@h.example.com:443?obfs=salamander&obfs-password=o&minPacketSize=512").json()
        assertNull(salamander.at("obfs.min_packet_size"))
    }

    @Test
    fun `juicity pin replaces insecure TLS`() {
        val pinned = Fx.singleOf("own.juicity_pinned")
        assertEquals(Protocol.JUICITY, pinned.protocol)
        assertTrue(pinned.udpOnly)
        pinned.json().let {
            assertAt("juicity", it, "type")
            assertAt(23182, it, "server_port")
            assertAt("00000000-0000-4000-8000-0000000000a1", it, "uuid")
            assertAt("synthetic-juicity", it, "password")
            assertAt("bbr", it, "congestion_control")
            assertAt("c3ludGhldGljLWp1aWNpdHktY2hhaW4taGFzaC0wMDE=", it, "pinned_certchain_sha256")
            assertAt("juicity.example.com", it, "tls.server_name")
            assertNull(it.at("tls.insecure"))
        }
        Fx.singleOf("own.juicity_insecure").json().let {
            assertAt(443, it, "server_port")
            assertAt(true, it, "tls.insecure")
            assertNull(it["pinned_certchain_sha256"])
        }
        assertEquals("juicity congestion control brutal", Fx.reasonOf(Fx.vector("own.juicity_bad_cc")))
        val husi = """{"type":"juicity","tag":"j","server":"juicity.example.com","server_port":443,
            |"uuid":"00000000-0000-4000-8000-0000000000a4","password":"p","pin_cert_sha256":"c3ludGhldGljLWp1aWNpdHktY2hhaW4taGFzaC0wMDE=",
            |"tls":{"enabled":true,"server_name":"juicity.example.com","insecure":true}}""".trimMargin()
        Fx.single(husi).json().let {
            assertAt("c3ludGhldGljLWp1aWNpdHktY2hhaW4taGFzaC0wMDE=", it, "pinned_certchain_sha256")
            assertNull(it["pin_cert_sha256"])
        }
    }

    @Test
    fun `wireguard link keeps literal plus in keys`() {
        val (priv, pub, psk) = listOf(key(0xfb), key(0xfa), key(0xf9))
        assertTrue('+' in priv && '/' in priv)
        val j = Fx.single("wireguard://$priv@[2001:db8::9]:51820?publickey=$pub&presharedkey=${enc(psk)}&address=10.0.0.2,fd00::2&reserved=1,2,3&jc=3&s1=20").json()
        assertAt(priv, j, "private_key")
        assertAt(pub, j, "peers.0.public_key")
        assertAt(psk, j, "peers.0.pre_shared_key")
        assertAt("2001:db8::9", j, "peers.0.address")
        assertAt(listOf("10.0.0.2/32", "fd00::2/128"), j, "address")
        assertAt(3, j, "amnezia.jc")
        assertNull(j.s("peers.0.reserved"))
    }

    @Test
    fun `vpn raw conf edge cases`() {
        fun vpn(conf: String) = "vpn://" + Fx.b64url(conf.toByteArray())
        val plain = """
            [Interface]
            PrivateKey = ${key(1)}
            Address = 10.1.0.2
            Jc = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            [Peer]
            PublicKey = ${key(2)}
            Endpoint = [2001:db8::10]:51820
        """.trimIndent()
        val n = Fx.single(vpn(plain))
        assertEquals(Protocol.WIREGUARD, n.protocol)
        assertEquals("[2001:db8::10]:51820", n.displayName)
        assertAt(listOf("10.1.0.2/32"), n.json(), "address")
        assertNull(n.json()["amnezia"])

        val weakHpk = plain.replace("Jc = 0", "Jc = 2\nS1 = 20\nS2 = 20\nS3 = 20\nS4 = 8\nHeaderProtectionKey = ${key(3)}")
        val r = Fx.parse(vpn(weakHpk))
        assertEquals(Capability.UNSUPPORTED, r.nodes.single().capability)
        assertTrue(r.unsupportedReasons.values.single().contains("S1-S4"))

        val noEndpoint = plain.replace("Endpoint = [2001:db8::10]:51820", "")
        assertEquals(SkipReason.MALFORMED, Fx.parse(vpn(noEndpoint)).skipped.single().reason)
    }

    @Test
    fun `vpn amnezia qCompress profile yields one node per wg container`() {
        val json = Fx.text("amnezia_profile.json").toByteArray()
        for (link in listOf("vpn://" + Fx.b64url(Fx.qCompress(json)), "vpn://" + Fx.b64url(json))) {
            val r = Fx.parse(link)
            assertEquals(emptyList(), r.skipped)
            assertEquals(2, r.nodes.size)
            val awg = r.nodes[0]
            assertEquals(Protocol.AMNEZIAWG, awg.protocol)
            assertEquals("🇩🇪 Synthetic Amnezia", awg.displayName)
            assertEquals("DE", awg.country)
            val j = awg.json()
            assertAt(1280, j, "mtu")
            assertAt("203.0.113.10", j, "peers.0.address")
            assertAt(43210, j, "peers.0.port")
            assertAt(116, j, "amnezia.s1")
            assertAt("1239197098", j, "amnezia.h1")
            val wg = r.nodes[1]
            assertEquals(Protocol.WIREGUARD, wg.protocol)
            assertEquals("🇩🇪 Synthetic Amnezia amnezia-wireguard", wg.displayName)
            assertAt("203.0.113.10", wg.json(), "peers.0.address")
            assertAt(51820, wg.json(), "peers.0.port")
        }
    }

    @Test
    fun `vpn profile without wireguard containers is not a node`() {
        val json = """{"containers":[{"container":"amnezia-openvpn","openvpn":{"last_config":"{}"}}],"hostName":"203.0.113.1"}"""
        val r = Fx.parse("vpn://" + Fx.b64url(Fx.qCompress(json.toByteArray())))
        assertEquals(SkipReason.UNSUPPORTED_SCHEME, r.skipped.single().reason)
    }
}
