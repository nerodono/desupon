package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden configs, also fed to the real core by native/bridge/golden_test.go.
 * Files under invalid/ break one rule each; the core must refuse them and [ConfigGenerator.validate] must flag them.
 */
class GoldenTest {
    private val gen = ConfigGenerator()

    private val goldens = Goldens(Goldens.root, foreign = setOf(FromParserGoldenTest.FROM_PARSER))

    private fun valid(): Map<String, String> = with(Fixtures) {
        mapOf(
            "probe-all-types.json" to gen.probeConfig(all).json.raw(),
            "main-open-rules.json" to gen.mainConfig(
                MainConfigRequest(
                    selection = mapOf(
                        Tier.DIRECT to listOf(vlessXhttp, vlessXhttpH3, vlessReality, vlessEncryption, vmessWs, trojanGrpc, shadowsocks),
                        Tier.RU to listOf(hysteria2, hysteria, tuic, anytls, socks, http, wireguard, amneziawg),
                    ),
                    mode = NetworkMode.OPEN,
                    rules = rules,
                    ruleSets = ruleSets,
                ),
            ).json.raw(),
            "main-whitelist-lan.json" to gen.mainConfig(
                MainConfigRequest(
                    selection = mapOf(
                        Tier.DIRECT to listOf(vlessXhttp),
                        Tier.RU to listOf(hysteria2, amneziawg),
                        Tier.TURN to listOf(wireguard),
                    ),
                    mode = NetworkMode.WHITELIST,
                    dns = DnsSettings(remote = "tls://dns.example.net", strategy = DnsStrategy.IPV4_ONLY),
                    lanBypass = true,
                    mtu = 1400,
                ),
            ).json.raw(),
            "main-placeholder.json" to gen.placeholderConfig(lanBypass = true).raw(),
            "main-turn-only.json" to gen.mainConfig(
                MainConfigRequest(selection = mapOf(Tier.TURN to listOf(wireguard)), mode = NetworkMode.OFFLINE),
            ).json.raw(),
        )
    }

    /** One broken rule per file, cut from a valid single-node probe config. */
    private fun invalid(): Map<String, String> = with(Fixtures) {
        fun single(node: moe.desupon.model.node.Node, edit: (MutableMap<String, JsonElement>) -> Unit): String {
            val root = parse(gen.probeConfig(listOf(node)).json.raw()).toMutableMap()
            val section = if (node.isEndpoint) "endpoints" else "outbounds"
            val item = (root[section] as JsonArray).single().jsonObject.toMutableMap().also(edit)
            root[section] = JsonArray(listOf(JsonObject(item)))
            return JsonObject(root).toString()
        }

        fun MutableMap<String, JsonElement>.edit(key: String, f: (MutableMap<String, JsonElement>) -> Unit) {
            this[key] = JsonObject(this[key]!!.jsonObject.toMutableMap().also(f))
        }
        mapOf(
            "invalid/xhttp-no-padding.json" to single(vlessXhttp) { o -> o.edit("transport") { it.remove("x_padding_bytes") } },
            "invalid/xhttp-host-header.json" to single(vlessXhttp) { o ->
                o.edit("transport") { it["headers"] = JsonObject(mapOf("Host" to JsonPrimitive("cdn.example.com"))) }
            },
            "invalid/vless-flow-udp443.json" to single(vlessReality) { it["flow"] = JsonPrimitive("xtls-rprx-vision-udp443") },
            "invalid/wg-peer-reserved.json" to single(wireguard) { o ->
                val peer = (o["peers"] as JsonArray).single().jsonObject.toMutableMap()
                peer["reserved"] = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3)))
                o["peers"] = JsonArray(listOf(JsonObject(peer)))
            },
            "invalid/wg-short-private-key.json" to single(wireguard) { it["private_key"] = JsonPrimitive(SHORT_KEY) },
            "invalid/reality-short-public-key.json" to single(vlessReality) { o ->
                o.edit("tls") { t -> t.edit("reality") { it["public_key"] = JsonPrimitive(SHORT_KEY.trimEnd('=')) } }
            },
            "invalid/ss2022-key-length.json" to single(shadowsocks) { it["password"] = JsonPrimitive(WG_PSK) },
            "invalid/tuic-uuid.json" to single(tuic) { it["uuid"] = JsonPrimitive("not-a-uuid") },
            "invalid/juicity-pin.json" to single(juicity) { it["pinned_certchain_sha256"] = JsonPrimitive(SHORT_KEY) },
            "invalid/wg-as-outbound.json" to parse(gen.probeConfig(listOf(wireguard)).json.raw()).let { root ->
                val out = root.toMutableMap()
                out["outbounds"] = out.remove("endpoints")!!
                JsonObject(out).toString()
            },
        )
    }

    /** 31 bytes, base64. */
    private val SHORT_KEY = "c3ludGhldGljLXNob3J0LWtleS0wMDAwMDAwMDAwMQ=="

    @Test
    fun `goldens match`() {
        goldens.check(valid() + invalid())
    }

    @Test
    fun `invalid goldens are flagged by validate`() {
        for ((name, json) in invalid()) {
            assertTrue(gen.validate(json, ConfigKind.PROBE).isNotEmpty(), "$name passed validate")
        }
    }

    @Test
    fun `valid goldens pass validate`() {
        for ((name, json) in valid()) {
            assertEquals(emptyList(), gen.validate(json), name)
        }
    }
}
