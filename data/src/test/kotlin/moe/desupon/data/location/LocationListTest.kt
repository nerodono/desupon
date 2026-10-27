package moe.desupon.data.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.PoolSelection
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocationListTest {
    private val wifi = NetworkKey(Transport.WIFI, "k")

    private fun node(id: String, country: String?, vararg subs: Long, capability: Capability = Capability.SUPPORTED) = Node(
        id = NodeId(id), subs = subs.map(::SubId).toSet(), protocol = Protocol.TROJAN, displayName = id.uppercase(),
        country = country, capability = capability, outboundJson = Secret("{}"), isEndpoint = false, udpOnly = false,
    )

    private fun sub(id: Long, title: String?) = SubscriptionEntry(
        SubscriptionInfo(SubId(id), Secret.EMPTY, title, null, null, null, null, null, null, null),
        nodeCount = 0, isLocal = false, userAgent = null, lastAttemptAtMs = null, skippedCount = null,
    )

    private fun ok(id: String, rtt: Double) = ProbeStat(NodeId(id), wifi, rtt, 3, 0, 0, ProbeStage.GET204, 1L)

    private fun dead(id: String) = ProbeStat(NodeId(id), wifi, 0.0, 0, 2, 2, ProbeStage.TLS, null)

    private val pool = listOf(
        node("nl1", "nl", 1),
        node("nl2", "NL", 1, 2),
        node("de1", "DE", 1),
        node("de2", "DE", 1),
        node("fi1", "FI", 1),
        node("x1", null, 1),
        node("off", "NL", 1),
        node("broken", "NL", 1, capability = Capability.UNSUPPORTED),
        node("far", "JP", 3),
    )
    private val subs = listOf(sub(1, "Один"), sub(2, null), sub(3, "Три"))

    @Test
    fun oneFailureAfterSuccessStillShowsTheLastRtt() {
        val suspect = ok("nl1", 80.0).copy(fail = 1, failStreak = 1)
        val stats = mapOf(NodeId("nl1") to suspect, NodeId("nl2") to ok("nl2", 60.0).copy(fail = 2, failStreak = 2))
        val data = LocationListBuilder.build(pool, subs, PoolSelection(), stats, probing = emptySet(), network = wifi)
        val nl = data.countries.single { it.iso == "NL" }.nodes.associateBy { it.id }
        assertEquals(ProbeState.OK, nl.getValue(NodeId("nl1")).state)
        assertEquals(80, nl.getValue(NodeId("nl1")).rttMs)
        assertEquals(ProbeState.DEAD, nl.getValue(NodeId("nl2")).state)
    }

    @Test
    fun groupsByCountryWithStatesAndBestRtt() {
        val stats = mapOf(
            NodeId("nl1") to ok("nl1", 80.4), NodeId("nl2") to ok("nl2", 40.6),
            NodeId("de1") to dead("de1"), NodeId("de2") to dead("de2"),
            NodeId("off") to ok("off", 5.0),
        )
        val sel = PoolSelection(SetId(4), setOf(SubId(1), SubId(2)), LocationChoice.Country("NL"), setOf(NodeId("off")))
        val data = LocationListBuilder.build(pool, subs, sel, stats, probing = setOf(NodeId("fi1")), network = wifi)

        assertEquals(listOf("DE", "FI", "NL"), data.countries.map { it.iso })
        val nl = data.countries.single { it.iso == "NL" }
        assertEquals(2, nl.nodeCount)
        assertEquals(41, nl.bestRttMs)
        assertEquals(ProbeState.OK, nl.state)
        assertEquals(listOf("Один", null), nl.nodes.single { it.id == NodeId("nl2") }.subscriptions.map { it.title })
        assertEquals(ProbeState.DEAD, data.countries.single { it.iso == "DE" }.state)
        assertNull(data.countries.single { it.iso == "DE" }.bestRttMs)
        assertEquals(ProbeState.CHECKING, data.countries.single { it.iso == "FI" }.state)
        assertEquals(listOf(NodeId("x1")), data.other.map { it.id })
        assertEquals(ProbeState.UNKNOWN, data.other.single().state)
        assertNull(data.pinnedOutsideSet)
        assertEquals(LocationChoice.Country("NL"), data.choice)
    }

    @Test
    fun pinOutsideTheSetIsReportedSeparately() {
        val sel = PoolSelection(SetId(4), setOf(SubId(1)), LocationChoice.Node(NodeId("far")))
        val data = LocationListBuilder.build(pool, subs, sel, emptyMap(), emptySet(), null)
        val pin = data.pinnedOutsideSet!!
        assertEquals(NodeId("far"), pin.id)
        assertEquals(false, pin.inActiveSet)
        assertEquals(listOf("Три"), pin.subscriptions.map { it.title })
        assertEquals(null, data.countries.firstOrNull { it.iso == "JP" })

        val inside = LocationListBuilder.build(pool, subs, sel.copy(choice = LocationChoice.Node(NodeId("nl1"))), emptyMap(), emptySet(), null)
        assertNull(inside.pinnedOutsideSet)
    }

    @Test
    fun sourceFollowsTheNetworkStatsAndProbing() = runTest {
        val probing = MutableStateFlow(emptySet<NodeId>())
        val network = MutableStateFlow<NetworkKey?>(null)
        val source = LocationListSource(
            nodes = flowOf(pool), subscriptions = flowOf(subs), selection = flowOf(PoolSelection()),
            stats = { key -> flowOf(if (key == wifi) mapOf(NodeId("fi1") to ok("fi1", 12.0)) else emptyMap()) },
            network = network, probing = probing,
        )
        assertEquals(ProbeState.UNKNOWN, source.data.first().countries.single { it.iso == "FI" }.state)
        network.value = wifi
        assertEquals(12, source.data.first().countries.single { it.iso == "FI" }.bestRttMs)
        probing.value = setOf(NodeId("fi1"))
        assertEquals(ProbeState.CHECKING, source.data.first().countries.single { it.iso == "FI" }.state)
    }
}
