package moe.desupon.model

import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.NodeKey
import moe.desupon.model.node.Protocol
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionInfo
import moe.desupon.model.turn.TurnProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SecretTest {
    private val raw = "synthetic-secret-1234"

    @Test
    fun `toString and templates are masked`() {
        val s = Secret(raw)
        assertEquals(Secret.MASK, s.toString())
        assertEquals("k=${Secret.MASK}", "k=$s")
        assertFalse(listOf(s).toString().contains(raw))
    }

    @Test
    fun `raw value is preserved and compared by value`() {
        assertEquals(raw, Secret(raw).raw())
        assertEquals(Secret(raw), Secret(raw))
        assertNotEquals(Secret(raw), Secret("other"))
        assertEquals(Secret(raw).hashCode(), Secret(raw).hashCode())
    }

    @Test
    fun `containing models do not leak secrets`() {
        val key = NodeKey(Protocol.TROJAN, "example.com", 443, credential = Secret(raw))
        val node = Node(
            id = key.id,
            subs = setOf(SubId(1)),
            protocol = Protocol.TROJAN,
            displayName = "node",
            country = "NL",
            capability = Capability.SUPPORTED,
            outboundJson = Secret("""{"type":"trojan","password":"$raw"}"""),
            isEndpoint = false,
            udpOnly = false,
        )
        val turn = TurnProfile(
            id = "t",
            peer = "203.0.113.1:56000",
            obfKey = Secret(raw),
            clientId = Secret(raw),
            callLink = Secret(raw),
            wgConf = Secret(raw),
        )
        val sub = SubscriptionInfo(
            id = SubId(1),
            url = Secret("https://example.com/sub/$raw"),
            title = null,
            userInfo = null,
            updateIntervalH = null,
            supportUrl = null,
            webPageUrl = null,
            announce = null,
            lastOkAtMs = null,
            lastError = null,
        )
        for (model in listOf<Any>(key, node, turn, sub)) {
            assertFalse(model.toString().contains(raw), model::class.simpleName)
        }
        assertFalse(NodeId.of(key).toString().contains(raw))
    }
}
