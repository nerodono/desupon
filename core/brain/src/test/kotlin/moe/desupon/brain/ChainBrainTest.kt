@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.plan.PlannerConfig
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.Tier
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ChainBrainTest {
    private val s1 = SubId(1)
    private val s2 = SubId(2)
    private val s3 = SubId(3)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Node.inSub(id: SubId) = copy(subs = setOf(id))

    private fun Harness.chain(subs: List<SubId>, choice: LocationChoice = LocationChoice.Any) {
        selection.selection.value = PoolSelection(choice = choice, chain = subs)
    }

    private fun Harness.rtt(vararg pairs: Pair<Node, Int>) {
        for ((n, ms) in pairs) prober.rtt[n.tag] = ms
    }

    private fun Harness.assertOn(node: Node, tier: Tier? = null) {
        val c = brain.state.value as? ConnState.Connected ?: fail("expected Connected, was ${brain.state.value}")
        assertEquals(node.displayName, c.node.displayName)
        tier?.let { assertEquals(it, c.tier) }
        assertEquals(node.tag, libbox.selected)
    }

    @Test
    fun `the first chain step with a working node wins, failing over step by step`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val b = Nodes.ss("b").inSub(s2)
        val c = Nodes.ss("c").inSub(s3)
        val h = Harness(this, listOf(a, b, c))
        h.alive(a, b, c)
        h.rtt(a to 90, b to 50, c to 10)
        h.chain(listOf(s1, s2))
        h.connectOn(WIFI)
        h.assertOn(a)
        assertEquals(ChainStep.Sub(0, s1), h.brain.activeStep.value)

        h.libbox.traffic(true)
        h.kill(a)
        advance(48_000)
        h.assertOn(b)
        assertEquals(ChainStep.Sub(1, s2), h.brain.activeStep.value)

        h.kill(b)
        advance(48_000)
        h.assertOn(c)
        assertEquals(ChainStep.Rest, h.brain.activeStep.value)
        assertEquals(1, h.libbox.reloads.size)

        h.brain.disconnect()
        assertNull(h.brain.activeStep.value)
    }

    @Test
    fun `a later step passing first does not win over an unprobed earlier step`() = runTest {
        val first = (1..4).map { Nodes.ss("f$it").inSub(s1) }
        val second = (1..20).map { Nodes.ss("o$it").inSub(s2) }
        val h = Harness(this, first + second)
        h.alive(*(first + second).toTypedArray())
        h.memory.map[WIFI] = NetworkMemory(WIFI, NetworkMode.OPEN, second[0].id, WALL_BASE)
        h.chain(listOf(s1, s2))
        val gate = CompletableDeferred<Unit>()
        h.prober.tagGate = first[0].tag to gate

        val connecting = launch { h.connectOn(WIFI) }
        runCurrent()
        assertTrue(h.brain.state.value is ConnState.Connecting, "the first step is still out: ${h.brain.state.value}")

        gate.complete(Unit)
        runCurrent()
        connecting.join()
        assertEquals(ChainStep.Sub(0, s1), h.brain.activeStep.value)
        assertTrue(h.libbox.selected in first.map { it.tag }, "selected ${h.libbox.selected}")
    }

    @Test
    fun `no chain publishes no step`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val h = Harness(this, listOf(a))
        h.alive(a)
        h.connectOn(WIFI)
        h.assertOn(a)
        assertNull(h.brain.activeStep.value)
    }

    @Test
    fun `an earlier step coming back is returned to through the upward hysteresis`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val c = Nodes.ss("c").inSub(s3)
        val h = Harness(this, listOf(a, c))
        h.alive(c)
        h.chain(listOf(s1))
        h.connectOn(WIFI)
        h.assertOn(c)
        h.libbox.traffic(true)

        h.alive(a)
        advance(625_000)
        h.assertOn(c)
        advance(5_000)
        h.assertOn(a)
        assertEquals(ChainStep.Sub(0, s1), h.brain.activeStep.value)
    }

    @Test
    fun `network tiers order nodes inside a step, not across steps`() = runTest {
        val direct = Nodes.ss("direct").inSub(s1)
        val ru = Nodes.ss("ru", country = "RU").inSub(s1)
        val other = Nodes.ss("other").inSub(s2)
        val h = Harness(this, listOf(direct, ru, other))
        h.alive(ru, other)
        h.rtt(other to 5, ru to 80)
        h.chain(listOf(s1))
        h.connectOn(WIFI)
        h.assertOn(ru, Tier.RU)

        h.libbox.traffic(true)
        h.alive(direct)
        advance(630_000)
        h.assertOn(direct, Tier.DIRECT)
    }

    @Test
    fun `a country narrows every step`() = runTest {
        val de = Nodes.ss("de", country = "DE").inSub(s1)
        val nl2 = Nodes.ss("nl2", country = "NL").inSub(s2)
        val nl3 = Nodes.ss("nl3", country = "NL").inSub(s3)
        val h = Harness(this, listOf(de, nl2, nl3))
        h.alive(de, nl2, nl3)
        h.rtt(de to 5, nl3 to 10, nl2 to 50)
        h.chain(listOf(s1, s2), choice = LocationChoice.Country("NL"))
        h.connectOn(WIFI)
        h.assertOn(nl2)
        assertEquals(ChainStep.Sub(1, s2), h.brain.activeStep.value)
    }

    @Test
    fun `a pin bypasses the chain`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val c = Nodes.ss("c").inSub(s3)
        val h = Harness(this, listOf(a, c))
        h.alive(a, c)
        h.chain(listOf(s1), choice = LocationChoice.Node(c.id))
        h.connectOn(WIFI)
        h.assertOn(c)
        assertNull(h.brain.activeStep.value)
    }

    @Test
    fun `reordering the chain while connected selects the loaded node without a reload`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val b = Nodes.ss("b").inSub(s2)
        val h = Harness(this, listOf(a, b))
        h.alive(a, b)
        h.chain(listOf(s1, s2))
        h.connectOn(WIFI)
        h.assertOn(a)
        val startedAt = (h.brain.state.value as ConnState.Connected).connectedAtMs
        advance(5_000)

        h.chain(listOf(s2, s1))
        runCurrent()
        h.assertOn(b)
        assertEquals(ChainStep.Sub(0, s2), h.brain.activeStep.value)
        assertEquals(1, h.libbox.reloads.size)
        assertEquals(startedAt, (h.brain.state.value as ConnState.Connected).connectedAtMs)

        h.chain(emptyList())
        runCurrent()
        h.assertOn(b)
        assertNull(h.brain.activeStep.value)
    }

    @Test
    fun `a chain needing nodes not loaded probes, reloads once and selects`() = runTest {
        val a = Nodes.ss("a").inSub(s1)
        val c = Nodes.ss("c").inSub(s3)
        val h = Harness(this, listOf(a, c), plannerConfig = PlannerConfig(perTier = 1))
        h.alive(a, c)
        h.rtt(a to 90, c to 10)
        h.connectOn(WIFI)
        h.assertOn(c)
        assertTrue(a.tag !in h.libbox.reloads.single().json)
        val pools = h.prober.poolRequests.size

        h.chain(listOf(s1))
        runCurrent()
        assertEquals(pools + 1, h.prober.poolRequests.size)
        assertEquals(2, h.libbox.reloads.size)
        assertTrue(a.tag in h.libbox.reloads.last().json && c.tag in h.libbox.reloads.last().json)
        h.assertOn(a)
        assertIs<FakeLibbox.Call.Select>(h.libbox.calls[h.libbox.calls.indexOf(h.libbox.reloads.last()) + 1])
    }

    @Test
    fun `endpoints across chain steps are capped, earliest steps first`() = runTest {
        val w1 = Nodes.wg("w1").inSub(s1)
        val w2 = Nodes.wg("w2").inSub(s1)
        val w3 = Nodes.wg("w3").inSub(s2)
        val c = Nodes.ss("c").inSub(s3)
        val h = Harness(this, listOf(w1, w2, w3, c), config = BrainConfig(chainMaxEndpoints = 2))
        h.alive(w1, w2, w3, c)
        h.chain(listOf(s1, s2))
        h.connectOn(WIFI)
        val json = h.libbox.reloads.single().json
        assertTrue(w1.tag in json && w2.tag in json && c.tag in json)
        assertTrue(w3.tag !in json)
    }

    @Test
    fun `a partial probe covers the current node, its step and the top-ranked rest`() = runTest {
        val step0 = (1..5).map { Nodes.ss("p$it").inSub(s1) }
        val rest = (1..70).map { Nodes.ss("r$it").inSub(s3) }
        val h = Harness(this, step0 + rest)
        h.alive(*(step0 + rest).toTypedArray())
        h.chain(listOf(s1))
        h.connectOn(WIFI)
        advance(1_000)
        val lastFull = h.activity.lastFullAtMs
        assertNotNull(lastFull, "the connect sweep finished behind the session")

        val before = h.prober.poolRequests.size
        val partial = h.brain.probeSetPool(full = false)
        assertEquals(ProbeRequestResult.Done(1 + Pools.UI_TOP, full = false), partial)
        val json = h.prober.poolRequests.drop(before).joinToString { it.nodesJson.raw() }
        assertTrue(step0.all { it.tag in json })
        assertEquals(1 + Pools.UI_TOP, (step0 + rest).count { it.tag in json })
        assertEquals(lastFull, h.activity.lastFullAtMs, "a partial probe is not a full one")

        assertEquals(ProbeRequestResult.Done(75), h.brain.probeSetPool(full = true))
    }
}
