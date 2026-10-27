package moe.desupon.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.desupon.data.inMemoryDb
import moe.desupon.data.repo.NetworkMemoryRepository
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.ProbeStatRepository
import moe.desupon.data.repo.SplitRuleRepository
import moe.desupon.data.repo.TierLearningRepository
import moe.desupon.data.split.RuDefaults
import moe.desupon.data.FakeClock
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import moe.desupon.model.subscription.SubId
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private val db = inMemoryDb()
    private val clock = FakeClock()

    @After
    fun close() = db.close()

    private suspend fun sub(): Long = db.subscriptionDao().insert(SubscriptionEntity(url = "https://s.example.com/x", hwid = "h", createdAt = 0))

    private fun node(id: String, name: String = id) = NodeEntity(
        id = id, protocol = "TROJAN", displayName = name, country = null, capability = "SUPPORTED",
        outboundJson = "{}", isEndpoint = false, udpOnly = false, labels = "LTE\nONLY_MOBILE", unsupportedReason = null,
    )

    @Test
    fun poolMergesSubscriptionsInOrderAndDropsOrphans() = runTest {
        val a = sub()
        val b = sub()
        val nodes = db.nodeDao()
        nodes.replaceForSubscription(a, listOf(node("n2"), node("n1")))
        nodes.replaceForSubscription(b, listOf(node("n1"), node("n3")))
        val repo = NodeRepository(nodes)

        val pool = repo.nodes.first()
        assertEquals(listOf("n2", "n1", "n3"), pool.map { it.id.hash })
        assertEquals(setOf(SubId(a), SubId(b)), pool.first { it.id.hash == "n1" }.subs)
        assertEquals(setOf("LTE", "ONLY_MOBILE"), pool.first().labels)

        nodes.replaceForSubscription(a, listOf(node("n1")))
        assertEquals(listOf("n1", "n3"), repo.snapshot().map { it.id.hash })

        db.subscriptionDao().delete(b)
        nodes.deleteOrphans()
        assertEquals(listOf("n1"), repo.snapshot().map { it.id.hash })
        assertEquals(listOf(SubNodeCount(a, 1)), nodes.observeCounts().first())
    }

    @Test
    fun unknownProtocolRowsAreSkipped() = runTest {
        val a = sub()
        db.nodeDao().replaceForSubscription(a, listOf(node("ok"), node("old").copy(protocol = "GONE")))
        assertEquals(listOf("ok"), NodeRepository(db.nodeDao()).snapshot().map { it.id.hash })
    }

    @Test
    fun probeStatsRoundTripPerNetworkAndDieWithTheirNode() = runTest {
        val a = sub()
        db.nodeDao().replaceForSubscription(a, listOf(node("n1"), node("n2")))
        val repo = ProbeStatRepository(db.probeStatDao(), clock)
        val wifi = NetworkKey(Transport.WIFI, "abc")
        val cell = NetworkKey(Transport.CELLULAR, "25001")
        val s1 = ProbeStat(NodeId("n1"), wifi, 120.5, 3, 1, 0, ProbeStage.GET204, 42L)
        repo.upsert(listOf(s1, s1.copy(nodeId = NodeId("n2"), lastStage = null, lastOkAtMs = null), s1.copy(network = cell, ok = 9)))

        assertEquals(s1, repo.forNetwork(wifi)[NodeId("n1")])
        assertNull(repo.forNetwork(wifi)[NodeId("n2")]!!.lastStage)
        assertEquals(9, repo.forNetwork(cell)[NodeId("n1")]!!.ok)

        db.nodeDao().replaceForSubscription(a, listOf(node("n1")))
        assertEquals(setOf(NodeId("n1")), repo.forNetwork(wifi).keys)
    }

    @Test
    fun probeStatsAreObservablePerNetwork() = runTest {
        val a = sub()
        db.nodeDao().replaceForSubscription(a, listOf(node("n1")))
        val repo = ProbeStatRepository(db.probeStatDao(), clock)
        val wifi = NetworkKey(Transport.WIFI, "abc")
        assertTrue(repo.observe(wifi).first().isEmpty())
        val s = ProbeStat(NodeId("n1"), wifi, 33.0, 1, 0, 0, ProbeStage.GET204, 5L)
        repo.upsert(listOf(s, s.copy(network = NetworkKey(Transport.CELLULAR, "25001"))))
        assertEquals(mapOf(NodeId("n1") to s), repo.observe(wifi).first())
    }

    @Test
    fun tierLearningIsPerNetworkIdempotentAndDiesWithItsNode() = runTest {
        val a = sub()
        db.nodeDao().replaceForSubscription(a, listOf(node("n1"), node("n2")))
        val repo = TierLearningRepository(db.tierLearningDao(), clock)
        val wifi = NetworkKey(Transport.WIFI, "abc")
        val cell = NetworkKey(Transport.CELLULAR, "25001")
        assertTrue(repo.promoted(cell).isEmpty())

        repo.promote(cell, setOf(NodeId("n1")))
        repo.promote(cell, setOf(NodeId("n1"), NodeId("n2")))
        repo.promote(wifi, emptySet())
        assertEquals(setOf(NodeId("n1"), NodeId("n2")), repo.promoted(cell))
        assertTrue(repo.promoted(wifi).isEmpty())

        db.nodeDao().replaceForSubscription(a, listOf(node("n2")))
        assertEquals(setOf(NodeId("n2")), repo.promoted(cell))
    }

    @Test
    fun networkMemoryPruneDropsStaleMemoryAndStats() = runTest {
        val repo = NetworkMemoryRepository(db.networkMemoryDao(), db.probeStatDao(), clock)
        val stats = ProbeStatRepository(db.probeStatDao(), clock)
        val old = NetworkKey(Transport.WIFI, "old")
        val fresh = NetworkKey(Transport.CELLULAR, "25099")
        repo.put(NetworkMemory(old, NetworkMode.WHITELIST, NodeId("n1"), updatedAtMs = 1_000))
        stats.upsert(listOf(ProbeStat(NodeId("n1"), old, 1.0, 1, 0, 0, null, null)))
        repo.put(NetworkMemory(fresh, NetworkMode.OPEN, null, updatedAtMs = clock.now))

        assertEquals(NetworkMode.WHITELIST, repo.get(old)!!.mode)
        clock.advance(NetworkMemoryRepository.DEFAULT_TTL_MS + 1)
        repo.put(NetworkMemory(fresh, NetworkMode.OPEN, null, updatedAtMs = clock.now))
        repo.pruneExpired()

        assertNull(repo.get(old))
        assertEquals(NetworkMode.OPEN, repo.get(fresh)!!.mode)
        assertTrue(stats.forNetwork(old).isEmpty())
    }

    @Test
    fun splitRulesKeepOrderReorderAndReset() = runTest {
        val repo = SplitRuleRepository(db.splitRuleDao())
        val a = repo.add(SplitRule(order = 99, kind = SplitRuleKind.DOMAIN_SUFFIX, value = "a.example", action = SplitAction.PROXY))
        val b = repo.add(SplitRule(order = 0, kind = SplitRuleKind.IP_CIDR, value = "10.0.0.0/8", action = SplitAction.BLOCK))
        assertEquals(listOf(a, b), repo.rules.first().map { it.id })
        assertEquals(listOf(0, 1), repo.rules.first().map { it.order })

        repo.reorder(listOf(b, a))
        assertEquals(listOf(b, a), repo.rules.first().map { it.id })

        val rule = repo.rules.first().first()
        assertTrue(repo.update(rule.copy(enabled = false)))
        assertEquals(false, repo.rules.first().first().enabled)

        repo.resetToDefaults()
        assertEquals(RuDefaults.rules, repo.rules.first().map { it.copy(id = 0) })
    }

    @Test
    fun freshDatabaseIsSeededWithRuRules() = runTest {
        val seeded = inMemoryDb(seed = true)
        try {
            val rules = SplitRuleRepository(seeded.splitRuleDao()).rules.first()
            assertEquals(RuDefaults.rules, rules.map { it.copy(id = 0) })
        } finally {
            seeded.close()
        }
    }

    @Test
    fun userInfoEmbedsAsNullWhenAbsent() = runTest {
        val dao = db.subscriptionDao()
        val id = dao.insert(SubscriptionEntity(url = "u", hwid = "h", createdAt = 0))
        assertNull(dao.get(id)!!.userInfo)
        dao.update(dao.get(id)!!.copy(userInfo = UserInfoColumns(1, 2, 0, 0)))
        assertEquals(UserInfoColumns(1, 2, 0, 0), dao.get(id)!!.userInfo)
    }
}
