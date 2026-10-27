package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.desupon.config.Fixtures.byTag
import moe.desupon.config.Fixtures.objects
import moe.desupon.config.Fixtures.strings
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainConfigTest {
    private val gen = ConfigGenerator()
    private var serial = 0

    /** [base] dialing through [hops], nearest first, the way the parser stores it. */
    private fun chained(base: Node, vararg hops: Node): Node {
        val json = Fixtures.parse(base.outboundJson.raw())
        val chain = JsonArray(hops.map { Fixtures.parse(it.outboundJson.raw()) })
        return base.copy(
            id = NodeId("%016x".format(++serial) + base.id.hash.drop(16)),
            outboundJson = Secret(JsonObject(json + (Node.CHAIN_KEY to chain)).toString()),
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun main(vararg nodes: Node): JsonObject = Fixtures.parse(
        gen.mainConfig(MainConfigRequest(selection = mapOf(Tier.DIRECT to nodes.toList()), mode = NetworkMode.OPEN)).json.raw(),
    )

    @Test
    fun `hops are emitted once per node, linked by detour, outside the groups`() {
        val node = chained(Fixtures.vlessReality, Fixtures.trojanGrpc, Fixtures.shadowsocks)
        val cfg = gen.mainConfig(MainConfigRequest(selection = mapOf(Tier.DIRECT to listOf(node, node, Fixtures.socks)), mode = NetworkMode.OPEN))
        assertEquals(listOf(node.id.tag, Fixtures.socks.id.tag), cfg.nodeTags)
        val root = Fixtures.parse(cfg.json.raw())
        val hop1 = "${node.id.tag}-1"
        val hop2 = "${node.id.tag}-2"
        val head = root.byTag(node.id.tag)
        assertEquals(hop1, head.str("detour"))
        assertNull(head["domain_resolver"])
        assertNull(head[Node.CHAIN_KEY])
        assertEquals("chrome", head["tls"]!!.jsonObject["utls"]!!.jsonObject.str("fingerprint"))
        root.byTag(hop1).let {
            assertEquals("trojan", it.str("type"))
            assertEquals(hop2, it.str("detour"))
            assertNull(it["domain_resolver"])
        }
        root.byTag(hop2).let {
            assertEquals("shadowsocks", it.str("type"))
            assertNull(it["detour"])
            assertEquals("local", it["domain_resolver"]!!.jsonObject.str("server"))
        }
        assertEquals(1, root.objects("outbounds").count { it.str("tag") == hop1 })
        for (group in root.objects("outbounds").filter { it.str("type") in setOf("selector", "urltest") }) {
            assertFalse(group["outbounds"]!!.strings().any { it.startsWith("${node.id.tag}-") })
        }
    }

    @Test
    fun `probe config carries the hops`() {
        val node = chained(Fixtures.trojanGrpc, Fixtures.vlessReality)
        val cfg = gen.probeConfig(listOf(node))
        assertEquals(listOf(node.id.tag), cfg.nodeTags)
        val root = Fixtures.parse(cfg.json.raw())
        assertEquals(listOf(node.id.tag, "${node.id.tag}-1"), root.objects("outbounds").map { it.str("tag") })
    }

    @Test
    fun `wireguard hops go to endpoints and wireguard nodes keep their resolver behind a hop`() {
        val viaWg = chained(Fixtures.trojanGrpc, Fixtures.wireguard)
        val wgViaTrojan = chained(Fixtures.wireguard, Fixtures.trojanGrpc)
        val root = main(viaWg, wgViaTrojan)
        assertEquals("wireguard", root.objects("endpoints").single { it.str("tag") == "${viaWg.id.tag}-1" }.str("type"))
        val wg = root.objects("endpoints").single { it.str("tag") == wgViaTrojan.id.tag }
        assertEquals("${wgViaTrojan.id.tag}-1", wg.str("detour"))
        assertEquals("local", wg["domain_resolver"]!!.jsonObject.str("server"))
        assertEquals("trojan", root.objects("outbounds").single { it.str("tag") == "${wgViaTrojan.id.tag}-1" }.str("type"))
    }

    @Test
    fun `a hop the core would refuse rejects only its node`() {
        val badHop = Fixtures.vlessReality.copy(
            outboundJson = Secret(Fixtures.vlessReality.outboundJson.raw().replace(Fixtures.REALITY_PUBLIC, "c2hvcnQ")),
        )
        val node = chained(Fixtures.trojanGrpc, badHop)
        val tooLong = chained(Fixtures.trojanGrpc, Fixtures.socks, Fixtures.socks, Fixtures.socks, Fixtures.socks)
        val cfg = gen.probeConfig(listOf(node, tooLong, Fixtures.socks))
        assertTrue(cfg.rejected[node.id]?.startsWith("chain hop 1: REALITY public_key") == true, cfg.rejected[node.id])
        assertEquals("chain longer than 3", cfg.rejected[tooLong.id])
        assertEquals(listOf(Fixtures.socks.id.tag), cfg.nodeTags)
        assertTrue(Fixtures.parse(cfg.json.raw()).objects("outbounds").none { it.str("tag")!!.startsWith(node.id.tag) })
    }

    @Test
    fun `validator refuses groups listing a hop and detour loops`() {
        val node = chained(Fixtures.trojanGrpc, Fixtures.socks)
        val root = main(node)
        val hop = "${node.id.tag}-1"
        val outbounds = root.objects("outbounds").map { o ->
            when (o.str("tag")) {
                ConfigGenerator.TAG_PROXY -> JsonObject(o + ("outbounds" to JsonArray((o["outbounds"]!!.strings() + hop).map(::JsonPrimitive))))
                hop -> JsonObject(o + ("detour" to JsonPrimitive(node.id.tag)))
                else -> o
            }
        }
        val violations = gen.validate(JsonObject(root + ("outbounds" to JsonArray(outbounds))).toString(), ConfigKind.MAIN)
        assertTrue(violations.any { it.contains("lists a chain hop") }, violations.toString())
        assertTrue(violations.any { it.contains("detour chain from ${node.id.tag} loops") }, violations.toString())
        assertEquals(emptyList(), gen.validate(root.toString(), ConfigKind.MAIN))
    }
}
