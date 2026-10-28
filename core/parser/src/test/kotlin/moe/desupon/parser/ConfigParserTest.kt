package moe.desupon.parser

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.parser.Fx.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ConfigParserTest {
    private val sub = Fx.SUB

    @Test
    fun `a single link reads to its node`() {
        val link = Fx.vector("husi.trojan_ws")
        val r = ConfigParser.read(link, sub)
        assertNull(r.error)
        assertEquals(Fx.single(link).id, r.nodes.single().id)
    }

    @Test
    fun `blank and unknown text are reported`() {
        assertEquals(ConfigError.Empty, ConfigParser.read("  \n ", sub).error)
        assertEquals(ConfigError.NotAConfig, ConfigParser.read("hello there", sub).error)
        assertEquals(ConfigError.UnknownScheme("anytls"), ConfigParser.read(Fx.vector("husi.anytls"), sub).error)
        assertEquals(ConfigError.NotImported("https"), ConfigParser.read("https://example.com/sub/token", sub).error)
    }

    @Test
    fun `two links are several configs, comments do not count`() {
        val two = Fx.vector("husi.trojan_ws") + "\n" + Fx.vector("husi.hysteria2")
        assertEquals(ConfigError.Several(2), ConfigParser.read(two, sub).error)
        val commented = "#profile-title: mine\n// note\n" + Fx.vector("husi.trojan_ws")
        assertNull(ConfigParser.read(commented, sub).error)
    }

    @Test
    fun `malformed links name the field and never the value`() {
        val e = assertIs<ConfigError.Malformed>(ConfigParser.read("vless://@example.com:443?security=tls#x", sub).error)
        assertEquals("vless", e.scheme)
        assertEquals("missing uuid", e.detail)

        val reality = "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality&pbk=c2VjcmV0&sni=a.example.com"
        val r = assertIs<ConfigError.Malformed>(ConfigParser.read(reality, sub).error)
        assertEquals("REALITY public key is not 32 bytes", r.detail)
        assertTrue("c2VjcmV0" !in r.detail.orEmpty())
    }

    @Test
    fun `a malformed sing-box outbound reports the normalizer reason`() {
        val e = assertIs<ConfigError.Malformed>(
            ConfigParser.read("""{"type":"tuic","server":"example.com","server_port":443,"uuid":"nope","password":"p"}""", sub).error,
        )
        assertEquals("tuic uuid is not a UUID", e.detail)
    }

    @Test
    fun `every supported vector round-trips through its outbound JSON`() {
        val failures = ArrayList<String>()
        for (v in Fx.vectors) {
            // Link-only reasons (faketcp, a pin with insecure TLS, finalmask) have no outbound JSON form.
            if (v.expect.startsWith("skip:") || v.expect.startsWith("unsupported:")) continue
            val node = Fx.single(v.link)
            val text = ConfigParser.outboundText(node)
            val back = ConfigParser.read(text, sub)
            val again = back.nodes.singleOrNull()
            when {
                again == null -> failures += "${v.id}: ${back.error}"
                again.id != node.id -> failures += "${v.id}: id changed"
                again.json() != node.json() -> failures += "${v.id}: outbound changed\n  ${node.json()}\n  ${again.json()}"
                again.displayName != node.displayName -> failures += "${v.id}: name ${node.displayName} -> ${again.displayName}"
                again.capability != node.capability -> failures += "${v.id}: capability changed"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `outbound text puts type and tag first`() {
        val node = Fx.singleOf("husi.hysteria2")
        val text = ConfigParser.outboundText(node)
        assertTrue(text.trimStart().startsWith("{\n  \"type\": \"hysteria2\",\n  \"tag\": \"test\""), text)
        val unnamed = ConfigParser.outboundText(node.json(), " ")
        assertTrue("\"tag\"" !in unnamed)
    }

    @Test
    fun `a link list splits into one config per line as written`() {
        val a = Fx.vector("husi.trojan_ws")
        val b = Fx.vector("husi.hysteria2")
        val split = ConfigParser.split("$a\n\nvless://@broken.example.com:443\n$b\n$a\n", sub)
        assertEquals(listOf(a, b), split.configs.map { it.text })
        assertEquals(1, split.duplicates)
        val skip = split.skipped.single()
        assertEquals(1, skip.index)
        assertEquals(SkipReason.MALFORMED, skip.reason)
        assertEquals("missing uuid", skip.detail)
    }

    @Test
    fun `a base64 link list is split by its decoded lines`() {
        val a = Fx.vector("husi.trojan_ws")
        val b = Fx.vector("v2ng.ss_sip002_plain")
        val split = ConfigParser.split(Fx.b64("$a\n$b"), sub)
        assertEquals(listOf(a, b), split.configs.map { it.text })
    }

    @Test
    fun `structured bodies become one outbound JSON per node`() {
        val split = ConfigParser.split(Fx.text("singbox.json"), sub)
        val whole = Fx.parse(Fx.text("singbox.json"))
        assertTrue(split.configs.isNotEmpty())
        for (c in split.configs) {
            assertTrue(c.text.trimStart().startsWith("{"), c.text)
            val back = ConfigParser.read(c.text, sub)
            assertEquals(c.nodes.map { it.id }, back.nodes.map { it.id })
        }
        val supported = whole.nodes.filter { it.capability == Capability.SUPPORTED }.map { it.id }
        assertTrue(split.configs.flatMap { c -> c.nodes.map { it.id } }.containsAll(supported))
    }

    @Test
    fun `chained nodes keep their identity when split into outbound JSON`() {
        for (fixture in listOf("chains/singbox.json", "chains/xray.json", "chains/clash.yaml")) {
            val text = Fx.text(fixture)
            val whole = Fx.parse(text)
            val split = ConfigParser.split(text, sub)
            val splitIds = split.configs.flatMap { c -> c.nodes.map { it.id } }
            val supported = whole.nodes.filter { it.capability == Capability.SUPPORTED }.map { it.id }
            assertTrue(supported.any { id -> whole.nodes.first { it.id == id }.outboundJson.raw().contains("\"chain\"") }, fixture)
            assertTrue(splitIds.containsAll(supported), "$fixture: ${supported - splitIds.toSet()}")
            assertTrue(whole.nodes.map { it.id }.containsAll(splitIds), fixture)
            for (c in split.configs) assertEquals(c.nodes.map { it.id }, ConfigParser.read(c.text, sub).nodes.map { it.id }, fixture)
        }
    }

    @Test
    fun `relays to the same exit stay distinct configs`() {
        val split = ConfigParser.split(Fx.text("chains/xray.json"), sub)
        val names = split.configs.flatMap { c -> c.nodes.map { it.displayName } }
        assertTrue(names.any { "via RU" in it } && names.any { "via KZ" in it }, names.toString())
        assertEquals(split.configs.size, split.configs.flatMap { c -> c.nodes.map { it.id } }.toSet().size)
    }

    @Test
    fun `a malformed chain is reported, not dropped silently`() {
        val e = assertIs<ConfigError.Malformed>(
            ConfigParser.read("""{"type":"trojan","server":"a.example.com","server_port":443,"password":"p","chain":[{"type":"direct"}]}""", sub).error,
        )
        assertEquals("chain hop is not a proxy", e.detail)
    }

    @Test
    fun `a gecko packet range the core refuses is unsupported`() {
        fun reason(obfs: String): String? {
            val r = ConfigParser.read("""{"type":"hysteria2","server":"a.example.com","server_port":443,"password":"p","tls":{"enabled":true},"obfs":$obfs}""", sub)
            return r.unsupportedReasons[r.nodes.single().id]
        }
        assertEquals("gecko packet size range", reason("""{"type":"gecko","password":"o","min_packet_size":1300}"""))
        assertEquals("gecko packet size range", reason("""{"type":"gecko","password":"o","max_packet_size":400}"""))
        assertEquals("gecko packet size range", reason("""{"type":"gecko","password":"o","min_packet_size":600,"max_packet_size":500}"""))
        assertNull(reason("""{"type":"gecko","password":"o","min_packet_size":300}"""))
        assertNull(reason("""{"type":"gecko","password":"o"}"""))
        assertNull(reason("""{"type":"salamander","password":"o"}"""))
        assertTrue(Fx.reasonOf("hy2://p@a.example.com:443?obfs=gecko&obfs-password=o&minpacketsize=1300#g") == "gecko packet size range")
    }

    @Test
    fun `amnezia settings the device refuses are unsupported`() {
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        val link = "awg://192.0.2.4:51820/?pk=$key&address=10.8.0.2/32&publickey=$key&jc=4"
        assertNull(Fx.reasonOf("$link&jmin=40&jmax=70&h1=100-200#ok"))
        assertEquals("AmneziaWG Jmin above Jmax", Fx.reasonOf("$link&jmin=80&jmax=70#junk"))
        assertEquals("AmneziaWG H1-H4 overlap", Fx.reasonOf("$link&h1=100-200&h2=150#overlap"))
        assertEquals("AmneziaWG I1 is not a tag chain", Fx.reasonOf("$link&i1=%3Cq%3E#chain"))

        fun reason(amnezia: String): String? {
            val r = ConfigParser.read(
                """{"type":"wireguard","address":["10.8.0.2/32"],"private_key":"$key",
                "peers":[{"address":"192.0.2.4","port":51820,"public_key":"$key","allowed_ips":["0.0.0.0/0"]}],"amnezia":$amnezia}""",
                sub,
            )
            return r.unsupportedReasons[r.nodes.single().id]
        }
        assertNull(reason("""{"h1":{"from":100,"to":200},"h2":300}"""))
        assertEquals("AmneziaWG H1-H4 overlap", reason("""{"h1":{"from":100,"to":200},"h2":"150"}"""))
        assertEquals("AmneziaWG H1 is not a range", reason("""{"h1":"100 - 200"}"""))
        assertEquals("AmneziaWG I1 is not a tag chain", reason("""{"i1":"<r 1>\n<r 2>"}"""))
    }

    @Test
    fun `outbounds the core refuses to build are unsupported`() {
        fun reason(json: String): String? = ConfigParser.read(json, sub).let { r -> r.unsupportedReasons[r.nodes.single().id] }
        assertEquals(
            "hysteria upload rate missing",
            reason("""{"type":"hysteria","server":"h.example.com","server_port":443,"down_mbps":50,"tls":{"enabled":true}}"""),
        )
        assertEquals("anytls without TLS", reason("""{"type":"anytls","server":"a.example.com","server_port":443,"password":"p"}"""))
        val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
        assertEquals(
            "peer allowed_ips missing",
            reason("""{"type":"wireguard","address":["10.0.0.2/32"],"private_key":"$key","peers":[{"address":"192.0.2.4","port":51820,"public_key":"$key"}]}"""),
        )
        val partialDashes = ConfigParser.read(
            """{"type":"tuic","server":"t.example.com","server_port":443,"uuid":"00000000-00004000-8000-00000000c0de","password":"p","tls":{"enabled":true}}""",
            sub,
        )
        assertEquals("tuic uuid is not a UUID", (partialDashes.error as ConfigError.Malformed).detail)
    }

    @Test
    fun `a node unsupported only by its source format is not turned into runnable JSON`() {
        val yaml = """
            proxies:
              - {name: ok, type: ss, server: a.example.com, port: 8388, cipher: aes-256-gcm, password: pw}
              - {name: bad, type: ss, server: b.example.com, port: 8388, cipher: aes-256-gcm, password: pw, plugin: shadow-tls, plugin-opts: {host: c.example.com}}
        """.trimIndent()
        assertEquals(Capability.UNSUPPORTED, Fx.parse(yaml).nodes.single { it.displayName == "bad" }.capability)
        val split = ConfigParser.split(yaml, sub)
        assertEquals(listOf("ok"), split.configs.map { it.nodes.single().displayName })
        assertEquals(SkipReason.UNSUPPORTED_SCHEME, split.skipped.single().reason)
    }

    @Test
    fun `an unsupported link stays a link and keeps its reason`() {
        val link = Fx.vector("husi.hysteria1_faketcp")
        val config = ConfigParser.split(link, sub).configs.single()
        assertEquals(link, config.text)
        assertTrue(config.unsupportedReasons.isNotEmpty())
    }

    @Test
    fun `clash proxies become configs`() {
        val split = ConfigParser.split(Fx.text("clash.yaml"), sub)
        assertTrue(split.configs.isNotEmpty())
        assertTrue(split.configs.all { ConfigParser.read(it.text, sub).error == null })
        assertTrue(split.configs.any { c -> c.nodes.any { it.protocol == Protocol.SHADOWSOCKS || it.protocol == Protocol.VMESS } })
    }
}
