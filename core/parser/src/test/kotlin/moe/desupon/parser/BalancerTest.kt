package moe.desupon.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.parser.Fx.assertAt
import moe.desupon.parser.Fx.json
import moe.desupon.parser.Fx.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BalancerTest {
    private val parsed = Fx.parse(Fx.text("balancer/xray.json"))
    private val byName = parsed.nodes.associateBy { it.displayName }

    private fun Node.chain(): List<JsonObject> = (json()[Node.CHAIN_KEY] as? JsonArray)?.map { it as JsonObject }.orEmpty()

    @Test
    fun `every balancer member becomes a node named after the config and its tag`() {
        assertEquals(
            listOf(
                "🇩🇪 Germany balanced · proxy-1",
                "🇩🇪 Germany balanced · proxy-2",
                "🇩🇪 Germany balanced · proxy-3",
                "🇳🇱 NL burst · proxy",
                "🇳🇱 NL burst · lb-a",
                "🇳🇱 NL burst · lb-c",
                "🇫🇮 unmatched balancer",
            ),
            parsed.nodes.map { it.displayName },
        )
        assertEquals("DE", byName.getValue("🇩🇪 Germany balanced · proxy-2").country)
    }

    @Test
    fun `members keep their per-node fixes and fragmentation`() {
        for ((tag, transport) in listOf("proxy-1" to null, "proxy-2" to "grpc", "proxy-3" to "xhttp")) {
            val node = byName.getValue("🇩🇪 Germany balanced · $tag")
            assertEquals(Capability.SUPPORTED, node.capability, tag)
            val j = node.json()
            assertAt(transport, j, "transport.type")
            assertAt(true, j, "tls.reality.enabled")
            assertAt("chrome", j, "tls.utls.fingerprint")
            assertAt(true, j, "tls.record_fragment")
            assertNull(j.s("tls.fragment"))
            assertEquals(emptyList(), node.chain())
        }
    }

    @Test
    fun `member chains resolve, duplicates and bad keys are counted`() {
        val c = byName.getValue("🇳🇱 NL burst · lb-c")
        assertAt("shadowsocks", c.chain().single(), "type")
        assertEquals(1, parsed.duplicates)
        assertEquals(listOf(SkippedEntry(1, "vless", SkipReason.MALFORMED, "REALITY public key is not 32 bytes")), parsed.skipped)
        assertEquals(emptyMap(), parsed.unsupportedReasons)
    }

    @Test
    fun `fallbackTag pulls in a proxy outside the selector`() {
        val body = Fx.text("balancer/xray.json").replace("\"fallbackTag\": \"direct\"", "\"fallbackTag\": \"extra\"")
            .replace(
                "{\"tag\": \"direct\", \"protocol\": \"freedom\"},\n      {\"tag\": \"block\"",
                "{\"tag\": \"extra\", \"protocol\": \"trojan\", \"settings\": {\"servers\": [{\"address\": \"de-extra.example.com\", \"port\": 443, \"password\": \"p\"}]}},\n" +
                    "      {\"tag\": \"direct\", \"protocol\": \"freedom\"},\n      {\"tag\": \"block\"",
            )
        val names = Fx.parse(body).nodes.map { it.displayName }
        assertEquals(true, "🇩🇪 Germany balanced · extra" in names, names.toString())
    }
}
