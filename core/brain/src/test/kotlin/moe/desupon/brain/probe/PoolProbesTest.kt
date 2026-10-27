@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain.probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FakeClock
import moe.desupon.brain.FakeNodes
import moe.desupon.brain.FakeProber
import moe.desupon.brain.FakeSelection
import moe.desupon.brain.FakeSettings
import moe.desupon.brain.FakeStats
import moe.desupon.brain.Harness
import moe.desupon.brain.Journal
import moe.desupon.brain.Nodes
import moe.desupon.brain.WIFI
import moe.desupon.brain.netEvent
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.tag
import moe.desupon.config.ConfigGenerator
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.network.Transport
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PoolProbesTest {
    @Test
    fun `requests pass every 15 s, forced ones every 10 s, and only every 2 min ask for the whole pool`() = runTest {
        val clock = FakeClock(testScheduler)
        val activity = ProbeActivity(clock)
        val asked = mutableListOf<Boolean>()
        val probes = PoolProbes({ full -> asked += full; activity.track(emptyList(), full = full) { ProbeRequestResult.Done(2, full) } }, activity, clock, backgroundScope)

        assertEquals(ProbeRequestResult.Done(2), probes.request())
        assertEquals(ProbeRequestResult.Throttled(15_000), probes.request())
        assertEquals(ProbeRequestResult.Throttled(10_000), probes.request(forced = true))
        advanceTimeBy(10_000)
        assertEquals(ProbeRequestResult.Done(2, full = false), probes.request(forced = true))
        advanceTimeBy(5_000)
        assertEquals(ProbeRequestResult.Throttled(10_000), probes.request(), "a partial probe counts too")
        advanceTimeBy(10_000)
        assertEquals(ProbeRequestResult.Done(2, full = false), probes.request())
        advanceTimeBy(80_000)
        assertEquals(ProbeRequestResult.Done(2, full = false), probes.request())
        advanceTimeBy(15_000)
        assertEquals(ProbeRequestResult.Done(2), probes.request())
        assertEquals(listOf(true, false, false, false, true), asked)
    }

    @Test
    fun `on cellular the whole pool is asked for only every 5 min`() = runTest {
        val clock = FakeClock(testScheduler)
        val activity = ProbeActivity(clock)
        val asked = mutableListOf<Boolean>()
        val probes = PoolProbes(
            { full -> asked += full; activity.track(emptyList(), full = full) { ProbeRequestResult.Done(1, full) } },
            activity, clock, backgroundScope, transport = { Transport.CELLULAR },
        )
        probes.request()
        advanceTimeBy(120_000)
        probes.request()
        advanceTimeBy(180_000)
        probes.request()
        assertEquals(listOf(true, false, true), asked)
    }

    @Test
    fun `a failed request does not throttle the next`() = runTest {
        val clock = FakeClock(testScheduler)
        val activity = ProbeActivity(clock)
        var result: ProbeRequestResult = ProbeRequestResult.NoNetwork
        val probes = PoolProbes({ result }, activity, clock, backgroundScope)
        assertEquals(ProbeRequestResult.NoNetwork, probes.request())
        result = ProbeRequestResult.Done(1)
        assertEquals(ProbeRequestResult.Done(1), probes.request())
    }

    @Test
    fun `a second request while one runs is busy, and the work survives its caller`() = runTest {
        val clock = FakeClock(testScheduler)
        val activity = ProbeActivity(clock)
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val probes = PoolProbes({
            gate.await()
            finished = true
            ProbeRequestResult.Done(1)
        }, activity, clock, backgroundScope)

        val first = backgroundScope.async { probes.request() }
        runCurrent()
        assertEquals(ProbeRequestResult.Busy, probes.request(forced = true))
        first.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(finished)
        advanceTimeBy(10_000)
        assertEquals(ProbeRequestResult.Done(1), probes.request(forced = true))
    }

    @Test
    fun `a connected brain takes the request, otherwise the off-tunnel probe does`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        var offTunnel = 0
        val probes = PoolProbes({ offTunnel++; ProbeRequestResult.Done(0) }, h.activity, h.clock, backgroundScope)
        probes.attach(h.brain)

        assertEquals(ProbeRequestResult.Done(0), probes.request())
        assertEquals(1, offTunnel, "no session yet")

        h.connectOn(WIFI)
        val pools = h.prober.poolRequests.size
        advanceTimeBy(60_000)
        assertEquals(ProbeRequestResult.Done(1), probes.request())
        assertEquals(pools + 1, h.prober.poolRequests.size)
        assertEquals(1, offTunnel)

        probes.detach(h.brain)
        advanceTimeBy(60_000)
        probes.request()
        assertEquals(2, offTunnel)
    }

    @Test
    fun `off-tunnel probe covers the set and an outside pin on the underlying network and records stats`() = runTest {
        val clock = FakeClock(testScheduler)
        val journal = Journal()
        val prober = FakeProber(journal)
        val inSet = Nodes.ss("a").copy(subs = setOf(SubId(1)))
        val outside = Nodes.ss("b").copy(subs = setOf(SubId(2)))
        val other = Nodes.ss("c").copy(subs = setOf(SubId(2)))
        prober.alive += inSet.tag
        val selection = FakeSelection()
        selection.selection.value = PoolSelection(SetId(4), setOf(SubId(1)), LocationChoice.Node(outside.id))
        val stats = FakeStats()
        val activity = ProbeActivity(clock)
        val net = netEvent(WIFI)
        var network: moe.desupon.brain.port.BrainEvent.NetworkChanged? = null
        val probe = OffTunnelPoolProbe(
            prober, ConfigGenerator(), FakeNodes(listOf(inSet, outside, other)), selection, FakeSettings(), stats,
            network = { network }, protector = { true }, activity = activity, clock = clock,
        )

        assertEquals(ProbeRequestResult.NoNetwork, probe.run())
        network = net
        assertEquals(ProbeRequestResult.Done(2), probe.run())
        val request = prober.poolRequests.single()
        assertEquals("192.0.2.53", request.dnsCsv)
        assertEquals(net.network, request.network)
        val json = request.nodesJson.raw()
        assertTrue(inSet.tag in json && outside.tag in json && other.tag !in json)
        val recorded = stats.forNetwork(WIFI)
        assertEquals(1, recorded.getValue(inSet.id).ok)
        assertEquals(1, recorded.getValue(outside.id).failStreak)
        assertTrue(activity.lastFullAtMs != null)

        selection.selection.value = PoolSelection(SetId(4), emptySet())
        assertIs<ProbeRequestResult.NoNodes>(probe.run())
    }

    @Test
    fun `off-tunnel probe skips AAAA on an IPv6 network when the user turned IPv6 off`() = runTest {
        val clock = FakeClock(testScheduler)
        val prober = FakeProber(Journal())
        val settings = FakeSettings()
        val probe = OffTunnelPoolProbe(
            prober, ConfigGenerator(), FakeNodes(listOf(Nodes.ss("a"))), FakeSelection(), settings, FakeStats(),
            network = { netEvent(WIFI, ipv6 = true) }, protector = { true }, activity = ProbeActivity(clock), clock = clock,
        )

        probe.run()
        settings.settings.value = ConnectionSettings(dns = DnsSettings(strategy = DnsStrategy.IPV4_ONLY))
        probe.run()
        assertEquals(listOf(false, true), prober.poolRequests.map { it.ipv4Only })
    }
}
