package moe.desupon.brain.plan

import moe.desupon.brain.BrainConfig
import moe.desupon.brain.CELL
import moe.desupon.brain.Nodes
import moe.desupon.brain.WIFI
import moe.desupon.brain.next
import moe.desupon.brain.withThroughput
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.probe.Throughput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeScoreTest {
    private val now = 1_800_000_000_000L
    private val ttl = 24 * 60 * 60_000L
    private val id = NodeId("0123456789abcdef")

    private fun stat(ok: Int = 5, fail: Int = 0, streak: Int = 0, lastOkAgo: Long? = 60_000, rtt: Double = 80.0) =
        ProbeStat(id, WIFI, rtt, ok, fail, streak, ProbeStage.GET204, lastOkAgo?.let { now - it })

    @Test
    fun `one failure after recent success is suspect, two or no success at all is failing`() {
        assertEquals(Standing.UNKNOWN, NodeScore.standing(null, now, ttl))
        assertEquals(Standing.VERIFIED, NodeScore.standing(stat(), now, ttl))
        assertEquals(Standing.SUSPECT, NodeScore.standing(stat(fail = 1, streak = 1), now, ttl))
        assertEquals(Standing.FAILING, NodeScore.standing(stat(fail = 2, streak = 2), now, ttl))
        assertEquals(Standing.FAILING, NodeScore.standing(stat(ok = 0, fail = 1, streak = 1, lastOkAgo = null), now, ttl))
        assertEquals(Standing.UNKNOWN, NodeScore.standing(stat(fail = 1, streak = 1, lastOkAgo = 2 * ttl), now, ttl))
        assertEquals(Standing.UNKNOWN, NodeScore.standing(stat(lastOkAgo = 2 * ttl), now, ttl))
    }

    @Test
    fun `score adds dispersion, connect time, failures and staleness to the RTT`() {
        val s = stat(ok = 3, fail = 1, streak = 1, lastOkAgo = ttl / 2).copy(madMs = 10.0, ewmaConnectMs = 40.0, okRecent = 3.0, failRecent = 1.0)
        val expected = 80.0 + 1.3 * 10 + 40.0 / 4 + 150 * 0.25 + 60 * 1 + 50.0
        assertEquals(expected, NodeScore.score(s, now, ttl), 1e-9)
        assertEquals(expected + NodeScore.THROTTLED_PENALTY, NodeScore.score(s.copy(throttled = true), now, ttl), 1e-9)
    }

    @Test
    fun `a jittery fast node can rank behind a steady slower one`() {
        val steady = stat(rtt = 120.0).copy(madMs = 5.0)
        val jittery = stat(rtt = 90.0).copy(madMs = 60.0)
        val order = listOf(jittery, steady).sortedWith(NodeScore.comparator({ it }, now, ttl))
        assertEquals(listOf(steady, jittery), order)
    }

    @Test
    fun `stat update tracks warm RTT, dispersion, connect time and decaying recent counts`() {
        val ok = { rtt: Int, connect: Int -> ProbeResult.Ok(ProbeStage.GET204, rtt, connectMs = connect, warm = true) }
        val legacy = ProbeStat(id, WIFI, 300.0, 4, 0, 0, ProbeStage.GET204, now)
        val first = legacy.next(id, WIFI, ok(60, 120), now, 0.5)
        assertEquals(60.0, first.ewmaRttMs, "a sample from before staged probes is replaced, not blended")
        assertEquals(120.0, first.ewmaConnectMs)
        assertEquals(0.0, first.madMs)
        assertEquals(1, first.rttSamples)

        val second = first.next(id, WIFI, ok(100, 80), now, 0.5)
        assertEquals(80.0, second.ewmaRttMs)
        assertEquals(20.0, second.madMs)
        assertEquals(100.0, second.ewmaConnectMs)

        val failed = second.next(id, WIFI, ProbeResult.Fail(ProbeStage.TLS, "timeout"), now + 5, 0.5)
        assertEquals(1, failed.failStreak)
        assertEquals(now + 5, failed.lastFailAtMs)
        assertEquals(80.0, failed.ewmaRttMs)
        assertEquals(1.0, failed.failRecent)
        assertEquals(second.okRecent * 0.8, failed.okRecent, 1e-9)
        assertEquals(1.0 / (1.0 + 1.8 * 0.8), failed.failureRatio, 1e-9)
    }

    @Test
    fun `throughput folds into kbps and the throttled mark`() {
        val s = stat().withThroughput(id, WIFI, Throughput(true, 65_536, 500), throttled = false, alpha = 0.5)
        assertEquals(65_536 * 8.0 / 500, s.ewmaKbps!!, 1e-9)
        val t = s.withThroughput(id, WIFI, Throughput(true, 20_000, 3_000), throttled = true, alpha = 0.5)
        assertTrue(t.throttled && t.ewmaKbps!! < s.ewmaKbps!!)
    }

    @Test
    fun `timeouts follow the transport for unknown nodes and the connect time for usable ones`() {
        val c = BrainConfig()
        assertEquals(5_000, c.probeTimeoutMs(WIFI.transport, null, now))
        assertEquals(3_000, c.probeTimeoutMs(CELL.transport, null, now))
        assertEquals(32, c.poolConcurrency(WIFI.transport))
        assertEquals(24, c.poolConcurrency(CELL.transport))
        val verified = stat(lastOkAgo = 1_000)
        assertEquals(1_500, c.probeTimeoutMs(CELL.transport, verified.copy(ewmaConnectMs = 100.0), now))
        assertEquals(2_400, c.probeTimeoutMs(CELL.transport, verified.copy(ewmaConnectMs = 800.0), now))
        assertEquals(5_000, c.probeTimeoutMs(CELL.transport, verified.copy(ewmaConnectMs = 4_000.0), now))
        assertEquals(3_000, c.probeTimeoutMs(CELL.transport, verified, now), "no connect time yet")
        assertEquals(3_000, c.probeTimeoutMs(CELL.transport, stat(fail = 2, streak = 2).copy(ewmaConnectMs = 100.0), now))
    }

    @Test
    fun `sweep order puts the last good node first, then steps, the RU tier under whitelist, and standing classes`() {
        val good = Nodes.ss("good")
        val fast = Nodes.ss("fast")
        val slow = Nodes.ss("slow")
        val hinted = Nodes.ss("hinted", labels = setOf(TierLabels.WHITELIST))
        val plain = Nodes.ss("plain")
        val dead = Nodes.ss("dead")
        val ru = Nodes.ss("ru", country = "RU")
        fun s(n: moe.desupon.model.node.Node, rtt: Double, streak: Int = 0) =
            n.id to ProbeStat(n.id, WIFI, rtt, 3, streak, streak, ProbeStage.GET204, now - 1_000)
        val stats = mapOf(s(good, 400.0), s(fast, 50.0), s(slow, 300.0), s(dead, 10.0, streak = 3))
        val pool = listOf(dead, plain, hinted, slow, fast, good)

        val open = moe.desupon.brain.pool.Pools.sweepOrder(pool, stats, emptyList(), good.id, moe.desupon.model.network.NetworkMode.OPEN, WIFI.transport, now)
        assertEquals(listOf("good", "fast", "slow", "hinted", "plain", "dead"), open.map { it.displayName })

        val wl = moe.desupon.brain.pool.Pools.sweepOrder(pool + ru, stats, emptyList(), null, moe.desupon.model.network.NetworkMode.WHITELIST, WIFI.transport, now)
        assertEquals(listOf("hinted", "ru", "fast", "slow", "good", "plain", "dead"), wl.map { it.displayName })
    }
}
