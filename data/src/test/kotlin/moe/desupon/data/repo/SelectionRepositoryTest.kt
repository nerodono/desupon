package moe.desupon.data.repo

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.PoolSelection
import moe.desupon.data.db.NodeEntity
import moe.desupon.data.db.SubscriptionEntity
import moe.desupon.data.inMemoryDb
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SelectionRepositoryTest {
    private val db = inMemoryDb()
    private val nodeRepo = NodeRepository(db.nodeDao())
    private val repo = SelectionRepository(db, nodeRepo)

    @After
    fun close() = db.close()

    private suspend fun sub(): SubId = SubId(db.subscriptionDao().insert(SubscriptionEntity(url = "https://s.example.com/${System.nanoTime()}", hwid = "h", createdAt = 0)))

    private fun node(id: String, country: String? = null, capability: String = "SUPPORTED") = NodeEntity(
        id = id, protocol = "TROJAN", displayName = id, country = country, capability = capability,
        outboundJson = "{}", isEndpoint = false, udpOnly = false, labels = "", unsupportedReason = null,
    )

    private suspend fun nodes(sub: SubId, vararg n: NodeEntity) = db.nodeDao().replaceForSubscription(sub.value, n.toList())

    @Test
    fun defaultsAreEverySubscriptionAndAnyLocation() = runTest {
        val a = sub()
        nodes(a, node("n1"), node("n2", capability = "UNSUPPORTED"))
        val sets = repo.subscriptionSets.first()
        val all = sets.single()
        assertTrue(all.isAll && all.isActive)
        assertEquals(1, all.subscriptionCount)
        assertEquals(1, all.supportedNodeCount)
        assertEquals(SetId.ALL, repo.activeSetId.first())
        assertEquals(LocationChoice.Any, repo.location.first())
        assertEquals(PoolSelection(), repo.selection.first())
    }

    @Test
    fun setsCountMembersAndNodesAndKeepTheirOrder() = runTest {
        val a = sub()
        val b = sub()
        nodes(a, node("n1"), node("n2"))
        nodes(b, node("n2"), node("n3"))
        val x = repo.createSet(" Работа ", setOf(a, SubId(999)))!!
        val y = repo.createSet("Дом", setOf(a, b))!!
        assertNull(repo.createSet("  "))
        repo.setNodeEnabled(NodeId("n2"), false)

        val sets = repo.subscriptionSets.first()
        assertEquals(listOf(SetId.ALL, x, y), sets.map { it.set.id })
        val work = sets[1]
        assertEquals("Работа", work.set.name)
        assertEquals(setOf(a), work.set.members)
        assertEquals(2, work.supportedNodeCount)
        assertEquals(1, work.enabledNodeCount)
        assertEquals(3, sets[2].supportedNodeCount)

        repo.reorderSets(listOf(y, x))
        assertEquals(listOf(SetId.ALL, y, x), repo.subscriptionSets.first().map { it.set.id })
        assertTrue(repo.renameSet(x, "Офис"))
        assertFalse(repo.renameSet(SetId.ALL, "Нет"))
        assertFalse(repo.deleteSet(SetId.ALL))
        assertEquals("Офис", repo.subscriptionSets.first().first { it.set.id == x }.set.name)
    }

    @Test
    fun activeSetDrivesTheSelectionAndFallsBackToAllWhenDeleted() = runTest {
        val a = sub()
        val b = sub()
        val x = repo.createSet("X", setOf(a))!!
        assertFalse(repo.setActiveSet(SetId(42)))
        assertTrue(repo.setActiveSet(x))
        repo.setNodeEnabled(NodeId("n9"), false)
        assertEquals(PoolSelection(x, setOf(a), LocationChoice.Any, setOf(NodeId("n9"))), repo.selection.first())

        repo.setMembers(x, setOf(a, b))
        assertEquals(setOf(a, b), repo.selection.first().subs)
        repo.setMembers(x, emptySet())
        assertEquals(emptySet(), repo.selection.first().subs)

        assertTrue(repo.deleteSet(x))
        assertEquals(SetId.ALL, repo.activeSetId.first())
        assertNull(repo.selection.first().subs)
    }

    @Test
    fun deletingASubscriptionRemovesItFromSets() = runTest {
        val a = sub()
        val b = sub()
        val x = repo.createSet("X", setOf(a, b))!!
        repo.setSubscriptionSets(b, setOf(x, SetId.ALL, SetId(77)))
        assertEquals(setOf(x), repo.setsOf(b))
        db.subscriptionDao().delete(a.value)
        assertEquals(setOf(b), repo.subscriptionSets.first().first { it.set.id == x }.set.members)
    }

    @Test
    fun locationIsGlobalAndValidatesPins() = runTest {
        val a = sub()
        nodes(a, node("n1", "nl"), node("bad", capability = "UNSUPPORTED"))
        val x = repo.createSet("X")!!
        assertTrue(repo.setLocation(LocationChoice.Country(" de")))
        assertEquals(LocationChoice.Country("DE"), repo.location.first())
        repo.setActiveSet(x)
        assertEquals(LocationChoice.Country("DE"), repo.location.first(), "the choice does not follow the set")

        assertFalse(repo.setLocation(LocationChoice.Node(NodeId("missing"))))
        assertFalse(repo.setLocation(LocationChoice.Node(NodeId("bad"))))
        repo.setNodeEnabled(NodeId("n1"), false)
        assertFalse(repo.setLocation(LocationChoice.Node(NodeId("n1"))))
        repo.setNodeEnabled(NodeId("n1"), true)
        assertTrue(repo.setLocation(LocationChoice.Node(NodeId("n1"))), "a pin may sit outside the active set")
        assertEquals(LocationChoice.Node(NodeId("n1")), repo.selection.first().choice)
    }

    @Test
    fun pinThatLosesItsNodeFallsBackToItsCountryThenAny() = runTest {
        val a = sub()
        nodes(a, node("n1", "NL"), node("n2", "NL"), node("d1", "DE"))
        repo.setLocation(LocationChoice.Node(NodeId("n1")))
        val event = async(start = CoroutineStart.UNDISPATCHED) { repo.fallbacks.first() }

        nodes(a, node("n2", "NL"), node("d1", "DE"))
        repo.reconcile()
        assertEquals(LocationFallback(NodeId("n1"), LocationChoice.Country("NL")), event.await())
        assertEquals(LocationChoice.Country("NL"), repo.location.first())

        repo.setLocation(LocationChoice.Node(NodeId("d1")))
        nodes(a, node("n2", "NL"))
        repo.reconcile()
        assertEquals(LocationChoice.Any, repo.location.first())

        repo.reconcile()
        assertEquals(LocationChoice.Any, repo.location.first())
    }

    @Test
    fun countryFallbackNeedsTheCountryInTheActiveSet() = runTest {
        val a = sub()
        val b = sub()
        nodes(a, node("n1", "NL"))
        nodes(b, node("n2", "NL"))
        val onlyA = repo.createSet("A", setOf(a))!!
        repo.setActiveSet(onlyA)
        repo.setLocation(LocationChoice.Node(NodeId("n2")))
        db.subscriptionDao().delete(b.value)
        db.nodeDao().deleteOrphans()
        repo.reconcile()
        assertEquals(LocationChoice.Country("NL"), repo.location.first())

        repo.setLocation(LocationChoice.Node(NodeId("n1")))
        repo.setMembers(onlyA, emptySet())
        repo.setNodeEnabled(NodeId("n1"), false)
        assertEquals(LocationChoice.Any, repo.location.first())
    }

    @Test
    fun disablingThePinnedNodeNeverShowsAHalfWrittenSelection() = runTest {
        val a = sub()
        nodes(a, node("n1", "NL"), node("n2", "NL"))
        repo.setLocation(LocationChoice.Node(NodeId("n1")))
        repo.selection.test {
            assertEquals(LocationChoice.Node(NodeId("n1")), awaitItem().choice)
            repo.setNodeEnabled(NodeId("n1"), false)
            val next = awaitItem()
            assertEquals(LocationChoice.Country("NL"), next.choice)
            assertEquals(setOf(NodeId("n1")), next.disabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun disabledFlagSurvivesUpdatesAndDiesWithItsNode() = runTest {
        val a = sub()
        nodes(a, node("n1"), node("n2"))
        repo.setNodeEnabled(NodeId("n1"), false)
        nodes(a, node("n1"), node("n3"))
        assertEquals(setOf(NodeId("n1")), repo.disabledNodes.first())
        nodes(a, node("n3"))
        assertTrue(repo.disabledNodes.first().isEmpty())
        assertNotNull(repo.subscriptionSets.first().single())
    }

    @Test
    fun chainsAreValidatedAndEditedInOrder() = runTest {
        val a = sub()
        val b = sub()
        val c = sub()
        val x = repo.createSet("X", setOf(a, b))!!
        assertEquals(emptyList(), repo.chain(x), "a new set has no chain")

        assertFalse(repo.setChain(x, listOf(a, c)), "only members")
        assertFalse(repo.setChain(x, listOf(a, a)), "no duplicates")
        assertFalse(repo.setChain(SetId(42), listOf(a)))
        assertTrue(repo.setChain(x, listOf(b)))
        assertTrue(repo.addToChain(x, a, at = 0))
        assertFalse(repo.addToChain(x, a))
        assertEquals(listOf(a, b), repo.chain(x))
        assertTrue(repo.moveInChain(x, a, 5))
        assertEquals(listOf(b, a), repo.observeChain(x).first())
        assertTrue(repo.removeFromChain(x, b))
        assertFalse(repo.removeFromChain(x, b))
        assertFalse(repo.moveInChain(x, b, 0))
        assertEquals(listOf(a), repo.subscriptionSets.first().first { it.set.id == x }.set.chain)

        assertTrue(repo.setChain(SetId.ALL, listOf(c, a)), "the built-in set chains any subscription")
        val sets = repo.subscriptionSets.first()
        assertEquals(listOf(c, a), sets.first { it.isAll }.set.chain)
        assertEquals(listOf(a), sets.first { it.set.id == x }.set.chain, "chains are per set")
        assertEquals(emptyList(), repo.chain(repo.createSet("Y", setOf(a, b, c))!!), "a new set does not inherit the global chain")
    }

    @Test
    fun theActiveSetsChainReachesTheSelection() = runTest {
        val a = sub()
        val b = sub()
        val x = repo.createSet("X", setOf(a, b))!!
        repo.setChain(SetId.ALL, listOf(b))
        repo.setChain(x, listOf(a, b))
        assertEquals(listOf(b), repo.selection.first().chain)
        repo.setActiveSet(x)
        repo.selection.test {
            assertEquals(listOf(a, b), awaitItem().chain)
            repo.moveInChain(x, b, 0)
            assertEquals(listOf(b, a), awaitItem().chain)
            db.subscriptionDao().delete(b.value)
            assertEquals(listOf(a), awaitItem().chain)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leavingOrDeletingASetDropsChainLinks() = runTest {
        val a = sub()
        val b = sub()
        val c = sub()
        val x = repo.createSet("X", setOf(a, b, c))!!
        repo.setChain(x, listOf(c, b, a))
        repo.setChain(SetId.ALL, listOf(a, b))

        repo.setMembers(x, setOf(a, c))
        assertEquals(listOf(c, a), repo.chain(x))
        repo.setSubscriptionSets(a, emptySet())
        assertEquals(listOf(c), repo.chain(x))
        assertEquals(listOf(a, b), repo.chain(SetId.ALL), "membership edits leave the global chain alone")

        db.subscriptionDao().delete(a.value)
        assertEquals(listOf(b), repo.chain(SetId.ALL))

        assertTrue(repo.deleteSet(x))
        assertEquals(emptyList(), repo.chain(x))
        assertEquals(listOf(b), repo.chain(SetId.ALL))
    }
}
