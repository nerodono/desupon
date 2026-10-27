package moe.desupon.brain.pool

import moe.desupon.brain.Nodes
import moe.desupon.brain.port.PoolSelection
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Protocol
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoolsTest {
    private val a = Nodes.ss("a", country = "NL").copy(subs = setOf(SubId(1)))
    private val b = Nodes.ss("b", country = "de").copy(subs = setOf(SubId(1), SubId(2)))
    private val c = Nodes.ss("c", country = "DE").copy(subs = setOf(SubId(2)))
    private val broken = Nodes.ss("x", capability = Capability.UNSUPPORTED).copy(subs = setOf(SubId(1)))
    private val turn = Nodes.ss("t").copy(subs = setOf(SubId(1)), protocol = Protocol.FREETURN)
    private val all = listOf(a, b, c, broken, turn, a)

    @Test
    fun setPoolKeepsUsableEnabledMembersOnce() {
        assertEquals(listOf(a, b, c), Pools.setPool(all, PoolSelection()))
        assertEquals(listOf(a, b), Pools.setPool(all, PoolSelection(SetId(3), setOf(SubId(1)))))
        assertEquals(listOf(b, c), Pools.setPool(all, PoolSelection(disabled = setOf(a.id))))
    }

    @Test
    fun countryNarrowsTheSetCaseInsensitively() {
        val sel = PoolSelection(SetId(3), setOf(SubId(1)), LocationChoice.Country("DE"))
        assertEquals(listOf(b), Pools.effective(all, sel))
    }

    @Test
    fun pinIgnoresTheSetButNotDisabledOrUnsupported() {
        val outside = PoolSelection(SetId(3), setOf(SubId(1)), LocationChoice.Node(c.id))
        assertEquals(listOf(c), Pools.effective(all, outside))
        assertEquals(listOf(a, b, c), Pools.probeTargets(all, outside))
        assertEquals(emptyList(), Pools.effective(all, outside.copy(disabled = setOf(c.id))))
        assertNull(Pools.pinned(all, PoolSelection(choice = LocationChoice.Node(broken.id))))
    }

    @Test
    fun hotTargetsPutKeptNodesThenTheLimitFromTheActiveStepThenEarlierSteps() {
        val s1 = SubId(1)
        val s2 = SubId(2)
        val s3 = SubId(3)
        val step0 = (1..3).map { Nodes.ss("p$it").copy(subs = setOf(s1)) }
        val step1 = (1..3).map { Nodes.ss("q$it").copy(subs = setOf(s2)) }
        val rest = (1..3).map { Nodes.ss("r$it").copy(subs = setOf(s3)) }
        val targets = rest + step1 + step0
        val key = moe.desupon.brain.WIFI
        fun stat(n: moe.desupon.model.node.Node, rtt: Double, failStreak: Int = 0) =
            n.id to moe.desupon.model.probe.ProbeStat(n.id, key, rtt, 1, failStreak, failStreak, null, null)
        val stats = mapOf(stat(step1[0], 50.0, failStreak = 2), stat(step1[1], 90.0), stat(step1[2], 20.0))

        val hot = Pools.hotTargets(targets, listOf(s1, s2), step = 1, keep = listOf(rest[2].id), stats = stats, limit = 6)
        assertEquals(listOf(rest[2], step1[2], step1[1], step1[0], step0[0], step0[1], step0[2]), hot)

        val idle = Pools.hotTargets(targets, listOf(s1, s2), step = null, keep = emptyList(), stats = emptyMap(), limit = 4)
        assertEquals(step0 + step1[0], idle)
    }
}
