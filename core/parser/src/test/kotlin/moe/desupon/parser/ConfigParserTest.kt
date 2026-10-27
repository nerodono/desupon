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
        val supported = whole.nodes.filter { it.capability == Capability.SUPPORTED && "chain" !in it.outboundJson.raw() }.map { it.id }
        assertTrue(split.configs.flatMap { c -> c.nodes.map { it.id } }.containsAll(supported))
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
