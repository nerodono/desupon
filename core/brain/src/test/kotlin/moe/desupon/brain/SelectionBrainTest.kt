@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.plan.PlannerConfig
import moe.desupon.brain.plan.TierLabels
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SelectionBrainTest {
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Harness.assertReloadsSelected() {
        libbox.calls.forEachIndexed { i, call ->
            if (call is FakeLibbox.Call.Reload) assertIs<FakeLibbox.Call.Select>(libbox.calls.getOrNull(i + 1), "reload #$i not followed by select")
        }
    }

    private fun Harness.assertOn(node: Node) {
        val c = brain.state.value as? ConnState.Connected ?: fail("expected Connected, was ${brain.state.value}")
        assertEquals(node.displayName, c.node.displayName)
        assertEquals(node.tag, libbox.selected)
    }

    private fun Harness.rtt(vararg pairs: Pair<Node, Int>) {
        for ((n, ms) in pairs) prober.rtt[n.tag] = ms
    }

    private fun Harness.select(choice: LocationChoice = LocationChoice.Any, subs: Set<SubId>? = null, disabled: Set<moe.desupon.model.node.NodeId> = emptySet()) {
        selection.selection.value = PoolSelection(if (subs == null) SetId.ALL else SetId(7), subs, choice, disabled)
    }

    private fun Node.inSub(id: Long) = copy(subs = setOf(SubId(id)))

    @Test
    fun `the active set limits the pool to its subscriptions`() = runTest {
        val a = Nodes.ss("a").inSub(1)
        val b = Nodes.ss("b").inSub(2)
        val h = Harness(this, listOf(a, b))
        h.alive(a, b)
        h.rtt(b to 10, a to 50)
        h.select(subs = setOf(SubId(1)))
        h.connectOn(WIFI)
        h.assertOn(a)
        assertTrue(b.tag !in h.libbox.reloads.single().json)
        assertTrue(b.tag !in h.prober.poolRequests.single().nodesJson.raw())
    }

    @Test
    fun `disabled nodes never enter the pool`() = runTest {
        val a = Nodes.ss("a")
        val b = Nodes.ss("b")
        val h = Harness(this, listOf(a, b))
        h.alive(a, b)
        h.rtt(b to 10, a to 50)
        h.select(disabled = setOf(b.id))
        h.connectOn(WIFI)
        h.assertOn(a)
        assertTrue(b.tag !in h.libbox.reloads.single().json)
    }

    @Test
    fun `an empty set fails with no nodes before touching the core`() = runTest {
        val a = Nodes.ss("a").inSub(1)
        val h = Harness(this, listOf(a))
        h.alive(a)
        h.select(subs = emptySet())
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.NO_NODES), h.brain.state.value)
        assertTrue(h.libbox.calls.all { it == FakeLibbox.Call.Stop })
    }

    @Test
    fun `country choice keeps selection and failover inside the country`() = runTest {
        val nl1 = Nodes.ss("nl1", country = "NL")
        val de1 = Nodes.ss("de1", country = "DE")
        val nl2 = Nodes.ss("nl2", country = "NL")
        val h = Harness(this, listOf(nl1, de1, nl2))
        h.alive(nl1, de1, nl2)
        h.rtt(de1 to 5, nl1 to 10, nl2 to 20)
        h.select(LocationChoice.Country("NL"))
        h.connectOn(WIFI)
        h.assertOn(nl1)
        assertTrue(de1.tag !in h.libbox.reloads.single().json)

        h.libbox.traffic(true)
        h.kill(nl1)
        advance(48_000)
        h.assertOn(nl2)

        h.kill(nl2)
        advance(48_000)
        val degraded = assertIs<ConnState.Degraded>(h.brain.state.value)
        assertEquals(FailReason.COUNTRY_UNAVAILABLE, degraded.reason)
        assertEquals("NL", degraded.detail)
        assertTrue(h.libbox.calls.none { it is FakeLibbox.Call.Select && it.tag == de1.tag })
    }

    @Test
    fun `a country without nodes in the active set fails with its code before touching the core`() = runTest {
        val nl = Nodes.ss("nl", country = "NL").inSub(1)
        val fi = Nodes.ss("fi", country = "FI").inSub(2)
        val h = Harness(this, listOf(nl, fi))
        h.alive(nl, fi)
        h.select(LocationChoice.Country("FI"), subs = setOf(SubId(1)))
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "FI"), h.brain.state.value)
        assertTrue(h.libbox.calls.all { it == FakeLibbox.Call.Stop })
    }

    @Test
    fun `a country whose nodes all fail at connect fails instead of idling`() = runTest {
        val nl = Nodes.ss("nl", country = "NL")
        val de = Nodes.ss("de", country = "DE")
        val h = Harness(this, listOf(nl, de))
        h.alive(de)
        h.select(LocationChoice.Country("NL"))
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "NL"), h.brain.state.value)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }

    @Test
    fun `a pinned node from outside the active set is used and never switched away from`() = runTest {
        val d1 = Nodes.ss("d1").inSub(1)
        val d2 = Nodes.ss("d2").inSub(9)
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 90)
        h.select(LocationChoice.Node(d2.id), subs = setOf(SubId(1)))
        h.connectOn(WIFI)
        h.assertOn(d2)
        assertTrue(d1.tag !in h.libbox.reloads.single().json)

        h.libbox.traffic(true)
        h.kill(d2)
        advance(48_000)
        val degraded = assertIs<ConnState.Degraded>(h.brain.state.value)
        assertEquals(FailReason.NODE_UNAVAILABLE, degraded.reason)
        assertEquals(d2.id.hash, degraded.detail)
        assertEquals(d2.tag, h.libbox.selected)

        h.alive(d2)
        advance(30_000)
        h.assertOn(d2)
        assertTrue(h.libbox.calls.none { it is FakeLibbox.Call.Select && it.tag == d1.tag })
    }

    @Test
    fun `a pin dead at connect or missing from the pool fails with its code`() = runTest {
        val d1 = Nodes.ss("d1")
        val gone = Nodes.ss("gone")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.select(LocationChoice.Node(gone.id))
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.NODE_UNAVAILABLE, gone.id.hash), h.brain.state.value)
        assertTrue(h.libbox.calls.all { it == FakeLibbox.Call.Stop })

        val d2 = Nodes.ss("d2")
        val h2 = Harness(this, listOf(d1, d2))
        h2.alive(d1)
        h2.select(LocationChoice.Node(d2.id))
        h2.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.NODE_UNAVAILABLE, d2.id.hash), h2.brain.state.value)
    }

    @Test
    fun `a pin bypasses the planner's transport filter`() = runTest {
        val wifiOnly = Nodes.ss("w", labels = setOf(TierLabels.ONLY_WIFI))
        val h = Harness(this, listOf(wifiOnly))
        h.alive(wifiOnly)
        h.select(LocationChoice.Node(wifiOnly.id))
        h.connectOn(CELL)
        h.assertOn(wifiOnly)
    }

    @Test
    fun `changing the choice to a loaded node selects it without reload`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.assertOn(d1)
        val startedAt = (h.brain.state.value as ConnState.Connected).connectedAtMs
        advance(5_000)

        h.select(LocationChoice.Node(d2.id))
        runCurrent()
        h.assertOn(d2)
        assertEquals(1, h.libbox.reloads.size)
        assertEquals(startedAt, (h.brain.state.value as ConnState.Connected).connectedAtMs)
    }

    @Test
    fun `changing the choice to nodes not loaded reloads once and selects`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3", country = "DE")
        val h = Harness(this, listOf(d1, d2, d3), plannerConfig = PlannerConfig(perTier = 2))
        h.alive(d1, d2, d3)
        h.rtt(d1 to 10, d2 to 20, d3 to 30)
        h.connectOn(WIFI)
        assertTrue(d3.tag !in h.libbox.reloads.single().json)

        h.select(LocationChoice.Country("DE"))
        runCurrent()
        assertEquals(2, h.libbox.reloads.size)
        assertTrue(d3.tag in h.libbox.reloads.last().json)
        h.assertOn(d3)
        h.assertReloadsSelected()
    }

    @Test
    fun `narrowing to the current node's country keeps it without a select`() = runTest {
        val de = Nodes.ss("de", country = "DE")
        val nl = Nodes.ss("nl", country = "NL")
        val h = Harness(this, listOf(de, nl))
        h.alive(de, nl)
        h.rtt(de to 5, nl to 10)
        h.connectOn(WIFI)
        h.assertOn(de)
        val selects = h.libbox.calls.count { it is FakeLibbox.Call.Select }

        h.select(LocationChoice.Country("DE"))
        runCurrent()
        h.assertOn(de)
        assertEquals(selects, h.libbox.calls.count { it is FakeLibbox.Call.Select })
    }

    @Test
    fun `disabling the current node switches away from it`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.select(disabled = setOf(d1.id))
        runCurrent()
        h.assertOn(d2)
        assertEquals(1, h.libbox.reloads.size)
    }

    @Test
    fun `switching to an empty set while connected fails with no nodes`() = runTest {
        val d1 = Nodes.ss("d1").inSub(1)
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        h.select(subs = setOf(SubId(2)))
        runCurrent()
        assertEquals(ConnState.Failed(FailReason.NO_NODES), h.brain.state.value)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }

    @Test
    fun `set probe covers the whole set while a country is chosen and leaves loaded WireGuard to the box`() = runTest {
        val nl = Nodes.ss("nl", country = "NL")
        val w1 = Nodes.wg("w1", country = "NL")
        val de = Nodes.ss("de", country = "DE")
        val h = Harness(this, listOf(nl, w1, de))
        h.alive(nl, w1, de)
        h.select(LocationChoice.Country("NL"))
        h.connectOn(WIFI)
        assertTrue(de.id !in h.stats.forNetwork(WIFI))
        assertNull(h.activity.lastFullAtMs, "a country-narrowed connect probe is not a full one")

        val gate = CompletableDeferred<Unit>()
        h.prober.poolGate = gate
        val result = async { h.brain.probeSetPool() }
        runCurrent()
        assertEquals(setOf(nl.id, w1.id, de.id), h.activity.probing.value)
        gate.complete(Unit)
        runCurrent()

        assertEquals(ProbeRequestResult.Done(3), result.await())
        assertTrue(h.activity.probing.value.isEmpty())
        assertNotNull(h.activity.lastFullAtMs)
        val json = h.prober.poolRequests.last().nodesJson.raw()
        assertTrue(de.tag in json && nl.tag in json && w1.tag !in json)
        assertTrue("probeLive:${w1.tag}" in h.journal.entries)
        assertTrue(de.id in h.stats.forNetwork(WIFI))
        h.assertOn(nl)
    }

    @Test
    fun `set probe without a session returns null`() = runTest {
        val h = Harness(this, listOf(Nodes.ss("a")))
        assertNull(h.brain.probeSetPool())
    }

    @Test
    fun `network mode is published while a session runs`() = runTest {
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(r1))
        h.alive(r1)
        assertNull(h.brain.networkMode.value)
        h.connectOn(CELL, NetworkMode.WHITELIST)
        assertEquals(NetworkMode.WHITELIST, h.brain.networkMode.value)
        h.brain.disconnect()
        assertNull(h.brain.networkMode.value)
    }
}
