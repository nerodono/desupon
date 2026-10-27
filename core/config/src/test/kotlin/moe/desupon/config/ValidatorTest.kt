package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ValidatorTest {
    private val gen = ConfigGenerator()

    private val main: JsonObject = Fixtures.parse(
        gen.mainConfig(
            MainConfigRequest(
                selection = mapOf(Tier.DIRECT to Fixtures.all),
                mode = NetworkMode.OPEN,
                rules = Fixtures.rules,
                ruleSets = Fixtures.ruleSets,
            ),
        ).json.raw(),
    )

    private val probe: JsonObject = Fixtures.parse(gen.probeConfig(Fixtures.all).json.raw())

    private fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
        JsonObject(toMutableMap().apply { if (value == null) remove(key) else put(key, value) })

    private fun JsonObject.at(key: String, f: (JsonObject) -> JsonObject): JsonObject = with(key, f(this[key]!!.jsonObject))

    private fun JsonObject.tun(f: (JsonObject) -> JsonObject): JsonObject =
        with("inbounds", JsonArray(listOf(f((this["inbounds"] as JsonArray).single().jsonObject))))

    private fun JsonObject.rules(f: (List<JsonElement>) -> List<JsonElement>): JsonObject =
        at("route") { it.with("rules", JsonArray(f(it["rules"] as JsonArray))) }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is JsonElement -> v
                is Boolean -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun assertFlagged(config: JsonObject, expected: String, kind: ConfigKind = ConfigKind.MAIN) {
        val violations = gen.validate(config.toString(), kind)
        if (violations.none { expected in it }) fail("expected '$expected' in $violations")
    }

    @Test
    fun `generated configs are clean`() {
        assertEquals(emptyList(), gen.validate(main.toString()))
        assertEquals(emptyList(), gen.validate(probe.toString()))
    }

    @Test
    fun `tun invariants`() {
        assertFlagged(main.tun { it.with("stack", JsonPrimitive("mixed")) }, "gvisor")
        assertFlagged(main.tun { it.with("stack", null) }, "gvisor")
        assertFlagged(main.tun { it.with("mtu", null) }, "mtu not explicit")
        assertFlagged(main.tun { it.with("mtu", JsonPrimitive(9000)) }, "mtu out of range")
        assertFlagged(main.tun { it.with("auto_route", null) }, "auto_route")
        assertFlagged(main.tun { it.with("include_package", JsonArray(listOf(JsonPrimitive("a.b")))) }, "include_package")
        assertFlagged(main.tun { it.with("exclude_package", JsonArray(listOf(JsonPrimitive("a.b")))) }, "exclude_package")
        assertFlagged(main.tun { it.with("platform", obj("http_proxy" to obj("enabled" to true))) }, "http_proxy")
        assertFlagged(main.tun { it.with("address", JsonArray(listOf(JsonPrimitive("172.19.0.1/30")))) }, "default address")
        assertFlagged(main.tun { it.with("address", JsonArray(listOf(JsonPrimitive("fdfe:dcba:9876::1/126")))) }, "default address")
        assertFlagged(main.tun { it.with("address", null) }, "address missing")
    }

    @Test
    fun `only inbound is tun`() {
        val mixed = obj("type" to "mixed", "tag" to "m", "listen" to "127.0.0.1", "listen_port" to 2080)
        assertFlagged(main.with("inbounds", JsonArray(main["inbounds"] as JsonArray + mixed)), "only inbound")
        assertFlagged(main.with("inbounds", JsonArray(listOf(mixed))), "only inbound")
        assertFlagged(probe.with("inbounds", JsonArray(listOf(mixed))), "inbounds in probe", ConfigKind.PROBE)
    }

    @Test
    fun `apis, debug and missing cache file`() {
        for (key in listOf("clash_api", "v2ray_api", "debug")) {
            assertFlagged(main.at("experimental") { it.with(key, obj("listen" to "127.0.0.1:9090")) }, "experimental.$key")
        }
        assertFlagged(main.with("experimental", null), "cache_file")
        assertFlagged(main.at("experimental") { it.with("cache_file", obj("enabled" to false)) }, "cache_file")
        assertFlagged(probe.with("experimental", obj("cache_file" to obj("enabled" to true))), "experimental section", ConfigKind.PROBE)
        assertFlagged(main.with("services", JsonArray(emptyList())), "services")
        assertFlagged(main.with("log", obj("output" to "/sdcard/box.log")), "log.output")
    }

    @Test
    fun `route invariants`() {
        assertFlagged(main.at("route") { it.with("auto_detect_interface", null) }, "auto_detect_interface")
        assertFlagged(main.at("route") { it.with("default_domain_resolver", null) }, "default_domain_resolver")
        assertFlagged(main.at("route") { it.with("final", JsonPrimitive("direct")) }, "final must be proxy")
        assertFlagged(main.rules { it.drop(1) }, "must start with")
        assertFlagged(main.rules { listOf(it[1], it[0]) + it.drop(2) }, "must start with")
        assertFlagged(main.rules { it.take(2) + it.drop(3) }, "must start with")
        assertFlagged(main.rules { it + obj("domain" to JsonArray(listOf(JsonPrimitive("x"))), "outbound" to "nowhere") }, "unknown outbound")
        val remote = obj("type" to "remote", "tag" to "r", "format" to "binary", "url" to "https://example.com/r.srs")
        assertFlagged(main.at("route") { it.with("rule_set", JsonArray(listOf(remote))) }, "not local")
    }

    @Test
    fun `dns integrity`() {
        assertFlagged(main.at("dns") { it.with("servers", JsonArray(listOf(obj("address" to "8.8.8.8")))) }, "legacy format")
        assertFlagged(main.at("dns") { it.with("final", JsonPrimitive("gone")) }, "dns.final")
        val noLocal = main.at("dns") { d -> d.with("servers", JsonArray((d["servers"] as JsonArray).drop(1))) }
        assertFlagged(noLocal, "unknown DNS server")
        assertFlagged(noLocal, "no local server")
    }

    @Test
    fun `node rule negatives`() {
        val xhttp = Fixtures.vlessXhttp.id.tag
        val reality = Fixtures.vlessReality.id.tag
        val hy2 = Fixtures.hysteria2.id.tag
        val wg = Fixtures.wireguard.id.tag
        val awg = Fixtures.amneziawg.id.tag
        fun edit(tag: String, f: (JsonObject) -> JsonObject): JsonObject {
            val section = if (tag == wg || tag == awg) "endpoints" else "outbounds"
            return probe.with(
                section,
                JsonArray((probe[section] as JsonArray).map { o -> if (o.jsonObject["tag"] == JsonPrimitive(tag)) f(o.jsonObject) else o }),
            )
        }

        fun check(tag: String, expected: String, f: (JsonObject) -> JsonObject) = assertFlagged(edit(tag, f), expected, ConfigKind.PROBE)

        check(xhttp, "x_padding_bytes") { o -> o.at("transport") { it.with("x_padding_bytes", null) } }
        check(xhttp, "x_padding_bytes") { o -> o.at("transport") { it.with("x_padding_bytes", JsonPrimitive("0-0")) } }
        check(xhttp, "Host") { o -> o.at("transport") { it.with("headers", obj("host" to "a.example")) } }
        check(xhttp, "stream-one with download") { o -> o.at("transport") { it.with("download", obj("x_padding_bytes" to "100-1000")) } }
        check(reality, "fingerprint chrome") { o -> o.at("tls") { t -> t.with("utls", obj("enabled" to true, "fingerprint" to "random")) } }
        check(reality, "support_x25519mlkem768") { o -> o.at("tls") { t -> t.at("reality") { it.with("support_x25519mlkem768", null) } } }
        check(reality, "unsupported flow") { it.with("flow", JsonPrimitive("xtls-rprx-vision-udp443")) }
        check(hy2, "uTLS on a QUIC") { o -> o.at("tls") { it.with("utls", obj("enabled" to true, "fingerprint" to "chrome")) } }
        check(Fixtures.vlessXhttpH3.id.tag, "uTLS on a QUIC") { o ->
            o.at("tls") { it.with("utls", obj("enabled" to true, "fingerprint" to "chrome")) }
        }
        check(wg, "peer reserved") { o ->
            o.with("peers", JsonArray((o["peers"] as JsonArray).map { it.jsonObject.with("reserved", JsonArray(listOf(JsonPrimitive(1)))) }))
        }
        check(wg, "keepalive") { o ->
            o.with("peers", JsonArray((o["peers"] as JsonArray).map { it.jsonObject.with("persistent_keepalive_interval", JsonPrimitive(25)) }))
        }
        check(wg, "listen_port") { it.with("listen_port", JsonPrimitive(51820)) }
        check(awg, "S1-S4") { o -> o.at("amnezia") { it.with("s2", JsonPrimitive(11)) } }
        check(awg, "domain_resolver missing") { it.with("domain_resolver", null) }
        val wgOutbound = probe.with("outbounds", JsonArray((probe["outbounds"] as JsonArray) + (probe["endpoints"] as JsonArray).first()))
        assertFlagged(wgOutbound, "must be an endpoint", ConfigKind.PROBE)
    }

    @Test
    fun `garbage input`() {
        assertEquals(listOf("config is not a JSON object"), gen.validate("{\"password\":\"${Fixtures.PASSWORD}\""))
        assertEquals(listOf("config is not a JSON object"), gen.validate("[]"))
    }

    @Test
    fun `secrets never reach messages`() {
        val messages = mutableListOf<String>()
        val broken = Fixtures.SECRETS.map { s -> Fixtures.node(Protocol.VLESS, "198.51.100.99", 1, """{"type":"vless","uuid":"$s",""") }
        val cfg = gen.probeConfig(Fixtures.all + Fixtures.awgSmallPadding + Fixtures.freeturn + Fixtures.malformed + broken)
        messages += cfg.rejected.values
        messages += assertFailsWith<ConfigException> { gen.probeConfig(broken + Fixtures.awgSmallPadding) }.let { listOfNotNull(it.message) + it.violations }
        messages += gen.validate("{\"x\":\"${Fixtures.PASSWORD}\",")
        // Every secret is placed into a field the validator complains about.
        val poisoned = probe.with(
            "outbounds",
            JsonArray(
                (probe["outbounds"] as JsonArray).map { o ->
                    o.jsonObject.with("tag", JsonPrimitive(Fixtures.UUID)).with("detour", JsonPrimitive(Fixtures.PASSWORD))
                        .with("flow", JsonPrimitive(Fixtures.WG_PRIVATE)).with("type", JsonPrimitive("vless"))
                },
            ),
        ).with("dns", obj("servers" to JsonArray(listOf(obj("type" to "udp", "tag" to Fixtures.OBFS_PASSWORD, "detour" to Fixtures.SS_PASSWORD)))))
        messages += gen.validate(poisoned.toString())
        assertTrue(messages.size > 10)
        for (m in messages) for (s in Fixtures.SECRETS) assertTrue(s !in m, "secret leaked into: $m")
    }

    @Test
    fun `generated json is a Secret`() {
        assertEquals(Secret.MASK, gen.probeConfig(listOf(Fixtures.socks)).json.toString())
    }
}
