package moe.desupon.brain.plan

import moe.desupon.brain.CELL
import moe.desupon.brain.Nodes
import moe.desupon.brain.WIFI
import moe.desupon.brain.port.Clock
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTierPlannerTest {
    private val now = 1_800_000_000_000L
    private val clock = object : Clock {
        override fun nowMs() = now

        override fun elapsedMs() = 0L
    }
    private val planner = DefaultTierPlanner(clock)

    private fun ok(node: Node, rtt: Double, key: NetworkKey = WIFI, ageMs: Long = 60_000) =
        node.id to ProbeStat(node.id, key, rtt, 5, 0, 0, ProbeStage.GET204, now - ageMs)

    private fun failing(node: Node, streak: Int, key: NetworkKey = WIFI, lastOkAgo: Long? = null) =
        node.id to ProbeStat(node.id, key, 80.0, 1, streak, streak, ProbeStage.GET204, lastOkAgo?.let { now - it })

    private fun names(nodes: List<Node>?) = nodes.orEmpty().map { it.displayName }

    private val nl1 = Nodes.ss("nl1")
    private val nl2 = Nodes.ss("nl2")
    private val de1 = Nodes.ss("de1", country = "DE")
    private val ru1 = Nodes.ss("ru1", country = "RU")
    private val wl1 = Nodes.ss("wl1", country = "FI", labels = setOf(TierLabels.WHITELIST))
    private val lte1 = Nodes.ss("lte1", country = "NL", labels = setOf(TierLabels.LTE))
    private val mobileOnly = Nodes.ss("mob", labels = setOf(TierLabels.ONLY_MOBILE))
    private val wifiOnly = Nodes.ss("wifi", labels = setOf(TierLabels.ONLY_WIFI))

    @Test
    fun `tier order follows the network mode`() {
        val pool = listOf(nl1, ru1)
        assertEquals(listOf(Tier.DIRECT, Tier.RU), planner.plan(NetworkMode.OPEN, WIFI, pool, emptyMap()).order)
        assertEquals(listOf(Tier.DIRECT, Tier.RU), planner.plan(NetworkMode.UNKNOWN, WIFI, pool, emptyMap()).order)
        assertEquals(listOf(Tier.RU, Tier.DIRECT), planner.plan(NetworkMode.WHITELIST, WIFI, pool, emptyMap()).order)
        assertEquals(listOf(Tier.RU, Tier.DIRECT), planner.plan(NetworkMode.OFFLINE, WIFI, pool, emptyMap()).order)
    }

    @Test
    fun `tiers come from country and labels, LTE counts only on cellular`() {
        val pool = listOf(nl1, ru1, wl1, lte1)
        val wifi = planner.plan(NetworkMode.OPEN, WIFI, pool, emptyMap())
        assertEquals(listOf("nl1", "lte1"), names(wifi.topK[Tier.DIRECT]))
        assertEquals(setOf("ru1", "wl1"), names(wifi.topK[Tier.RU]).toSet())
        val cell = planner.plan(NetworkMode.OPEN, CELL, pool, emptyMap())
        assertEquals(listOf("nl1"), names(cell.topK[Tier.DIRECT]))
        assertEquals(setOf("ru1", "wl1", "lte1"), names(cell.topK[Tier.RU]).toSet())
    }

    @Test
    fun `transport labels filter the pool`() {
        val pool = listOf(nl1, mobileOnly, wifiOnly)
        assertEquals(listOf("nl1", "wifi"), names(planner.plan(NetworkMode.OPEN, WIFI, pool, emptyMap()).topK[Tier.DIRECT]))
        assertEquals(listOf("nl1", "mob"), names(planner.plan(NetworkMode.OPEN, CELL, pool, emptyMap()).topK[Tier.DIRECT]))
    }

    @Test
    fun `unsupported and FreeTurn nodes are never planned`() {
        val broken = Nodes.ss("broken", capability = Capability.UNSUPPORTED)
        val ft = Nodes.ss("ft").copy(protocol = Protocol.FREETURN)
        val plan = planner.plan(NetworkMode.OPEN, WIFI, listOf(broken, ft, nl1), emptyMap())
        assertEquals(listOf("nl1"), names(plan.topK.values.flatten()))
    }

    @Test
    fun `usable by composite score with one-off failures as suspects, then unknown, then failing by streak`() {
        val a = Nodes.ss("a")
        val b = Nodes.ss("b")
        val c = Nodes.ss("c")
        val d = Nodes.ss("d")
        val e = Nodes.ss("e")
        val stats = mapOf(
            ok(a, rtt = 300.0),
            ok(b, rtt = 90.0),
            failing(c, streak = 1, lastOkAgo = 10_000),
            failing(d, streak = 1, lastOkAgo = 1_000),
            failing(e, streak = 4),
        )
        val unknown = Nodes.ss("unknown")
        val plan = planner.plan(NetworkMode.OPEN, WIFI, listOf(e, d, c, unknown, a, b), stats)
        assertEquals(listOf("b", "d", "c", "a", "unknown", "e"), names(plan.topK[Tier.DIRECT]))
    }

    @Test
    fun `stale success ranks as unknown`() {
        val stale = Nodes.ss("stale")
        val fresh = Nodes.ss("fresh")
        val stats = mapOf(ok(stale, rtt = 10.0, ageMs = 3 * 24 * 60 * 60_000L), ok(fresh, rtt = 500.0))
        assertEquals(listOf("fresh", "stale"), names(planner.plan(NetworkMode.OPEN, WIFI, listOf(stale, fresh), stats).topK[Tier.DIRECT]))
    }

    @Test
    fun `top-K per tier and endpoint budget`() {
        val many = (1..20).map { Nodes.ss("n$it") }
        val wgs = (1..5).map { Nodes.wg("wg$it") }
        val planner = DefaultTierPlanner(clock, PlannerConfig(perTier = 6, endpointsPerTier = 2))
        val plan = planner.plan(NetworkMode.OPEN, WIFI, wgs + many, emptyMap())
        val direct = plan.topK.getValue(Tier.DIRECT)
        assertEquals(6, direct.size)
        assertEquals(2, direct.count { it.isEndpoint })
    }

    @Test
    fun `whitelist orders RU verified here, then hints, and samples DIRECT`() {
        val learned = nl2.copy(labels = setOf(TierLabels.LEARNED_RU))
        val directs = (1..5).map { Nodes.ss("d$it") }
        val stats = mapOf(ok(ru1, rtt = 200.0, key = CELL), ok(learned, rtt = 150.0, key = CELL))
        val ruHint = Nodes.ss("ruHint", country = "RU")
        val plan = planner.plan(NetworkMode.WHITELIST, CELL, listOf(ruHint, wl1, ru1, learned) + directs, stats)
        assertEquals(listOf(Tier.RU, Tier.DIRECT), plan.order)
        assertEquals(listOf("nl2", "ru1", "wl1", "ruHint"), names(plan.topK[Tier.RU]))
        assertEquals(2, plan.topK.getValue(Tier.DIRECT).size)
    }

    @Test
    fun `learned label moves a foreign node to RU, otherwise it stays DIRECT`() {
        val plain = planner.plan(NetworkMode.OPEN, WIFI, listOf(nl1), emptyMap())
        assertEquals(listOf(Tier.DIRECT), plain.order)
        val learned = planner.plan(NetworkMode.OPEN, WIFI, listOf(nl1.copy(labels = setOf(TierLabels.LEARNED_RU))), emptyMap())
        assertEquals(listOf(Tier.RU), learned.order)
    }

    @Test
    fun `offline keeps full top-K in both tiers`() {
        val directs = (1..5).map { Nodes.ss("x$it") }
        val plan = planner.plan(NetworkMode.OFFLINE, WIFI, listOf(ru1) + directs, emptyMap())
        assertEquals(listOf(Tier.RU, Tier.DIRECT), plan.order)
        assertEquals(5, plan.topK.getValue(Tier.DIRECT).size)
    }

    @Test
    fun `empty tiers are dropped from the order`() {
        val plan = planner.plan(NetworkMode.OPEN, WIFI, listOf(nl1, de1), emptyMap())
        assertEquals(listOf(Tier.DIRECT), plan.order)
        assertTrue(Tier.RU !in plan.topK)
    }
}
