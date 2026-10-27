@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.probe.OffTunnelPoolProbe
import moe.desupon.brain.probe.ProbeActivity
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.PoolSelection
import moe.desupon.config.ConfigGenerator
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.probe.Throughput
import moe.desupon.model.selection.LocationChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StagedProbeBrainTest {
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Harness.assertOn(node: Node) {
        val c = brain.state.value
        assertIs<ConnState.Connected>(c, "state $c")
        assertEquals(node.displayName, c.node.displayName)
        assertEquals(node.tag, libbox.selected)
    }

    private suspend fun Harness.connect(key: NetworkKey, ipv6: Boolean = true) {
        detector.modes[key] = NetworkMode.OPEN
        brain.onEvent(netEvent(key, ipv6 = ipv6))
        brain.connect()
    }

    @Test
    fun `connect goes on after three passing nodes while the rest of the sweep finishes behind it`() = runTest {
        val nodes = (1..40).map { Nodes.ss("n$it") }
        val h = Harness(this, nodes)
        h.alive(*nodes.toTypedArray())
        val gate = CompletableDeferred<Unit>()
        h.prober.tagGate = nodes[20].tag to gate

        h.connectOn(WIFI)

        h.assertOn(nodes[0])
        assertNull(h.activity.lastFullAtMs, "the tail is still out")
        assertTrue(nodes[20].id in h.activity.probing.value, "the gated batch shows as in flight")
        assertTrue(nodes[0].id !in h.activity.probing.value)
        runCurrent()
        assertEquals(3, h.prober.poolRequests.size, "batches of 16, two at a time, the third after the first")

        gate.complete(Unit)
        advance(100)
        assertNotNull(h.activity.lastFullAtMs)
        assertEquals(40, h.stats.forNetwork(WIFI).size)
        assertTrue(h.activity.probing.value.isEmpty())
    }

    @Test
    fun `a passing last good node is enough to go on`() = runTest {
        val good = Nodes.ss("good")
        val dead = (1..15).map { Nodes.ss("d$it") }
        val later = (1..10).map { Nodes.ss("l$it") }
        val h = Harness(this, listOf(good) + dead + later)
        h.alive(good, *later.toTypedArray())
        h.memory.map[WIFI] = NetworkMemory(WIFI, NetworkMode.OPEN, good.id, WALL_BASE)
        val gate = CompletableDeferred<Unit>()
        h.prober.tagGate = later[0].tag to gate

        h.connectOn(WIFI)

        h.assertOn(good)
        assertNull(h.activity.lastFullAtMs)
        gate.complete(Unit)
        advance(100)
        assertNotNull(h.activity.lastFullAtMs)
    }

    @Test
    fun `pool probes skip AAAA without IPv6 and give usable nodes connect-based timeouts`() = runTest {
        val a = Nodes.ss("a")
        val b = Nodes.ss("b")
        val h = Harness(this, listOf(a, b))
        h.alive(a, b)
        h.stats.map[CELL to a.id] = ProbeStat(a.id, CELL, 90.0, 3, 0, 0, ProbeStage.GET204, WALL_BASE, ewmaConnectMs = 600.0, rttSamples = 3)

        h.connect(CELL, ipv6 = false)

        val request = h.prober.poolRequests.single()
        assertTrue(request.ipv4Only)
        assertEquals(3_000, request.timeoutMs)
        assertEquals(24, request.concurrency)
        assertEquals(mapOf(a.tag to 1_800), request.timeouts)

        h.brain.disconnect()
        h.connect(WIFI)
        val wifi = h.prober.poolRequests.last()
        assertTrue(!wifi.ipv4Only)
        assertEquals(5_000, wifi.timeoutMs)
        assertEquals(32, wifi.concurrency)
    }

    @Test
    fun `on a network not seen lately a throttled current node gives way to a finalist with throughput`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3")
        val h = Harness(this, listOf(d1, d2, d3))
        h.alive(d1, d2, d3)
        h.prober.rtt += mapOf(d1.tag to 50, d2.tag to 80, d3.tag to 90)
        h.prober.throughput[d1.tag] = Throughput(true, 20_000, 3_000)
        h.prober.throughput[d2.tag] = Throughput(true, 65_536, 400)
        h.prober.throughput[d3.tag] = Throughput(true, 4_000, 3_000)

        h.connectOn(WIFI)
        advance(100)

        assertEquals(listOf(d1.tag, d2.tag, d3.tag), h.prober.throughputRequests)
        h.assertOn(d2)
        val stats = h.stats.forNetwork(WIFI)
        assertTrue(stats.getValue(d1.id).throttled)
        assertEquals(0, stats.getValue(d1.id).failStreak, "throttled is not failed")
        assertTrue(stats.getValue(d3.id).throttled)
        assertEquals(1, stats.getValue(d3.id).failStreak, "starved counts a failed probe")
        assertTrue(!stats.getValue(d2.id).throttled && stats.getValue(d2.id).ewmaKbps != null)

        h.brain.disconnect()
        h.connectOn(WIFI)
        advance(100)
        assertEquals(3, h.prober.throughputRequests.size, "the network is known now")
    }

    @Test
    fun `no throughput check on a recently seen network`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.memory.map[WIFI] = NetworkMemory(WIFI, NetworkMode.OPEN, null, WALL_BASE)
        h.connectOn(WIFI)
        advance(100)
        assertTrue(h.prober.throughputRequests.isEmpty())
    }

    @Test
    fun `with the screen off a failover checks loaded nodes live but sweeps nothing`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.prober.rtt += mapOf(d1.tag to 50, d2.tag to 80)
        h.connectOn(WIFI)
        h.assertOn(d1)
        val pools = h.prober.poolRequests.size
        h.kill(d1, d2)
        h.brain.onScreenChanged(false)

        h.brain.onEvent(BrainEvent.Tick)
        advance(100)
        advance(BrainConfig().confirmDelayMs)

        assertIs<ConnState.Degraded>(h.brain.state.value)
        assertEquals(pools, h.prober.poolRequests.size)
        assertTrue(d2.tag in h.prober.live)
    }

    @Test
    fun `off-tunnel UI probe covers the top-ranked nodes plus the chosen country`() = runTest {
        val nl = (1..30).map { Nodes.ss("nl$it", country = "NL") }
        val de = (1..3).map { Nodes.ss("de$it", country = "DE") }
        val prober = FakeProber(Journal())
        val selection = FakeSelection()
        selection.selection.value = PoolSelection(choice = LocationChoice.Country("DE"))
        val clock = FakeClock(testScheduler)
        val probe = OffTunnelPoolProbe(
            prober, ConfigGenerator(), FakeNodes(nl + de), selection, FakeSettings(), FakeStats(),
            network = { netEvent(WIFI) }, protector = { true }, activity = ProbeActivity(clock), clock = clock,
        )

        assertEquals(ProbeRequestResult.Done(Pools.UI_TOP + de.size, full = false), probe.run(full = false))
        val json = prober.poolRequests.joinToString { it.nodesJson.raw() }
        assertTrue(de.all { it.tag in json })
        assertEquals(2, prober.poolRequests.size, "23 nodes in batches of 16")
    }
}
