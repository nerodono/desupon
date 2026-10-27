package moe.desupon.config

import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.Tier
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.SubscriptionParser
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Parser fixtures through the generator into golden/from-parser/, which native/bridge/golden_test.go feeds to the core. */
class FromParserGoldenTest {
    private val gen = ConfigGenerator()
    private val fixtures = File(System.getProperty("parser.fixtures") ?: "../parser/src/test/resources/fixtures")
    private val goldens = Goldens(File(Goldens.root, FROM_PARSER))

    private fun text(path: String): String = File(fixtures, path).readText()

    private fun headers(path: String): Map<String, String> = text(path).lines().filter { ':' in it }
        .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }

    private fun parse(body: String, headers: Map<String, String> = emptyMap()): List<Node> =
        SubscriptionParser.parse(body, SubId(1), headers).nodes

    /** Vector ids whose expected outcome is a supported node. */
    private fun supportedVectors(): List<Pair<String, String>> = text("vectors.tsv").lines().filter { it.isNotBlank() }
        .map { it.split('\t') }
        .filter { (_, expect) -> ':' !in expect }
        .map { it[0] to it[2] }

    private fun sources(): Map<String, List<Node>> = buildMap {
        put("xui-body", parse(text("xui/body.b64"), headers("xui/headers.txt")))
        put("xui-links", parse(text("xui/links.txt")))
        put("xui-awg-nl", parse(text("xui/awg_nl.conf")))
        put("xui-awg-fi", parse(text("xui/awg_fi.conf")))
        put("singbox", parse(text("singbox.json")))
        put("xray", parse(text("xray.json")))
        put("sip008", parse(text("sip008.json")))
        put("clash", parse(text("clash.yaml")))
        put("chains-xray", parse(text("chains/xray.json")))
        put("chains-clash", parse(text("chains/clash.yaml")))
        put("chains-singbox", parse(text("chains/singbox.json")))
        put("balancer-xray", parse(text("balancer/xray.json")))
        val profile = Base64.getUrlEncoder().withoutPadding().encodeToString(text("amnezia_profile.json").toByteArray())
        put("amnezia-profile", parse("vpn://$profile"))
        put("vectors", supportedVectors().flatMap { (_, link) -> parse(link) })
    }

    @Test
    fun `parser output passes the generator and matches goldens`() {
        val cases = LinkedHashMap<String, String>()
        val problems = mutableListOf<String>()
        val rejected = sortedSetOf<String>()
        for ((name, nodes) in sources()) {
            val supported = nodes.filter { it.capability == Capability.SUPPORTED }
            if (supported.isEmpty()) {
                problems += "$name: parser produced no supported nodes"
                continue
            }
            val probe = gen.probeConfig(supported)
            val main = gen.mainConfig(MainConfigRequest(selection = mapOf(Tier.DIRECT to supported), mode = NetworkMode.OPEN))
            for ((id, reason) in probe.rejected + main.rejected) rejected += "$name ${id.tag}: $reason"
            cases["probe-$name.json"] = probe.json.raw()
            cases["main-$name.json"] = main.json.raw()
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        assertEquals(KNOWN_PARSER_ISSUES.joinToString("\n"), rejected.joinToString("\n"), "nodes the core would refuse")
        goldens.check(cases)
    }

    @Test
    fun `every supported vector yields a node`() {
        val missing = supportedVectors().filter { (_, link) -> parse(link).none { it.capability == Capability.SUPPORTED } }
        assertEquals(emptyList(), missing.map { it.first })
        assertTrue(supportedVectors().size > 20)
    }

    companion object {
        const val FROM_PARSER = "from-parser"

        /** Parser fixtures or vectors the core refuses at start; drop an entry once the parser rejects or fixes that node. */
        private val KNOWN_PARSER_ISSUES = sortedSetOf<String>()
    }
}
