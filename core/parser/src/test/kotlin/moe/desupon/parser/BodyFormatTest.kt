package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyFormatTest {
    @Test
    fun `sing-box config keeps proxies, drops groups, applies core fixes`() {
        val r = Fx.parse(Fx.text("singbox.json"))
        assertEquals(BodyFormat.SING_BOX_JSON, r.format)
        assertEquals(
            listOf("🇩🇪 reality-de", "hy2-us", "xhttp-h3 LTE", "legacy-wg", "ss-chain", "🇯🇵 awg-jp"),
            r.nodes.map { it.displayName },
        )
        assertEquals(
            listOf(SkippedEntry(7, "naive", SkipReason.UNKNOWN_SCHEME), SkippedEntry(9, null, SkipReason.MALFORMED)),
            r.skipped,
        )
        val byName = r.nodes.associateBy { it.displayName }

        val reality = byName.getValue("🇩🇪 reality-de").json()
        assertNull(reality["tag"])
        assertAt("xtls-rprx-vision", reality, "flow")
        assertAt("chrome", reality, "tls.utls.fingerprint")
        assertAt(true, reality, "tls.reality.support_x25519mlkem768")
        assertEquals("DE", byName.getValue("🇩🇪 reality-de").country)

        assertNull(byName.getValue("hy2-us").json().s("tls.utls"))

        val xh = byName.getValue("xhttp-h3 LTE")
        assertTrue(xh.udpOnly)
        assertEquals(setOf(NodeLabels.LTE), xh.labels)
        val xj = xh.json()
        assertNull(xj.s("tls.utls"))
        assertAt("cdn.example.com", xj, "transport.host")
        assertAt("1", xj, "transport.headers.X-Test")
        assertNull(xj.s("transport.headers.Host"))
        assertAt("100-1000", xj, "transport.x_padding_bytes")
        assertNull(xj.s("transport.download"))

        val wg = byName.getValue("legacy-wg")
        assertEquals(Protocol.WIREGUARD, wg.protocol)
        assertTrue(wg.isEndpoint)
        assertAt("192.0.2.10", wg.json(), "peers.0.address")
        assertAt(51820, wg.json(), "peers.0.port")
        assertNull(wg.json()["reserved"])

        val chain = byName.getValue("ss-chain")
        assertEquals(Capability.UNSUPPORTED, chain.capability)
        assertTrue(r.unsupportedReasons.getValue(chain.id).contains("detour"))

        val awg = byName.getValue("🇯🇵 awg-jp")
        assertEquals(Protocol.AMNEZIAWG, awg.protocol)
        assertEquals(Capability.UNSUPPORTED, awg.capability)
        assertNull(awg.json().s("peers.0.reserved"))
    }

    @Test
    fun `xray json array takes the proxy outbound of each config`() {
        val r = Fx.parse(Fx.text("xray.json"))
        assertEquals(BodyFormat.XRAY_JSON, r.format)
        assertEquals(
            listOf("🇳🇱 Reality NL", "vmess ws", "trojan grpc", "xhttp via extra", "chained", "hysteria xray"),
            r.nodes.map { it.displayName },
        )
        assertEquals(listOf(SkippedEntry(6, null, SkipReason.MALFORMED)), r.skipped)
        val (reality, vmess, trojan, xhttp, chained) = r.nodes
        r.nodes[5].json().let {
            assertAt("hysteria2", it, "type")
            assertAt("gecko", it, "obfs.type")
            assertAt(1400, it, "obfs.max_packet_size")
        }
        reality.json().let {
            assertAt("c3ludGhldGljLXhyYXktcmVhbGl0eS1rZXktMDAwMDE", it, "tls.reality.public_key")
            assertAt("a1b2", it, "tls.reality.short_id")
            assertAt("chrome", it, "tls.utls.fingerprint")
            assertAt("xtls-rprx-vision", it, "flow")
            assertNull(it["encryption"])
        }
        vmess.json().let {
            assertAt("/ws", it, "transport.path")
            assertAt(2048, it, "transport.max_early_data")
            assertAt("vm.example.com", it, "transport.headers.Host")
            assertAt(listOf("http/1.1"), it, "tls.alpn")
        }
        assertAt("tunnel", trojan.json(), "transport.service_name")
        xhttp.json().let {
            assertAt("packet-up", it, "transport.mode")
            assertAt("/p", it, "transport.path")
            assertAt("200-400", it, "transport.x_padding_bytes")
            assertAt(true, it, "transport.no_grpc_header")
            assertAt("16-32", it, "transport.xmux.max_concurrency")
            assertAt(30, it, "transport.xmux.h_keep_alive_period")
            assertAt(1000000, it, "transport.sc_max_each_post_bytes")
        }
        assertEquals(Capability.SUPPORTED, chained.capability)
        assertAt(true, chained.json(), "tls.record_fragment")
        assertNull(chained.json()["chain"])
    }

    @Test
    fun `sip008`() {
        val r = Fx.parse(Fx.text("sip008.json"))
        assertEquals(BodyFormat.SIP008, r.format)
        assertEquals(2, r.nodes.size)
        assertEquals(listOf(SkippedEntry(2, "ss", SkipReason.MALFORMED)), r.skipped)
        val sg = r.nodes[0].json()
        assertAt("obfs-local", sg, "plugin")
        assertAt("obfs=http;obfs-host=www.example.org", sg, "plugin_opts")
        assertEquals("SG", r.nodes[0].country)
        assertAt("2001:db8::5", r.nodes[1].json(), "server")
    }

    @Test
    fun `clash yaml proxies`() {
        val r = Fx.parse(Fx.text("clash.yaml"))
        assertEquals(BodyFormat.CLASH_YAML, r.format)
        assertEquals(
            listOf(Protocol.SHADOWSOCKS, Protocol.VMESS, Protocol.VLESS, Protocol.TROJAN, Protocol.HYSTERIA2, Protocol.TUIC, Protocol.AMNEZIAWG),
            r.nodes.map { it.protocol },
        )
        assertEquals(listOf(SkipReason.UNKNOWN_SCHEME, SkipReason.MALFORMED), r.skipped.map { it.reason })
        assertEquals("snell", r.skipped[0].scheme)
        val (ss, vmess, vless, trojan, hy2) = r.nodes
        val (tuic, wg) = r.nodes.drop(5)
        assertAt("obfs-local", ss.json(), "plugin")
        assertAt("obfs=tls;obfs-host=www.example.org", ss.json(), "plugin_opts")
        assertEquals("US", ss.country)
        vmess.json().let {
            assertAt(true, it, "tls.enabled")
            assertAt("vm2.example.com", it, "tls.server_name")
            assertAt("/ray", it, "transport.path")
            assertAt(2048, it, "transport.max_early_data")
            assertAt("vm2.example.com", it, "transport.headers.Host")
        }
        vless.json().let {
            assertAt("chrome", it, "tls.utls.fingerprint")
            assertAt("0a1b", it, "tls.reality.short_id")
        }
        assertAt("svc", trojan.json(), "transport.service_name")
        hy2.json().let {
            assertAt(listOf("20000:30000", "40000:40000"), it, "server_ports")
            assertAt(50, it, "up_mbps")
            assertAt(200, it, "down_mbps")
            assertAt("salamander", it, "obfs.type")
        }
        assertEquals(setOf(NodeLabels.ONLY_MOBILE), hy2.labels)
        assertAt("bbr", tuic.json(), "congestion_control")
        wg.json().let {
            assertAt(listOf("10.10.0.2/32", "fd00::2/128"), it, "address")
            assertAt(4, it, "amnezia.jc")
            assertAt("11111", it, "amnezia.h1")
            assertAt(1408, it, "mtu")
            assertNull(it.s("peers.0.reserved"))
        }
    }

    @Test
    fun `mihomo hysteria2 gecko keeps packet sizes`() {
        val yaml = """
            |proxies:
            |  - name: gecko
            |    type: hysteria2
            |    server: hy2.example.com
            |    port: 443
            |    password: synthetic-auth
            |    obfs: gecko
            |    obfs-password: synthetic-obfs
            |    obfs-min-packet-size: 512
            |    obfs-max-packet-size: 1400
            |    sni: hy2.example.com
        """.trimMargin()
        val j = Fx.parse(yaml).nodes.single().json()
        assertAt("gecko", j, "obfs.type")
        assertAt("synthetic-obfs", j, "obfs.password")
        assertAt(512, j, "obfs.min_packet_size")
        assertAt(1400, j, "obfs.max_packet_size")
    }

    @Test
    fun `xray json hysteria takes auth from hysteriaSettings and gecko from finalmask`() {
        val config = """
            |[{"remarks":"hy2","outbounds":[{"tag":"proxy","protocol":"hysteria",
            |  "settings":{"version":2,"address":"hy2.example.com","port":443},
            |  "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"synthetic-auth"},
            |    "security":"tls","tlsSettings":{"serverName":"hy2.example.com","alpn":["h3"],"fingerprint":"chrome"},
            |    "finalmask":{"udp":[{"type":"salamander","settings":{"password":"synthetic-obfs","packetSize":"512-1400"}},
            |      {"type":"udphop","settings":{"remotePorts":"20000-30000","interval":"15-45"}}],
            |      "quicParams":{"brutalUp":"20 mbps","congestion":"bbr"}}}},
            |  {"tag":"direct","protocol":"freedom"}]}]
        """.trimMargin()
        val r = Fx.parse(config)
        assertEquals(BodyFormat.XRAY_JSON, r.format)
        val n = r.nodes.single()
        assertEquals(Protocol.HYSTERIA2, n.protocol)
        assertEquals(Capability.SUPPORTED, n.capability)
        n.json().let {
            assertAt("hysteria2", it, "type")
            assertAt("synthetic-auth", it, "password")
            assertAt("gecko", it, "obfs.type")
            assertAt(512, it, "obfs.min_packet_size")
            assertAt(1400, it, "obfs.max_packet_size")
            assertAt(listOf("20000:30000"), it, "server_ports")
            assertAt("15s", it, "hop_interval")
            assertAt("45s", it, "hop_interval_max")
            assertAt(20, it, "up_mbps")
            assertAt("hy2.example.com", it, "tls.server_name")
            assertAt(listOf("h3"), it, "tls.alpn")
        }
        val tcp = config.replace("\"network\":\"hysteria\"", "\"network\":\"tcp\"")
        assertEquals(Capability.UNSUPPORTED, Fx.parse(tcp).nodes.single().capability)
    }

    @Test
    fun `plain list tolerates bom, crlf, comments and junk lines`() {
        val body = "﻿# comment\r\n\r\n" + Fx.vector("husi.trojan_default") + "\r\nnot a link\r\n" + Fx.vector("own.ss2022_aes128") + "\r\n"
        val r = Fx.parse(body)
        assertEquals(BodyFormat.LINK_LIST, r.format)
        assertEquals(2, r.nodes.size)
        assertEquals(listOf(SkippedEntry(1, null, SkipReason.MALFORMED)), r.skipped)
        assertEquals("aes128", r.nodes[1].displayName)
        val bytes = SubscriptionParser.parse(body.toByteArray(), Fx.SUB)
        assertEquals(r.nodes.map { it.id }, bytes.nodes.map { it.id })
    }

    @Test
    fun `base64 wraps any format but only one level deep`() {
        val links = Fx.vector("husi.trojan_default") + "\n"
        val once = Fx.parse(Fx.b64(links))
        assertTrue(once.base64)
        assertEquals(1, once.nodes.size)
        val twice = Fx.parse(Fx.b64(Fx.b64(links)))
        assertEquals(BodyFormat.UNKNOWN, twice.format)
        assertEquals(0, twice.nodes.size)
        val json = Fx.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(Fx.text("singbox.json").toByteArray()))
        assertEquals(BodyFormat.SING_BOX_JSON, json.format)
        assertTrue(json.base64)
        assertEquals(BodyFormat.CLASH_YAML, Fx.parse(Fx.b64(Fx.text("clash.yaml"))).format)
    }

    @Test
    fun `empty, garbage and broken bodies never throw`() {
        assertEquals(BodyFormat.EMPTY, Fx.parse("").format)
        assertEquals(BodyFormat.EMPTY, Fx.parse(" \r\n\t").format)
        for (b in listOf("hello world", "<!DOCTYPE html><html><body>sub</body></html>", "{\"outbounds\": [", "[1,2,3]", "{}", "proxies: [", "====", "\u0000\u0001")) {
            val r = Fx.parse(b)
            assertEquals(0, r.nodes.size, b)
        }
        val truncated = Fx.text("xui/links.txt").lines().map { it.take(it.length / 2) }.joinToString("\n")
        Fx.parse(truncated)
        for (v in Fx.vectors) for (cut in listOf(10, 20, 40)) Fx.parse(v.link.take(cut))
    }

    @Test
    fun `wg-quick conf body and single outbound object`() {
        val conf = Fx.parse(Fx.text("xui/awg_nl.conf"))
        assertEquals(BodyFormat.WG_CONF, conf.format)
        assertEquals("🇳🇱 Amnezia", conf.nodes.single().displayName)
        val one = Fx.parse("""{"type":"trojan","tag":"t1","server":"t.example.com","server_port":443,"password":"p","tls":{"enabled":true}}""")
        assertEquals(BodyFormat.SING_BOX_JSON, one.format)
        assertEquals("t1", one.nodes.single().displayName)
        assertFalse(one.nodes.single().udpOnly)
    }
}
