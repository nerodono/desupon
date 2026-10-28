package moe.desupon.feature.subscriptions.configs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.ConfigParser
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ConfigFormTest {
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val realityKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val ss16 = Base64.getEncoder().encodeToString(ByteArray(16) { 7 })
    private val wgPrivate = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val wgPublic = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val wgPsk = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val pin = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
    private val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }

    /** Synthetic links on reserved example hosts, one per protocol variant the form shows. */
    private val links = mapOf(
        "vless reality vision" to "vless://$uuid@r.example.com:443?security=reality&pbk=$realityKey&sid=abcd&sni=www.example.org&fp=firefox&flow=xtls-rprx-vision#R",
        "vless ws tls" to "vless://$uuid@w.example.com:443?type=ws&security=tls&sni=w.example.com&host=cdn.example.com&path=%2Fws%3Fed%3D2048&alpn=h2,http/1.1#WS",
        "vless grpc" to "vless://$uuid@g.example.com:443?type=grpc&security=tls&serviceName=svc&sni=g.example.com",
        "vless xhttp" to "vless://$uuid@x.example.com:443?type=xhttp&security=tls&sni=x.example.com&path=%2Fx&mode=packet-up#X",
        "vless httpupgrade none" to "vless://$uuid@h.example.com:80?type=httpupgrade&host=h.example.com&path=%2Fup",
        "vmess ws tls" to "vmess://" + Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"VM","add":"m.example.com","port":"443","id":"$uuid","aid":"2","scy":"chacha20-poly1305","net":"ws","host":"m.example.com","path":"/vm","tls":"tls","sni":"m.example.com"}""".toByteArray(),
        ),
        "vmess h2" to "vmess://$uuid@h2.example.com:443?type=http&security=tls&host=a.example.com,b.example.com&path=%2Fh2#H2",
        "trojan default" to "trojan://secret@t.example.com:443?sni=t.example.com#T",
        "trojan ws insecure" to "trojan://secret@tw.example.com:443?type=ws&path=%2Ft&allowInsecure=1#TW",
        "ss 2022" to "ss://2022-blake3-aes-128-gcm:${java.net.URLEncoder.encode(ss16, "UTF-8")}@s.example.com:8388#SS",
        "ss obfs" to "ss://YWVzLTI1Ni1nY206cHc@o.example.com:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.org#OB",
        "hy2 salamander hop" to "hysteria2://pw@hy.example.com:443?obfs=salamander&obfs-password=op&sni=hy.example.com&mport=20000-30000&hopinterval=60&upmbps=50&downmbps=200#HY",
        "hy2 gecko" to "hysteria2://pw@gk.example.com:8443?obfs=gecko&obfs-password=op&minpacketsize=600&maxpacketsize=1200&insecure=1&alpn=h3#GK",
        "tuic" to "tuic://$uuid:pw@tu.example.com:443?congestion_control=bbr&udp_relay_mode=quic&reduce_rtt=1&sni=tu.example.com&alpn=h3#TU",
        "wireguard" to "wireguard://${enc(wgPrivate)}@wg.example.com:51820?publickey=${enc(wgPublic)}&presharedkey=${enc(wgPsk)}" +
            "&address=10.0.0.2/32,fd00::2/128&mtu=1380#WG",
        "amneziawg" to "awg://198.51.100.7:51820?pk=${enc(wgPrivate)}&publickey=${enc(wgPublic)}&address=10.8.0.2/32&jc=4&jmin=40&jmax=70" +
            "&s1=15&s2=20&s3=5&s4=6&h1=100000-200000&h2=300000&h3=400000&h4=500000-600000&i1=${enc("<b 0xaabb><r 16>")}#AWG",
        "hysteria v1" to "hysteria://h1.example.com:443?auth=secret&peer=h1.example.com&insecure=1&upmbps=20&downmbps=100&obfsParam=xp&mport=20000-30000#H1",
        "juicity pinned" to "juicity://$uuid:pw@j.example.com:443?congestion_control=bbr&sni=j.example.com&pinned_certchain_sha256=${enc(pin)}#J",
        "juicity insecure" to "juicity://$uuid:pw@ji.example.com:443?sni=ji.example.com&allow_insecure=1#JI",
        "anytls" to """{"type":"anytls","tag":"A","server":"a.example.com","server_port":443,"password":"pw","idle_session_timeout":"30s",
            "tls":{"enabled":true,"server_name":"a.example.com","utls":{"enabled":true,"fingerprint":"chrome"}}}""",
        "socks" to """{"type":"socks","tag":"S5","server":"s5.example.com","server_port":1080,"version":"5","username":"u","password":"p"}""",
        "http tls" to """{"type":"http","tag":"HP","server":"hp.example.com","server_port":443,"username":"u","password":"p",
            "tls":{"enabled":true,"server_name":"hp.example.com"}}""",
    )

    private fun node(text: String): Node {
        val read = ConfigParser.read(text, SubId(0))
        return read.nodes.singleOrNull() ?: fail("no single node from $text: ${read.error}")
    }

    private fun Node.json(): JsonObject = Json.parseToJsonElement(outboundJson.raw()).jsonObject

    private fun formOf(text: String): ConfigForm = ConfigForm.from(ConfigParser.read(text, SubId(0))) ?: fail("form refused $text")

    @Test
    fun `every form protocol round-trips without changes`() {
        val failures = ArrayList<String>()
        for ((label, link) in links) {
            val original = node(link)
            assertEquals(Capability.SUPPORTED, original.capability, label)
            val form = formOf(link)
            val back = node(form.toText())
            when {
                back.id != original.id -> failures += "$label: id changed"
                back.json() != original.json() -> failures += "$label: outbound changed\n  ${original.json()}\n  ${back.json()}"
                back.displayName != original.displayName -> failures += "$label: name ${original.displayName} -> ${back.displayName}"
            }
            assertEquals(emptyMap(), form.validate().errors, label)
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `unknown keys survive an edit`() {
        val json = """{"type":"vless","tag":"K","server":"k.example.com","server_port":443,"uuid":"$uuid","packet_encoding":"xudp",
            "tls":{"enabled":true,"server_name":"k.example.com","ech":{"enabled":true},"min_version":"1.3"},
            "transport":{"type":"ws","path":"/k","max_early_data":2048,"early_data_header_name":"Sec-WebSocket-Protocol"}}"""
        val form = formOf(json).set(Field.SERVER, "k2.example.com").set(Field.PATH, "/k2")
        val o = form.toOutbound()
        assertEquals("k2.example.com", o.s("server"))
        assertEquals("xudp", o.s("packet_encoding"))
        val tls = o["tls"]!!.jsonObject
        assertEquals("1.3", tls.s("min_version"))
        assertEquals(JsonObject(mapOf("enabled" to kotlinx.serialization.json.JsonPrimitive(true))), tls["ech"])
        val transport = o["transport"]!!.jsonObject
        assertEquals("/k2", transport.s("path"))
        assertEquals("2048", transport.s("max_early_data"))
        assertEquals("K", form.name)
    }

    @Test
    fun `switching transport drops the old transport's keys`() {
        val form = formOf(links.getValue("vless ws tls")).set(Field.TRANSPORT, "grpc").set(Field.SERVICE_NAME, "s")
        val transport = form.toOutbound()["transport"]!!.jsonObject
        assertEquals(setOf("type", "service_name"), transport.keys)
        assertEquals(null, formOf(links.getValue("vless ws tls")).set(Field.TRANSPORT, ConfigForm.TCP).toOutbound()["transport"])
    }

    @Test
    fun `security choices rewrite tls`() {
        val reality = formOf(links.getValue("vless reality vision"))
        assertTrue(Field.FINGERPRINT !in reality.fields(), "REALITY always presents chrome")
        val tls = reality.set(Field.SECURITY, ConfigForm.TLS).set(Field.FINGERPRINT, "firefox").toOutbound()["tls"]!!.jsonObject
        assertNull(tls["reality"])
        assertEquals("firefox", tls["utls"]!!.jsonObject.s("fingerprint"))
        assertNull(reality.set(Field.SECURITY, ConfigForm.NONE).set(Field.FLOW, "").toOutbound()["tls"])
        val insecure = formOf(links.getValue("trojan ws insecure"))
        assertTrue(insecure.isOn(Field.INSECURE))
        val toReality = insecure.set(Field.SECURITY, ConfigForm.REALITY).set(Field.REALITY_KEY, realityKey).set(Field.SNI, "a.example.org")
        val r = toReality.toOutbound()["tls"]!!.jsonObject
        assertNull(r["insecure"], "REALITY verifies the server itself")
        assertEquals("chrome", r["utls"]!!.jsonObject.s("fingerprint"))
    }

    @Test
    fun `hop ports read and write in both notations`() {
        assertEquals(listOf("20000:30000", "443:443"), Ports.ranges("20000-30000, 443"))
        assertEquals(listOf("1:2"), Ports.ranges("1:2"))
        assertEquals(emptyList(), Ports.ranges("  "))
        assertNull(Ports.ranges("30000-20000"))
        assertNull(Ports.ranges("abc"))
        assertNull(Ports.ranges("70000"))
        assertEquals("20000-30000, 443", Ports.show(listOf("20000:30000", "443:443")))
        val hy = formOf(links.getValue("hy2 salamander hop"))
        assertEquals("20000-30000", hy[Field.HOP_PORTS])
        assertEquals("60s", hy[Field.HOP_INTERVAL])
        val cleared = hy.set(Field.HOP_PORTS, "").toOutbound()
        assertNull(cleared["server_ports"])
    }

    @Test
    fun `every form protocol has a round-trip case`() {
        val covered = links.values.mapNotNull { ConfigForm.from(ConfigParser.read(it, SubId(0)))?.protocol }.toSet()
        assertEquals(FormProtocol.entries.toSet(), covered)
    }

    @Test
    fun `blank forms ask for what each protocol needs`() {
        for (p in FormProtocol.entries) {
            val errors = ConfigForm.blank(p).validate().errors
            assertEquals(UiText.Res(R.string.subs_form_error_required), errors[Field.SERVER], "$p")
            when (p) {
                FormProtocol.SOCKS, FormProtocol.HTTP, FormProtocol.HYSTERIA -> assertEquals(setOf(Field.SERVER), errors.keys, "$p")
                FormProtocol.WIREGUARD, FormProtocol.AMNEZIAWG ->
                    assertEquals(setOf(Field.SERVER, Field.PRIVATE_KEY, Field.ADDRESS, Field.PUBLIC_KEY), errors.keys, "$p")
                else -> assertTrue(Field.UUID in errors || Field.PASSWORD in errors, "$p: $errors")
            }
        }
    }

    @Test
    fun `a filled blank form reads back as one runnable node`() {
        val filled = mapOf(
            FormProtocol.VLESS to ConfigForm.blank(FormProtocol.VLESS)
                .set(Field.SERVER, "v.example.com").set(Field.UUID, uuid).set(Field.SNI, "www.example.org").set(Field.REALITY_KEY, realityKey),
            FormProtocol.VMESS to ConfigForm.blank(FormProtocol.VMESS).set(Field.SERVER, "m.example.com").set(Field.UUID, uuid),
            FormProtocol.TROJAN to ConfigForm.blank(FormProtocol.TROJAN).set(Field.SERVER, "t.example.com").set(Field.PASSWORD, "pw"),
            FormProtocol.SHADOWSOCKS to ConfigForm.blank(FormProtocol.SHADOWSOCKS).set(Field.SERVER, "s.example.com").set(Field.PASSWORD, ss16),
            FormProtocol.HYSTERIA2 to ConfigForm.blank(FormProtocol.HYSTERIA2).set(Field.SERVER, "h.example.com").set(Field.PASSWORD, "pw"),
            FormProtocol.TUIC to ConfigForm.blank(FormProtocol.TUIC).set(Field.SERVER, "u.example.com").set(Field.UUID, uuid).set(Field.PASSWORD, "pw"),
            FormProtocol.AMNEZIAWG to ConfigForm.blank(FormProtocol.AMNEZIAWG).set(Field.SERVER, "198.51.100.8")
                .set(Field.PRIVATE_KEY, wgPrivate).set(Field.PUBLIC_KEY, wgPublic).set(Field.ADDRESS, "10.8.0.2").set(Field.H1, "100-200"),
            FormProtocol.WIREGUARD to ConfigForm.blank(FormProtocol.WIREGUARD).set(Field.SERVER, "wg.example.com")
                .set(Field.PRIVATE_KEY, wgPrivate).set(Field.PUBLIC_KEY, wgPublic).set(Field.ADDRESS, "10.0.0.2/32, fd00::2"),
            FormProtocol.HYSTERIA to ConfigForm.blank(FormProtocol.HYSTERIA).set(Field.SERVER, "h1.example.com"),
            FormProtocol.JUICITY to ConfigForm.blank(FormProtocol.JUICITY).set(Field.SERVER, "j.example.com").set(Field.UUID, uuid).set(Field.PASSWORD, "pw"),
            FormProtocol.ANYTLS to ConfigForm.blank(FormProtocol.ANYTLS).set(Field.SERVER, "a.example.com").set(Field.PASSWORD, "pw"),
            FormProtocol.SOCKS to ConfigForm.blank(FormProtocol.SOCKS).set(Field.SERVER, "s.example.com"),
            FormProtocol.HTTP to ConfigForm.blank(FormProtocol.HTTP).set(Field.SERVER, "p.example.com").set(Field.USERNAME, "u").set(Field.PASSWORD, "p"),
        )
        assertEquals(FormProtocol.entries.toSet(), filled.keys)
        for ((p, form) in filled) {
            assertEquals(emptyMap(), form.validate().errors, "$p")
            val n = node(form.name("Мой").toText())
            assertEquals(Capability.SUPPORTED, n.capability, "$p")
            assertEquals("Мой", n.displayName)
            assertEquals(p, ConfigForm.from(ConfigParser.read(form.toText(), SubId(0)))?.protocol, "$p reopens in its own form")
            val draft = ConfigDraft.new().copy(mode = ConfigMode.FORM, form = form)
            assertNull(draft.validate().error, "$p")
        }
    }

    @Test
    fun `field rules`() {
        fun err(form: ConfigForm, field: Field) = form.validate().errors[field]
        val vless = ConfigForm.blank(FormProtocol.VLESS).set(Field.SERVER, "v.example.com").set(Field.SNI, "a.example.org")
        assertEquals(UiText.Res(R.string.subs_form_error_required), err(vless.set(Field.UUID, " "), Field.UUID))
        assertNull(err(vless.set(Field.UUID, "not-a-uuid"), Field.UUID))
        assertEquals(UiText.Res(R.string.subs_form_error_reality_key), err(vless.set(Field.REALITY_KEY, "c2hvcnQ"), Field.REALITY_KEY))
        assertEquals(UiText.Res(R.string.subs_form_error_short_id), err(vless.set(Field.REALITY_SID, "abc"), Field.REALITY_SID))
        assertEquals(UiText.Res(R.string.subs_form_error_short_id), err(vless.set(Field.REALITY_SID, "zz"), Field.REALITY_SID))
        assertNull(err(vless.set(Field.REALITY_SID, "0123456789abcdef"), Field.REALITY_SID))
        assertEquals(UiText.Res(R.string.subs_form_error_port), err(vless.set(Field.PORT, "70000"), Field.PORT))
        assertEquals(UiText.Res(R.string.subs_form_error_server), err(vless.set(Field.SERVER, "https://v.example.com"), Field.SERVER))
        assertEquals(UiText.Res(R.string.subs_form_error_required), err(vless.set(Field.SNI, ""), Field.SNI))
        val visionOverWs = vless.set(Field.TRANSPORT, "ws")
        assertEquals(UiText.Res(R.string.subs_form_error_vision), err(visionOverWs, Field.FLOW))
        assertEquals(UiText.Res(R.string.subs_form_error_path), err(visionOverWs.set(Field.PATH, "ws"), Field.PATH))

        val ss = ConfigForm.blank(FormProtocol.SHADOWSOCKS).set(Field.SERVER, "s.example.com")
        assertEquals(UiText.Res(R.string.subs_form_error_ss2022_key, listOf(16)), err(ss.set(Field.PASSWORD, "plain"), Field.PASSWORD))
        assertNull(err(ss.set(Field.PASSWORD, "$ss16:$ss16"), Field.PASSWORD))
        assertNull(err(ss.set(Field.METHOD, "aes-256-gcm").set(Field.PASSWORD, "plain"), Field.PASSWORD))

        val hy = ConfigForm.blank(FormProtocol.HYSTERIA2).set(Field.SERVER, "h.example.com").set(Field.PASSWORD, "pw")
        assertEquals(UiText.Res(R.string.subs_form_error_ports), err(hy.set(Field.HOP_PORTS, "1-2-3"), Field.HOP_PORTS))
        val hop = hy.set(Field.HOP_PORTS, "20000-30000")
        assertEquals(UiText.Res(R.string.subs_form_error_hop_interval), err(hop.set(Field.HOP_INTERVAL, "3s"), Field.HOP_INTERVAL))
        assertEquals(UiText.Res(R.string.subs_form_error_hop_interval), err(hop.set(Field.HOP_INTERVAL, "30"), Field.HOP_INTERVAL))
        assertNull(err(hop.set(Field.HOP_INTERVAL, "1m30s"), Field.HOP_INTERVAL))
        assertEquals(UiText.Res(R.string.subs_form_error_number), err(hy.set(Field.UP_MBPS, "-5"), Field.UP_MBPS))
        val gecko = hy.set(Field.OBFS, ConfigForm.GECKO)
        assertEquals(UiText.Res(R.string.subs_form_error_required), err(gecko, Field.OBFS_PASSWORD))
        val sized = gecko.set(Field.OBFS_PASSWORD, "op").set(Field.GECKO_MIN, "1200").set(Field.GECKO_MAX, "600")
        assertEquals(UiText.Res(R.string.subs_form_error_packet_range), err(sized, Field.GECKO_MAX))
        assertEquals(UiText.Res(R.string.subs_form_error_packet_size, listOf(2048)), err(sized.set(Field.GECKO_MAX, "4096"), Field.GECKO_MAX))

        val vmess = ConfigForm.blank(FormProtocol.VMESS).set(Field.SERVER, "m.example.com").set(Field.UUID, uuid)
        assertEquals(UiText.Res(R.string.subs_form_error_alter_id), err(vmess.set(Field.ALTER_ID, "-1"), Field.ALTER_ID))
        assertNull(vmess.set(Field.ALTER_ID, "0").toOutbound()["alter_id"])
    }

    @Test
    fun `a VLESS or VMess id that is not a UUID warns but saves, TUIC refuses it`() {
        val warning = UiText.Res(R.string.subs_form_warning_custom_id)
        for (p in listOf(FormProtocol.VLESS, FormProtocol.VMESS)) {
            val f = ConfigForm.blank(p).set(Field.SERVER, "v.example.com").set(Field.SNI, "a.example.org").set(Field.UUID, "my-user")
            assertNull(f.validate().errors[Field.UUID], p.name)
            assertEquals(warning, f.warnings()[Field.UUID], p.name)
            assertNull(f.set(Field.UUID, uuid).warnings()[Field.UUID], p.name)
        }
        val custom = formOf("vless://my-user@c.example.com:443?security=tls&sni=c.example.com#C")
        assertEquals(warning, custom.warnings()[Field.UUID])
        assertEquals(emptyMap(), custom.validate().errors)

        val tuic = ConfigForm.blank(FormProtocol.TUIC).set(Field.SERVER, "t.example.com").set(Field.PASSWORD, "pw").set(Field.UUID, "my-user")
        assertEquals(UiText.Res(R.string.subs_form_error_uuid), tuic.validate().errors[Field.UUID])
        assertNull(tuic.warnings()[Field.UUID])
    }

    @Test
    fun `gecko sizes are checked against the core's defaults for blank fields`() {
        val gecko = ConfigForm.blank(FormProtocol.HYSTERIA2).set(Field.SERVER, "h.example.com").set(Field.PASSWORD, "pw")
            .set(Field.OBFS, ConfigForm.GECKO).set(Field.OBFS_PASSWORD, "op")
        val effective = UiText.Res(R.string.subs_form_error_packet_effective, listOf(512, 1200))
        assertEquals(effective, gecko.set(Field.GECKO_MIN, "1300").validate().errors[Field.GECKO_MIN])
        assertEquals(effective, gecko.set(Field.GECKO_MAX, "400").validate().errors[Field.GECKO_MAX])
        assertEquals(emptyMap(), gecko.set(Field.GECKO_MIN, "300").validate().errors)
        assertEquals(emptyMap(), gecko.set(Field.GECKO_MAX, "1400").validate().errors)
        assertEquals(emptyMap(), gecko.validate().errors)
    }

    @Test
    fun `editing a field clears only its error`() {
        val form = ConfigForm.blank(FormProtocol.TROJAN).validate()
        assertNotNull(form.errors[Field.SERVER])
        val edited = form.set(Field.SERVER, "t.example.com")
        assertNull(edited.errors[Field.SERVER])
        assertNotNull(edited.errors[Field.PASSWORD])
    }

    @Test
    fun `configs the form cannot show stay in text mode`() {
        val read = { t: String -> ConfigParser.read(t, SubId(0)) }
        assertNull(ConfigForm.from(read("vless://$uuid@k.example.com:443?type=kcp&seed=x#K")), "mkcp has no form")
        assertNull(ConfigForm.from(read("hysteria://h.example.com:9080?auth=a&protocol=faketcp")), "unsupported nodes keep their text")
        val byteAuth = """{"type":"hysteria","server":"h.example.com","server_port":443,"auth":"c2VjcmV0","up_mbps":10,"down_mbps":50,"tls":{"enabled":true}}"""
        assertNull(ConfigForm.from(read(byteAuth)), "base64 auth has no field")
        val twoPeers = """{"type":"wireguard","address":["10.0.0.2/32"],"private_key":"$wgPrivate","peers":[
            {"address":"a.example.com","port":51820,"public_key":"$wgPublic","allowed_ips":["0.0.0.0/0"]},
            {"address":"b.example.com","port":51820,"public_key":"$wgPsk","allowed_ips":["10.1.0.0/16"]}]}"""
        assertNull(ConfigForm.from(read(twoPeers)), "the form edits one peer")
        assertNull(ConfigForm.from(read("trojan://a@a.example.com:443\ntrojan://b@b.example.com:443")))
    }

    @Test
    fun `amneziawg fields are checked against what the device accepts`() {
        fun err(form: ConfigForm, field: Field) = form.validate().errors[field]
        val awg = ConfigForm.blank(FormProtocol.AMNEZIAWG).set(Field.SERVER, "198.51.100.8")
            .set(Field.PRIVATE_KEY, wgPrivate).set(Field.PUBLIC_KEY, wgPublic).set(Field.ADDRESS, "10.8.0.2/32")
        assertEquals(emptyMap(), awg.validate().errors)
        assertEquals(UiText.Res(R.string.subs_form_error_junk_range), err(awg.set(Field.JMIN, "80"), Field.JMAX))
        assertNull(err(awg.set(Field.JMIN, "80").set(Field.JC, "0"), Field.JMAX), "no junk packets, no junk sizes")
        assertEquals(UiText.Res(R.string.subs_form_error_int_range, listOf(0, 65535)), err(awg.set(Field.S1, "70000"), Field.S1))
        assertEquals(UiText.Res(R.string.subs_form_error_awg_range), err(awg.set(Field.H1, "9-1"), Field.H1))
        assertEquals(UiText.Res(R.string.subs_form_error_awg_overlap, listOf(1)), err(awg.set(Field.H1, "5-10").set(Field.H2, "8"), Field.H2))
        assertEquals(UiText.Res(R.string.subs_form_error_awg_overlap_default, listOf(2)), err(awg.set(Field.H1, "2"), Field.H1))
        assertEquals(UiText.Res(R.string.subs_form_error_awg_chain), err(awg.set(Field.I1, "<b 0xabc>"), Field.I1))
        assertNull(err(awg.set(Field.I1, "<b 0xaabb><r 16><t>"), Field.I1))
        val cleared = ConfigForm.AWG_FIELDS.fold(awg) { f, field -> f.set(field, "") }
        assertNull(cleared.toOutbound()["amnezia"], "an empty AmneziaWG block saves as plain WireGuard")
    }

    @Test
    fun `wireguard edits keep what the form does not show`() {
        val json = """{"type":"wireguard","tag":"W","mtu":1400,"address":["10.8.0.2/32"],"private_key":"$wgPrivate",
            "peers":[{"address":"198.51.100.9","port":51820,"public_key":"$wgPublic","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":25}],
            "amnezia":{"jc":4,"jmin":40,"jmax":70,"s1":20,"s2":20,"s3":20,"s4":20,"header_protection_key":"$wgPsk","random_trailers":true}}"""
        val form = formOf(json)
        assertEquals(FormProtocol.AMNEZIAWG, form.protocol)
        val o = form.set(Field.S1, "30").set(Field.SERVER, "198.51.100.10").toOutbound()
        val amnezia = o["amnezia"]!!.jsonObject
        assertEquals("30", amnezia.s("s1"))
        assertEquals(wgPsk, amnezia.s("header_protection_key"))
        assertEquals("true", amnezia.s("random_trailers"))
        val peer = (o["peers"] as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals("198.51.100.10", peer.s("address"))
        assertEquals("25", peer.s("persistent_keepalive_interval"))
        assertNull(o["server"])
    }

    @Test
    fun `wireguard addresses take bare IPs and get prefixes`() {
        assertEquals(listOf("10.0.0.2/32", "fd00::2/64"), Cidr.list("10.0.0.2, fd00::2/64"))
        assertEquals(listOf("0.0.0.0/0", "::/0"), Cidr.list(ConfigForm.ALL_TRAFFIC))
        assertEquals(listOf("1:2:3:4:5:6:7:8/128", "::ffff:10.0.0.1/128"), Cidr.list("1:2:3:4:5:6:7:8 ::ffff:10.0.0.1"))
        assertEquals(emptyList(), Cidr.list(" "))
        assertNull(Cidr.list("10.0.0.300"))
        assertNull(Cidr.list("10.0.0.0/33"))
        assertNull(Cidr.list("1:2:3"))
        assertNull(Cidr.list("1::2::3"))
        assertNull(Cidr.list("example.com"))
        assertEquals(listOf("::/128", "::1.2.3.4/128", "1:2:3:4:5:6:1.2.3.4/128"), Cidr.list("::, ::1.2.3.4, 1:2:3:4:5:6:1.2.3.4"))
        for (bad in listOf("fd00::2:", "fd00:::2", ":fd00::2", "1.2.3.4::", "1.2.3.4::1", ":::", "fe80::1%eth0", "12345::1", "1:2:3:4:5:6:7:8:9", "1::2:3:4:5:6:7:8")) {
            assertNull(Cidr.list(bad), bad)
        }
    }

    @Test
    fun `wireguard address lists made of separators only are required`() {
        val wg = ConfigForm.blank(FormProtocol.WIREGUARD)
        assertEquals(UiText.Res(R.string.subs_form_error_required), wg.set(Field.ALLOWED_IPS, ", ").validate().errors[Field.ALLOWED_IPS])
        assertEquals(UiText.Res(R.string.subs_form_error_required), wg.set(Field.ADDRESS, ",").validate().errors[Field.ADDRESS])
    }

    @Test
    fun `typed header ranges save in the form the core decodes`() {
        val awg = ConfigForm.blank(FormProtocol.AMNEZIAWG).set(Field.SERVER, "198.51.100.8")
            .set(Field.PRIVATE_KEY, wgPrivate).set(Field.PUBLIC_KEY, wgPublic).set(Field.ADDRESS, "10.8.0.2/32")
            .set(Field.H1, "100\t- 200").set(Field.H2, " 300 ")
        assertEquals(emptyMap(), awg.validate().errors)
        val amnezia = awg.toOutbound()["amnezia"]!!.jsonObject
        assertEquals("100-200", amnezia.s("h1"))
        assertEquals("300", amnezia.s("h2"))
        assertEquals(Capability.SUPPORTED, node(awg.toText()).capability)
    }

    @Test
    fun `settings the form cannot show keep the config in text mode`() {
        val read = { t: String -> ConfigParser.read(t, SubId(0)) }
        val objectRange = """{"type":"wireguard","address":["10.8.0.2/32"],"private_key":"$wgPrivate",
            "peers":[{"address":"198.51.100.9","port":51820,"public_key":"$wgPublic","allowed_ips":["0.0.0.0/0"]}],
            "amnezia":{"jc":4,"jmin":40,"jmax":70,"h1":{"from":100,"to":200}}}"""
        assertEquals(Capability.SUPPORTED, node(objectRange).capability)
        assertNull(ConfigForm.from(read(objectRange)), "a {from,to} range has no field")
        val socks4 = """{"type":"socks","server":"s.example.com","server_port":1080,"version":"4a","username":"u"}"""
        assertNull(ConfigForm.from(read(socks4)), "the form is SOCKS5")
    }

    @Test
    fun `other protocols ask for what their core needs`() {
        fun err(form: ConfigForm, field: Field) = form.validate().errors[field]
        val hy1 = ConfigForm.blank(FormProtocol.HYSTERIA).set(Field.SERVER, "h.example.com")
        assertEquals(UiText.Res(R.string.subs_form_error_required), err(hy1.set(Field.UP_MBPS, ""), Field.UP_MBPS))
        assertNull(err(hy1, Field.OBFS_PASSWORD), "xplus is optional")

        val juicity = formOf(links.getValue("juicity pinned"))
        assertTrue(Field.INSECURE !in juicity.fields(), "a pin replaces CA checks")
        assertTrue(Field.INSECURE in juicity.set(Field.PIN_SHA256, "").fields())
        assertNull(juicity.switch(Field.INSECURE, true).toOutbound()["tls"]!!.jsonObject["insecure"])
        assertEquals(UiText.Res(R.string.subs_form_error_pin), err(juicity.set(Field.PIN_SHA256, "abc"), Field.PIN_SHA256))
        assertNull(err(juicity.set(Field.PIN_SHA256, "ab".repeat(32)), Field.PIN_SHA256))
        assertEquals(UiText.Res(R.string.subs_form_error_uuid), err(juicity.set(Field.UUID, "my-user"), Field.UUID))

        val socks = ConfigForm.blank(FormProtocol.SOCKS).set(Field.SERVER, "s.example.com")
        assertEquals(UiText.Res(R.string.subs_form_error_required), err(socks.set(Field.PASSWORD, "p"), Field.USERNAME))
        assertEquals(listOf(ConfigForm.NONE, ConfigForm.TLS), ConfigForm.blank(FormProtocol.HTTP).options(Field.SECURITY))

        val wg = ConfigForm.blank(FormProtocol.WIREGUARD)
        assertEquals(UiText.Res(R.string.subs_form_error_wg_key), err(wg.set(Field.PUBLIC_KEY, "c2hvcnQ="), Field.PUBLIC_KEY))
        assertEquals(UiText.Res(R.string.subs_form_error_cidr), err(wg.set(Field.ADDRESS, "10.0.0"), Field.ADDRESS))
        assertEquals(UiText.Res(R.string.subs_form_error_int_range, listOf(576, 65535)), err(wg.set(Field.MTU, "100"), Field.MTU))
    }

    @Test
    fun `a name equal to the address is shown as empty`() {
        assertEquals("", formOf("trojan://pw@t.example.com:443").name)
        assertEquals("T", formOf(links.getValue("trojan default")).name)
    }

    @Test
    fun `durations`() {
        assertEquals(30.0, FieldRules.seconds("30s"))
        assertEquals(90.0, FieldRules.seconds("1m30s"))
        assertEquals(0.5, FieldRules.seconds("500ms"))
        assertNull(FieldRules.seconds("30"))
        assertNull(FieldRules.seconds("s"))
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
