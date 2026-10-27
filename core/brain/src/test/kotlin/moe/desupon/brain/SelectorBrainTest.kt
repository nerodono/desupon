@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.plan.PlannerConfig
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.config.LocalRuleSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import moe.desupon.config.ConfigGenerator
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.Tier
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import moe.desupon.model.status.ConnInfo
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SelectorBrainTest {
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Each reload must be followed by an explicit select, or the selector keeps its cached member. */
    private fun Harness.assertReloadsSelected() {
        libbox.calls.forEachIndexed { i, call ->
            if (call is FakeLibbox.Call.Reload) {
                val next = libbox.calls.getOrNull(i + 1)
                assertIs<FakeLibbox.Call.Select>(next, "reload #$i not followed by select: ${libbox.calls}")
                assertEquals("proxy", next.group)
            }
        }
    }

    private fun Harness.connected(): ConnState.Connected =
        brain.state.value as? ConnState.Connected ?: fail("expected Connected, was ${brain.state.value}")

    private fun Harness.assertOn(node: Node, tier: Tier? = null) {
        val c = connected()
        assertEquals(node.displayName, c.node.displayName)
        tier?.let { assertEquals(it, c.tier) }
        assertEquals(node.tag, libbox.selected)
    }

    private fun Harness.rtt(vararg pairs: Pair<Node, Int>) {
        for ((n, ms) in pairs) prober.rtt[n.tag] = ms
    }

    @Test
    fun `connect raises the TUN before detecting and probing, then loads the plan, selects the seed and probes it live`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d2, r1, d1))
        h.alive(d1, d2, r1)
        h.rtt(d1 to 50, d2 to 80, r1 to 40)

        h.connectOn(WIFI)

        assertEquals(listOf("placeholder", "detect", "probePool", "reload", "select:${d1.tag}", "probeLive:${d1.tag}"), h.journal.entries)
        h.assertOn(d1, Tier.DIRECT)
        assertEquals(WALL_BASE, h.connected().connectedAtMs)
        val request = h.prober.poolRequests.single()
        assertEquals("192.0.2.53", request.dnsCsv)
        assertEquals(netEvent(WIFI).network, request.network)
        val json = h.libbox.reloads.single().json
        listOf(d1, d2, r1).forEach { assertTrue(it.tag in json) }
        assertEquals(d1.id, h.memory.map.getValue(WIFI).lastGood)
        assertEquals(NetworkMode.OPEN, h.memory.map.getValue(WIFI).mode)
        assertEquals(3, h.stats.forNetwork(WIFI).size)
        h.assertReloadsSelected()
    }

    @Test
    fun `state goes through connecting with the detected mode`() = runTest {
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(r1))
        h.alive(r1)
        h.brain.state.test {
            assertEquals(ConnState.Idle, awaitItem())
            h.connectOn(CELL, NetworkMode.WHITELIST)
            assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), awaitItem())
            assertEquals(ConnState.Connecting(NetworkMode.WHITELIST), awaitItem())
            val c = assertIs<ConnState.Connected>(awaitItem())
            assertEquals(Tier.RU, c.tier)
            expectNoEvents()
        }
    }

    @Test
    fun `seed failing its live probe is replaced at once`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.prober.liveDead += d1.tag
        h.brain.state.test {
            skipItems(1)
            h.connectOn(WIFI)
            skipItems(2)
            val sw = assertIs<ConnState.Switching>(awaitItem())
            assertEquals(d1.displayName, sw.from.displayName)
            assertEquals(d2.displayName, sw.to.displayName)
            assertIs<ConnState.Connected>(awaitItem())
        }
        h.assertOn(d2)
        assertEquals(1, h.libbox.reloads.size)
        h.assertReloadsSelected()
    }

    @Test
    fun `two consecutive GET204 failures switch within the tier, same country first`() = runTest {
        val d1 = Nodes.ss("d1", country = "NL")
        val de = Nodes.ss("de", country = "DE")
        val d2 = Nodes.ss("d2", country = "NL")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, de, d2, r1))
        h.alive(d1, de, d2, r1)
        h.rtt(d1 to 50, de to 60, d2 to 80)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.kill(d1)

        advance(45_000)
        h.assertOn(d1)
        advance(3_000)

        h.assertOn(d2, Tier.DIRECT)
        assertEquals(1, h.libbox.reloads.size)
        assertEquals(d2.id, h.memory.map.getValue(WIFI).lastGood)
    }

    @Test
    fun `a single failure is confirmed before acting`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.kill(d1)
        advance(45_000)
        h.alive(d1)
        advance(3_000)
        h.assertOn(d1)
        h.kill(d1)
        advance(42_000)
        h.alive(d1)
        advance(3_000)
        h.assertOn(d1)
        assertEquals(1, h.libbox.calls.count { it is FakeLibbox.Call.Select })
    }

    @Test
    fun `exhausted tier falls through to the next tier`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, d2, r1))
        h.alive(d1, d2, r1)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.kill(d1, d2)
        advance(48_000)
        h.assertOn(r1, Tier.RU)
        assertEquals(1, h.libbox.reloads.size)
    }

    @Test
    fun `more than three switches in five minutes pins Unstable until the window clears`() = runTest {
        val nodes = (1..5).map { Nodes.ss("n$it") }
        val h = Harness(this, nodes)
        nodes.forEachIndexed { i, n -> h.rtt(n to 10 * (i + 1)) }
        h.alive(*nodes.toTypedArray())
        h.connectOn(WIFI)
        h.libbox.traffic(true)

        for (k in 0 until 3) {
            h.kill(nodes[k])
            // Past the first minute each switch waits for one more probe after an outbound reset.
            advance(if (k == 0) 48_000 else 51_000)
            h.assertOn(nodes[k + 1])
        }
        h.kill(nodes[3], nodes[4])
        h.brain.state.test {
            skipItems(1)
            advance(51_000)
            val unstable = assertIs<ConnState.Unstable>(awaitItem())
            assertEquals("n5", unstable.best.displayName)
            assertEquals(nodes[4].tag, h.libbox.selected)
            val selects = h.libbox.calls.count { it is FakeLibbox.Call.Select }

            advance(90_000)
            expectNoEvents()
            assertEquals(selects, h.libbox.calls.count { it is FakeLibbox.Call.Select })

            h.alive(nodes[4])
            // First switch was at 48 s; the window clears at 348 s.
            advanceTimeBy(348_000 - testScheduler.currentTime - 1)
            runCurrent()
            expectNoEvents()
            advance(1)
            assertIs<ConnState.Connected>(expectMostRecentItem())
            assertEquals(348_000L, testScheduler.currentTime)
        }
        h.assertOn(nodes[4])
    }

    @Test
    fun `upward switch waits for 10 minutes on the current node and 3 passes over 3 minutes`() = runTest {
        val d1 = Nodes.ss("d1")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, r1))
        h.alive(r1)
        h.connectOn(WIFI)
        h.assertOn(r1, Tier.RU)
        h.libbox.traffic(true)

        h.alive(d1)
        advance(625_000)
        h.assertOn(r1)
        advance(5_000)
        h.assertOn(d1, Tier.DIRECT)
    }

    @Test
    fun `upward candidate needs its passes to span 3 minutes`() = runTest {
        val d1 = Nodes.ss("d1")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, r1))
        h.alive(r1)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        advance(480_000)
        h.alive(d1)
        // Passes at 495, 540, 585, 630 s: 10 minutes reached but only 135 s of passes.
        advance(170_000)
        h.assertOn(r1)
        advance(25_000)
        h.assertOn(d1)
    }

    @Test
    fun `a failed upward check restarts the count`() = runTest {
        val d1 = Nodes.ss("d1")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, r1))
        h.alive(r1, d1)
        h.prober.liveDead += d1.tag
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.prober.liveDead.clear()
        advance(580_000)
        h.prober.liveDead += d1.tag
        advance(10_000)
        h.prober.liveDead.clear()
        // Passes restart at 630 s and span 180 s at 810 s.
        advance(215_000)
        h.assertOn(r1)
        advance(5_000)
        h.assertOn(d1)
    }

    @Test
    fun `passive stall on proxied connections triggers the check without active probing`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.kill(d1)
        advance(100_000)
        val probesBefore = h.prober.live.size

        for (i in 0..12) {
            h.libbox.connections.value = listOf(conn("c", listOf("proxy", d1.tag), up = 1_000L * (i + 1), down = 500))
            advance(1_000)
        }
        assertTrue(h.prober.live.size > probesBefore)
        advance(6_000)
        h.assertOn(d2)
    }

    @Test
    fun `direct traffic stalls are ignored`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        val probesBefore = h.prober.live.size
        for (i in 0..30) {
            h.libbox.connections.value = listOf(conn("c", listOf("direct"), up = 1_000L * (i + 1), down = 0))
            advance(1_000)
        }
        assertEquals(probesBefore, h.prober.live.size)
    }

    @Test
    fun `external stall and tick events force a check`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        h.brain.onEvent(BrainEvent.PassiveStall)
        runCurrent()
        h.brain.onEvent(BrainEvent.Tick)
        runCurrent()
        assertEquals(3, h.prober.live.size)
    }

    @Test
    fun `active checks run only while traffic flows and the screen is on`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        advance(300_000)
        assertEquals(1, h.prober.live.size)

        h.libbox.traffic(true)
        advance(15_000)
        assertEquals(2, h.prober.live.size)
        advance(45_000)
        assertEquals(3, h.prober.live.size)

        h.brain.onScreenChanged(false)
        advance(300_000)
        assertEquals(3, h.prober.live.size)

        h.brain.onScreenChanged(true)
        advance(45_000)
        assertEquals(4, h.prober.live.size)
    }

    @Test
    fun `offline still connects, degrades, and recovers once a node answers`() = runTest {
        val r1 = Nodes.ss("r1", country = "RU")
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2, r1))
        h.connectOn(WIFI, NetworkMode.OFFLINE)

        assertEquals(1, h.libbox.reloads.size)
        assertEquals(r1.tag, h.libbox.selected)
        val degraded = assertIs<ConnState.Degraded>(h.brain.state.value)
        assertEquals("r1", degraded.node.displayName)

        h.alive(d1)
        advance(30_000)
        h.assertOn(d1, Tier.DIRECT)
        assertEquals(1, h.libbox.reloads.size)
        h.assertReloadsSelected()
    }

    @Test
    fun `degraded retries back off`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.connectOn(WIFI, NetworkMode.OFFLINE)
        assertIs<ConnState.Degraded>(h.brain.state.value)
        val at = mutableListOf<Long>()
        repeat(4) {
            val before = h.prober.live.size
            while (h.prober.live.size == before) advance(1_000)
            at += testScheduler.currentTime
        }
        assertEquals(listOf(30_000L, 90_000L, 210_000L, 450_000L), at)
    }

    @Test
    fun `whole tier dead re-probes the pool and reloads only for new nodes`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3")
        val h = Harness(this, listOf(d1, d2, d3), plannerConfig = PlannerConfig(perTier = 2))
        h.alive(d1, d2, d3)
        h.rtt(d1 to 10, d2 to 20, d3 to 30)
        h.connectOn(WIFI)
        assertTrue(d3.tag !in h.libbox.reloads.single().json)
        h.libbox.traffic(true)
        h.kill(d1, d2)
        advance(48_000)

        h.assertOn(d3)
        assertEquals(2, h.prober.poolRequests.size)
        assertEquals(2, h.libbox.reloads.size)
        h.assertReloadsSelected()
    }

    @Test
    fun `network change without a top-K change probes the loaded nodes and never reloads`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val r1 = Nodes.ss("r1", country = "RU")
        val h = Harness(this, listOf(d1, d2, r1))
        h.alive(d1, d2, r1)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.journal.entries.clear()

        h.brain.onEvent(netEvent(CELL))
        runCurrent()
        h.brain.onEvent(netEvent(WIFI_B))
        runCurrent()
        h.brain.onEvent(netEvent(WIFI_B))
        runCurrent()

        assertEquals(1, h.libbox.reloads.size)
        assertEquals(2, h.journal.entries.count { it == "detect" })
        assertEquals(0, h.journal.entries.count { it == "probePool" })
        assertEquals(setOf(d1.tag, d2.tag, r1.tag), h.journal.entries.filter { it.startsWith("probeLive:") }.map { it.removePrefix("probeLive:") }.toSet())
        h.assertOn(d1)
        assertEquals(d1.id, h.memory.map.getValue(CELL).lastGood)
        assertEquals(d1.id, h.memory.map.getValue(WIFI_B).lastGood)
    }

    @Test
    fun `network change moves to the last good node of the new network without reload`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.memory.map[CELL] = NetworkMemory(CELL, NetworkMode.OPEN, d2.id, WALL_BASE)
        h.connectOn(WIFI)
        h.assertOn(d1)
        h.brain.onEvent(netEvent(CELL))
        runCurrent()
        h.assertOn(d2)
        assertEquals(1, h.libbox.reloads.size)
    }

    @Test
    fun `network change needing new nodes reloads once and reselects`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3")
        val h = Harness(this, listOf(d1, d2, d3), plannerConfig = PlannerConfig(perTier = 2))
        h.alive(d1, d2, d3)
        h.rtt(d1 to 10, d2 to 20, d3 to 30)
        h.connectOn(WIFI)
        val startedAt = h.connected().connectedAtMs
        advance(60_000)

        h.kill(d1, d2)
        h.brain.onEvent(netEvent(CELL))
        runCurrent()

        assertEquals(2, h.libbox.reloads.size)
        assertTrue(d3.tag in h.libbox.reloads.last().json)
        h.assertOn(d3)
        assertEquals(startedAt, h.connected().connectedAtMs)
        h.assertReloadsSelected()
    }

    @Test
    fun `split change reloads with a select and keeps the session timer`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        val startedAt = h.connected().connectedAtMs
        advance(120_000)

        h.split.rules.value = listOf(SplitRule(order = 0, kind = SplitRuleKind.DOMAIN_SUFFIX, value = "example.org", action = SplitAction.DIRECT))
        runCurrent()
        assertEquals(2, h.libbox.reloads.size)
        assertTrue("example.org" in h.libbox.reloads.last().json)

        val apps = AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank"))
        h.settings.settings.value = ConnectionSettings(apps = apps)
        runCurrent()
        assertEquals(3, h.libbox.reloads.size)
        assertEquals(apps, h.libbox.reloads.last().apps)

        h.assertOn(d1)
        assertEquals(startedAt, h.connected().connectedAtMs)
        h.assertReloadsSelected()
    }

    @Test
    fun `ipv4 only keeps IPv6 out of the tun from the placeholder on, and toggling it reloads the tun`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        val v6 = ConfigGenerator.TUN_ADDRESS.single { ':' in it }
        fun ipv4Only(on: Boolean) {
            h.settings.settings.value = ConnectionSettings(dns = DnsSettings(strategy = if (on) DnsStrategy.IPV4_ONLY else DnsStrategy.PREFER_IPV4))
        }
        ipv4Only(true)

        h.connectOn(WIFI)
        assertTrue(v6 !in h.libbox.calls.filterIsInstance<FakeLibbox.Call.Placeholder>().single().json)
        assertTrue(v6 !in h.libbox.reloads.single().json)
        assertTrue(h.prober.poolRequests.all { it.ipv4Only }, "the network has IPv6, the setting turns it off")
        val startedAt = h.connected().connectedAtMs

        ipv4Only(false)
        runCurrent()
        assertEquals(2, h.libbox.reloads.size)
        assertTrue(v6 in h.libbox.reloads.last().json)
        ipv4Only(true)
        runCurrent()
        assertEquals(3, h.libbox.reloads.size)
        assertTrue(v6 !in h.libbox.reloads.last().json)

        h.assertOn(d1)
        assertEquals(startedAt, h.connected().connectedAtMs)
        h.assertReloadsSelected()
    }

    @Test
    fun `a rule-set becoming usable reloads once, a refresh in place or an unused set does not`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.split.rules.value = listOf(SplitRule(order = 0, kind = SplitRuleKind.RULE_SET, value = "geoip-ru-blocked", action = SplitAction.PROXY))
        h.connectOn(WIFI)
        assertEquals(1, h.libbox.reloads.size)
        assertTrue("geoip-ru-blocked" !in h.libbox.reloads.last().json, "an unusable set is skipped")

        val blocked = "geoip-ru-blocked" to LocalRuleSet("/sets/geoip-ru-blocked.srs", domainBased = false)
        h.ruleSets.emit(mapOf(blocked))
        runCurrent()
        assertEquals(2, h.libbox.reloads.size)
        assertTrue("/sets/geoip-ru-blocked.srs" in h.libbox.reloads.last().json)

        h.ruleSets.emit(mapOf(blocked))
        runCurrent()
        assertEquals(2, h.libbox.reloads.size, "the same set again is a content refresh in place")
        h.ruleSets.emit(mapOf(blocked, "geosite-unused" to LocalRuleSet("/sets/geosite-unused.srs")))
        runCurrent()
        assertEquals(2, h.libbox.reloads.size, "no rule uses the new set")

        h.ruleSets.emit(mapOf("geosite-unused" to LocalRuleSet("/sets/geosite-unused.srs")))
        runCurrent()
        assertEquals(3, h.libbox.reloads.size)
        assertTrue("geoip-ru-blocked" !in h.libbox.reloads.last().json)
        h.assertOn(d1)
        h.assertReloadsSelected()
    }

    @Test
    fun `installed apps changing reloads the same config on the current node`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.brain.onAppsChanged()
        h.connectOn(WIFI)
        assertEquals(1, h.libbox.reloads.size, "nothing to reapply before a session")
        val startedAt = h.connected().connectedAtMs

        h.brain.onAppsChanged()
        runCurrent()
        assertEquals(2, h.libbox.reloads.size)
        assertEquals(h.libbox.reloads.first().json, h.libbox.reloads.last().json)
        h.assertOn(d1)
        assertEquals(startedAt, h.connected().connectedAtMs)
        h.assertReloadsSelected()
    }

    @Test
    fun `whitelist passes promote nodes to RU for that network only`() = runTest {
        val r1 = Nodes.ss("r1", country = "RU")
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3")
        val h = Harness(this, listOf(r1, d1, d2, d3))
        h.alive(d1)
        h.connectOn(CELL, NetworkMode.WHITELIST)

        assertEquals(setOf(d1.id), h.learning.map[CELL])
        h.assertOn(d1, Tier.RU)

        h.alive(d2, r1)
        h.brain.onEvent(netEvent(WIFI))
        runCurrent()
        assertNull(h.learning.map[WIFI])
        h.assertOn(d1, Tier.DIRECT)
        assertEquals(1, h.libbox.reloads.size)
    }

    @Test
    fun `learned promotion survives into the next session, whatever the mode`() = runTest {
        val r1 = Nodes.ss("r1", country = "RU")
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(r1, d1, d2))
        h.alive(d1)
        h.connectOn(CELL, NetworkMode.WHITELIST)
        h.brain.disconnect()

        h.connectOn(CELL, NetworkMode.OPEN)
        h.assertOn(d1, Tier.RU)
        assertEquals(setOf(d1.id), h.learning.map[CELL])
    }

    @Test
    fun `restart after process death resumes on the last good node of the network`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val nodes = listOf(d1, d2)
        val first = Harness(this, nodes)
        first.alive(d1, d2)
        first.rtt(d1 to 10, d2 to 20)
        first.connectOn(WIFI)
        first.libbox.traffic(true)
        first.kill(d1)
        advance(48_000)
        first.assertOn(d2)

        first.alive(d1)
        val second = Harness(this, nodes, memory = first.memory, stats = first.stats, learning = first.learning, prober = first.prober)
        second.connectOn(WIFI)
        second.assertOn(d2)
    }

    @Test
    fun `disconnect stops the core and halts probing`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.brain.disconnect()
        assertEquals(ConnState.Idle, h.brain.state.value)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
        val probes = h.prober.live.size
        advance(600_000)
        assertEquals(probes, h.prober.live.size)

        h.connectOn(WIFI)
        h.assertOn(d1)
        assertEquals(2, h.libbox.reloads.size)
    }

    @Test
    fun `connect waits for a network`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        val job = backgroundScope.launch { h.brain.connect() }
        runCurrent()
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), h.brain.state.value)
        assertTrue(h.libbox.calls.isEmpty())
        h.brain.onEvent(netEvent(WIFI))
        runCurrent()
        assertTrue(job.isCompleted)
        h.assertOn(d1)
    }

    @Test
    fun `no usable nodes fails without touching the core config`() = runTest {
        val h = Harness(this, emptyList())
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.NO_NODES), h.brain.state.value)
        assertTrue(h.libbox.reloads.isEmpty())
    }

    @Test
    fun `core refusing the config fails the session`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.libbox.failReload = true
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.CORE), h.brain.state.value)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }

    @Test
    fun `split reload refused mid-session restores the last loaded config on the current node`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.prober.liveDead += d1.tag
        h.connectOn(WIFI)
        h.prober.liveDead.clear()
        h.assertOn(d2)
        val startedAt = h.connected().connectedAtMs
        val good = h.libbox.reloads.single()

        h.libbox.failNextReloads = 1
        val apps = AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank"))
        h.settings.settings.value = ConnectionSettings(apps = apps)
        runCurrent()

        val reloads = h.libbox.reloads
        assertEquals(3, reloads.size)
        assertEquals(apps, reloads[1].apps)
        assertEquals(good, reloads[2])
        assertEquals(FakeLibbox.Call.Select("proxy", d2.tag), h.libbox.calls.last())
        h.assertOn(d2)
        assertEquals(startedAt, h.connected().connectedAtMs)

        // The wanted split is retried on the next occasion.
        h.brain.onEvent(netEvent(CELL))
        runCurrent()
        assertEquals(4, h.libbox.reloads.size)
        assertEquals(apps, h.libbox.reloads.last().apps)
        h.assertOn(d2)
    }

    @Test
    fun `network reload refused mid-session falls back, then recovers through the new plan`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val d3 = Nodes.ss("d3")
        val h = Harness(this, listOf(d1, d2, d3), plannerConfig = PlannerConfig(perTier = 2))
        h.alive(d1, d2, d3)
        h.rtt(d1 to 10, d2 to 20, d3 to 30)
        h.connectOn(WIFI)
        val startedAt = h.connected().connectedAtMs

        h.kill(d1, d2)
        h.libbox.failNextReloads = 1
        h.brain.onEvent(netEvent(CELL))
        runCurrent()

        val reloads = h.libbox.reloads
        assertEquals(4, reloads.size)
        assertTrue(d3.tag in reloads[1].json)
        assertEquals(reloads[0], reloads[2])
        assertTrue(d3.tag in reloads[3].json)
        h.assertOn(d3)
        assertEquals(startedAt, h.connected().connectedAtMs)
    }

    @Test
    fun `config failing to generate mid-session keeps the running config without touching the core`() = runTest {
        val d1 = Nodes.ss("d1")
        val bad = Nodes.ss("bad").copy(outboundJson = moe.desupon.model.Secret("not json"))
        var planned = listOf(d1)
        val planner = moe.desupon.brain.port.TierPlanner { _, _, _, _ ->
            moe.desupon.brain.port.TierPlan(listOf(Tier.DIRECT), mapOf(Tier.DIRECT to planned))
        }
        val h = Harness(this, listOf(d1, bad), planner = planner)
        h.alive(d1)
        h.connectOn(WIFI)
        h.assertOn(d1)

        planned = listOf(bad)
        h.brain.onEvent(netEvent(CELL))
        runCurrent()

        assertEquals(1, h.libbox.reloads.size)
        h.assertOn(d1, Tier.DIRECT)
    }

    @Test
    fun `fallback reload failing too fails the session with a core error`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        h.libbox.failNextReloads = 2
        h.split.rules.value = listOf(SplitRule(order = 0, kind = SplitRuleKind.DOMAIN_SUFFIX, value = "example.org", action = SplitAction.DIRECT))
        runCurrent()

        assertEquals(ConnState.Failed(FailReason.CORE), h.brain.state.value)
        assertEquals(3, h.libbox.reloads.size)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }

    @Test
    fun `turn nodes from a custom planner are ignored`() = runTest {
        val d1 = Nodes.ss("d1")
        val t1 = Nodes.ss("t1")
        val planner = moe.desupon.brain.port.TierPlanner { _, _, _, _ ->
            moe.desupon.brain.port.TierPlan(listOf(Tier.TURN, Tier.DIRECT), mapOf(Tier.TURN to listOf(t1), Tier.DIRECT to listOf(d1)))
        }
        val h = Harness(this, listOf(d1, t1), planner = planner)
        h.alive(d1, t1)
        h.connectOn(WIFI)
        h.assertOn(d1, Tier.DIRECT)
        assertTrue(t1.tag !in h.libbox.reloads.single().json)
    }

    private fun conn(id: String, chain: List<String>, up: Long, down: Long) =
        ConnInfo(id, "tcp", "203.0.113.1:443", null, chain.last(), chain, up, down, 0, false)

    @Test
    fun `pool check reports subscriptions with a passing node while connecting, then clears`() = runTest {
        val a = Nodes.ss("a").copy(subs = setOf(SubId(1)))
        val b = Nodes.ss("b").copy(subs = setOf(SubId(4)))
        val c = Nodes.ss("c").copy(subs = setOf(SubId(2), SubId(3)))
        val h = Harness(this, listOf(a, b, c))
        h.alive(a, c)
        var seen: Set<SubId>? = null
        h.prober.onLive = { if (seen == null) seen = h.brain.liveSubscriptions.value }

        h.connectOn(WIFI)

        assertEquals(setOf(SubId(1), SubId(2), SubId(3)), seen)
        assertIs<ConnState.Connected>(h.brain.state.value)
        assertTrue(h.brain.liveSubscriptions.value.isEmpty())
    }

    @Test
    fun `remembered last good node brings the TUN up on its plan before the pool is probed`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.memory.map[WIFI] = NetworkMemory(WIFI, NetworkMode.OPEN, d2.id, WALL_BASE)

        h.connectOn(WIFI)

        assertEquals(
            listOf("reload", "select:${d2.tag}", "detect", "probePool", "select:${d2.tag}", "probeLive:${d2.tag}"),
            h.journal.entries,
        )
        h.assertOn(d2)
        assertEquals(1, h.libbox.reloads.size)
        h.assertReloadsSelected()
    }

    @Test
    fun `the TUN is up before the network is detected or probed`() = runTest {
        for (remembered in listOf(false, true)) {
            val d1 = Nodes.ss("d1")
            val h = Harness(this, listOf(d1))
            h.alive(d1)
            if (remembered) h.memory.map[WIFI] = NetworkMemory(WIFI, NetworkMode.OPEN, d1.id, WALL_BASE)
            h.connectOn(WIFI)
            val firstCore = h.journal.entries.indexOfFirst { it == "reload" || it == "placeholder" }
            val firstProbe = h.journal.entries.indexOfFirst { it == "detect" || it == "probePool" || it.startsWith("probeLive:") }
            assertTrue(firstCore in 0 until firstProbe, "remembered=$remembered: ${h.journal.entries}")
            h.brain.disconnect()
        }
    }

    @Test
    fun `core refusing the placeholder fails the session before any probe`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.libbox.failPlaceholder = true
        h.connectOn(WIFI)
        assertEquals(ConnState.Failed(FailReason.CORE), h.brain.state.value)
        assertTrue(h.prober.poolRequests.isEmpty())
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }

    @Test
    fun `pool probe while connected leaves running WireGuard keys to the live box`() = runTest {
        val w1 = Nodes.wg("w1")
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(w1, d1, d2), plannerConfig = PlannerConfig(perTier = 2))
        h.alive(w1, d1, d2)
        h.rtt(w1 to 10, d1 to 20, d2 to 30)
        h.connectOn(WIFI)
        h.assertOn(w1)
        assertTrue(w1.tag in h.prober.poolRequests.single().nodesJson.raw())
        h.libbox.traffic(true)
        h.kill(d1)
        h.prober.liveDead += w1.tag
        val liveW1 = h.prober.live.count { it == w1.tag }

        advance(48_000)

        val rescue = h.prober.poolRequests.last().nodesJson.raw()
        assertEquals(2, h.prober.poolRequests.size)
        assertTrue(w1.tag !in rescue)
        assertTrue(d2.tag in rescue)
        assertTrue(h.prober.live.count { it == w1.tag } > liveW1)
        h.assertOn(d2)
    }

    @Test
    fun `network loss pauses checks without spending failover budget`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)

        h.brain.onEvent(BrainEvent.NetworkLost)
        runCurrent()
        h.kill(d1, d2)
        val live = h.prober.live.size
        val pools = h.prober.poolRequests.size
        h.brain.onEvent(BrainEvent.Tick)
        advance(10 * 60_000L)
        assertEquals(live, h.prober.live.size)
        assertEquals(pools, h.prober.poolRequests.size)
        h.assertOn(d1)

        h.alive(d2)
        h.brain.onEvent(netEvent(WIFI))
        runCurrent()
        assertEquals(live + 1, h.prober.live.size, "the current node is checked as soon as the network is back")
        h.assertOn(d1)
        advance(6_000)
        h.assertOn(d2)
        assertEquals(pools, h.prober.poolRequests.size)
    }

    @Test
    fun `wake resets only loaded outbounds dialing through sleep-fragile QUIC, then checks the current node at once`() = runTest {
        val t1 = Nodes.tuic("t1")
        val h1 = Nodes.hy2("h1")
        val s1 = Nodes.ss("s1")
        val c1 = Nodes.ssViaTuic("c1")
        val w1 = Nodes.wg("w1")
        val h = Harness(this, listOf(t1, h1, s1, c1, w1))
        h.alive(t1, h1, s1, c1, w1)
        h.rtt(t1 to 10, h1 to 20, s1 to 30, c1 to 40, w1 to 50)
        h.connectOn(WIFI)
        advance(600_000)
        val config = h.libbox.reloads.last().json
        for (node in listOf(t1, h1, s1, c1, w1)) assertTrue("\"tag\":\"${node.tag}\"" in config, "${node.displayName} not loaded")
        h.journal.entries.clear()

        h.brain.onWake(25_000)
        runCurrent()

        assertEquals(0, h.libbox.resets, "a wake never resets the whole core")
        assertEquals(setOf(t1.tag, c1.tag), h.libbox.outboundResets.toSet())
        assertEquals("probeLive:${t1.tag}", h.journal.entries.last())
        h.assertOn(t1)
    }

    @Test
    fun `wake resets nodes riding QUIC on the transport too, as the node or a hop`() = runTest {
        val q1 = Nodes.vlessH3("q1")
        val c1 = Nodes.ssViaVlessQuic("c1")
        val p1 = Nodes.vlessH2("p1")
        val h = Harness(this, listOf(q1, c1, p1))
        h.alive(q1, c1, p1)
        h.rtt(q1 to 10, c1 to 20, p1 to 30)
        h.connectOn(WIFI)
        advance(600_000)
        val config = h.libbox.reloads.last().json
        for (node in listOf(q1, c1, p1)) assertTrue("\"tag\":\"${node.tag}\"" in config, "${node.displayName} not loaded")

        h.brain.onWake(25_000)
        runCurrent()

        assertEquals(0, h.libbox.resets)
        assertEquals(setOf(q1.tag, c1.tag), h.libbox.outboundResets.toSet(), "a pooled TCP node rides out a short sleep")
    }

    @Test
    fun `a long sleep also resets nodes pooling their flows on one TCP connection`() = runTest {
        val t1 = Nodes.tuic("t1")
        val p1 = Nodes.vlessH2("p1")
        val s1 = Nodes.ss("s1")
        val h = Harness(this, listOf(t1, p1, s1))
        h.alive(t1, p1, s1)
        h.rtt(t1 to 10, p1 to 20, s1 to 30)
        h.connectOn(WIFI)
        advance(600_000)

        h.brain.onWake(BrainConfig().longSleepMs)
        runCurrent()

        assertEquals(0, h.libbox.resets)
        assertEquals(setOf(t1.tag, p1.tag), h.libbox.outboundResets.toSet())
    }

    @Test
    fun `a wake with no sleep-fragile node resets nothing but still opens the grace window and checks at once`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        advance(600_000)
        val before = h.stats.map.getValue(WIFI to d1.id)
        h.journal.entries.clear()

        h.prober.liveDead += d1.tag
        h.brain.onWake(25_000)
        runCurrent()

        assertEquals(0, h.libbox.resets)
        assertEquals(emptyList(), h.libbox.outboundResets)
        assertEquals("probeLive:${d1.tag}", h.journal.entries.single { it.startsWith("probe") })
        val after = h.stats.map.getValue(WIFI to d1.id)
        assertEquals(before.fail, after.fail, "a failure in the grace window is not recorded")
        assertEquals(0, after.failStreak)
    }

    @Test
    fun `an unblock right after the core started over skips the reset but still checks`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        advance(600_000)
        h.brain.onNetworkUnblocked()
        runCurrent()
        assertEquals(1, h.libbox.resets)
        h.journal.entries.clear()

        advance(BrainConfig().resetCooldownMs - 1)
        h.brain.onNetworkUnblocked()
        runCurrent()

        assertEquals(1, h.libbox.resets, "a flapping unblock does not drop every connection again")
        assertEquals(listOf("probeLive:${d1.tag}"), h.journal.entries)

        advance(1)
        h.brain.onNetworkUnblocked()
        runCurrent()
        assertEquals(2, h.libbox.resets)
    }

    @Test
    fun `an unblocked network resets the whole core and checks the current node at once`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.connectOn(WIFI)
        advance(600_000)
        h.journal.entries.clear()

        h.brain.onNetworkUnblocked()
        runCurrent()

        assertEquals(listOf("reset", "probeLive:${d1.tag}"), h.journal.entries)
        h.assertOn(d1)
    }

    @Test
    fun `wake retries a degraded session at once`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.connectOn(WIFI, NetworkMode.OFFLINE)
        assertIs<ConnState.Degraded>(h.brain.state.value)
        h.alive(d1)
        advance(10_000)

        h.brain.onWake(25_000)
        runCurrent()

        h.assertOn(d1)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun `a failure right after wake is confirmed without a reset and leaves no mark once confirmed good`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        advance(600_000)
        val before = h.stats.map.getValue(WIFI to d1.id)

        h.prober.liveDead += d1.tag
        h.brain.onWake(25_000)
        runCurrent()
        h.prober.liveDead.clear()
        advance(3_000)

        h.assertOn(d1)
        assertEquals(0, h.libbox.resets)
        assertEquals(emptyList(), h.libbox.outboundResets)
        val after = h.stats.map.getValue(WIFI to d1.id)
        assertEquals(before.fail, after.fail)
        assertEquals(0, after.failStreak)
    }

    @Test
    fun `failover inside the wake grace window keeps the last good node until the new one passes after it`() = runTest {
        val d1 = Nodes.tuic("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        advance(600_000)
        val failsBefore = h.stats.map.getValue(WIFI to d1.id).fail

        h.kill(d1)
        h.brain.onWake(25_000)
        runCurrent()
        advance(3_000)

        h.assertOn(d2)
        assertEquals(listOf(d1.tag), h.libbox.outboundResets, "the wake reset counts as the node's")
        assertEquals(d1.id, h.memory.map.getValue(WIFI).lastGood)
        assertEquals(failsBefore, h.stats.map.getValue(WIFI to d1.id).fail)

        advance(45_000)
        h.assertOn(d2)
        assertEquals(d2.id, h.memory.map.getValue(WIFI).lastGood)
    }

    @Test
    fun `the wake grace window still records what a set probe finds off the core`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        advance(600_000)
        val before = h.stats.map.getValue(WIFI to d2.id)

        h.kill(d2)
        h.brain.onWake(25_000)
        runCurrent()
        val probed = async { h.brain.probeSetPool() }
        runCurrent()

        assertEquals(ProbeRequestResult.Done(2), probed.await())
        val after = h.stats.map.getValue(WIFI to d2.id)
        assertEquals(before.fail + 1, after.fail)
        assertEquals(1, after.failStreak)
    }

    @Test
    fun `a failure the confirmation clears resets nothing`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        advance(60_000)
        h.prober.liveDead += d1.tag

        advance(30_000)
        h.prober.liveDead.clear()
        advance(3_000)

        h.assertOn(d1)
        assertEquals(0, h.libbox.resets)
        assertEquals(emptyList(), h.libbox.outboundResets)
    }

    @Test
    fun `a failure that would switch away resets the node's outbound and probes it again uncounted, at most once a minute`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        advance(60_000)
        val failsBefore = h.stats.map.getValue(WIFI to d1.id).fail
        h.kill(d1)
        h.journal.entries.clear()

        advance(33_000)
        assertEquals(listOf("probeLive:${d1.tag}", "probeLive:${d1.tag}", "reset:${d1.tag}"), h.journal.entries)
        h.alive(d1)
        advance(3_000)
        h.assertOn(d1)
        assertEquals(failsBefore + 1, h.stats.map.getValue(WIFI to d1.id).fail, "the failure before the reset is not recorded")

        h.kill(d1)
        advance(45_000)
        h.assertOn(d2)
        assertEquals(listOf(d1.tag), h.libbox.outboundResets)
        assertEquals(0, h.libbox.resets)
    }

    @Test
    fun `a failure soon after a reload is confirmed without a reset`() = runTest {
        val d1 = Nodes.ss("d1")
        val d2 = Nodes.ss("d2")
        val h = Harness(this, listOf(d1, d2))
        h.alive(d1, d2)
        h.rtt(d1 to 10, d2 to 20)
        h.connectOn(WIFI)
        h.libbox.traffic(true)
        h.kill(d1)
        advance(48_000)
        h.assertOn(d2)
        assertEquals(0, h.libbox.resets)
        assertEquals(emptyList(), h.libbox.outboundResets)
    }

    @Test
    fun `disconnect does not wait for a pool probe in flight`() = runTest {
        val d1 = Nodes.ss("d1")
        val h = Harness(this, listOf(d1))
        h.alive(d1)
        h.prober.poolGate = CompletableDeferred()
        h.brain.onEvent(netEvent(WIFI))
        backgroundScope.launch { h.brain.connect() }
        runCurrent()
        assertEquals(1, h.prober.poolRequests.size)

        h.brain.disconnect()

        assertEquals(ConnState.Idle, h.brain.state.value)
        assertEquals(FakeLibbox.Call.Stop, h.libbox.calls.last())
    }
}
