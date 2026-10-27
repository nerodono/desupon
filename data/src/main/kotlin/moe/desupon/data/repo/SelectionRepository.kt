package moe.desupon.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.port.SelectionPort
import moe.desupon.data.db.DesuponDatabase
import moe.desupon.data.db.DisabledNodeEntity
import moe.desupon.data.db.SelectionStateEntity
import moe.desupon.data.db.SubSetEntity
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.selection.SubscriptionSet
import moe.desupon.model.subscription.SubId

data class SubscriptionSetEntry(
    val set: SubscriptionSet,
    val subscriptionCount: Int,
    /** Distinct runnable nodes of the members, disabled ones included. */
    val supportedNodeCount: Int,
    /** What automatic selection over this set would use: [supportedNodeCount] minus disabled nodes. */
    val enabledNodeCount: Int,
    val isActive: Boolean,
) {
    val isAll: Boolean get() = set.id.isAll
}

/** The pinned node [from] disappeared, so the location choice became [to]. */
data class LocationFallback(val from: NodeId, val to: LocationChoice)

/**
 * Subscription sets with their priority chains, the active set, the global location choice and disabled nodes;
 * a pin that loses its node falls back to its country, else to [LocationChoice.Any].
 */
class SelectionRepository internal constructor(
    private val db: DesuponDatabase,
    private val nodes: NodeRepository,
) : SelectionPort {
    private val sets = db.subSetDao()
    private val state = db.selectionDao()
    private val subscriptions = db.subscriptionDao()
    private val reconcileLock = Mutex()

    private val _fallbacks = MutableSharedFlow<LocationFallback>(extraBufferCapacity = 8)
    val fallbacks: SharedFlow<LocationFallback> = _fallbacks.asSharedFlow()

    private val storedState: Flow<SelectionStateEntity> = state.observeState().map { it ?: SelectionStateEntity() }

    /** Ordered chain per set; sets without one are absent. */
    private val chains: Flow<Map<SetId, List<SubId>>> = sets.observeChains().map { rows ->
        rows.groupBy({ SetId(it.setId) }, { SubId(it.subId) })
    }.distinctUntilChanged()

    private val userSets: Flow<List<SubscriptionSet>> = combine(sets.observeSets(), sets.observeMembers(), chains) { rows, members, chainOf ->
        val bySet = members.groupBy({ it.setId }, { SubId(it.subId) })
        rows.map { SubscriptionSet(SetId(it.id), it.name, it.position, bySet[it.id].orEmpty().toSet(), chainOf[SetId(it.id)].orEmpty()) }
    }

    private val allChain: Flow<List<SubId>> = chains.map { it[SetId.ALL].orEmpty() }.distinctUntilChanged()

    private val allSubIds: Flow<Set<SubId>> = subscriptions.observeAll().map { rows -> rows.mapTo(LinkedHashSet()) { SubId(it.id) } }

    val disabledNodes: Flow<Set<NodeId>> = state.observeDisabled().map { ids -> ids.mapTo(HashSet(), ::NodeId) }.distinctUntilChanged()

    /** [SetId.ALL] when nothing is stored or the stored set is gone. */
    val activeSetId: Flow<SetId> = combine(storedState, userSets) { s, user -> resolveActive(s.activeSetId, user) }.distinctUntilChanged()

    val location: Flow<LocationChoice> = storedState.map { LocationChoice.decode(it.location) }.distinctUntilChanged()

    /** [SetId.ALL] first, then user sets by position. */
    val subscriptionSets: Flow<List<SubscriptionSetEntry>> =
        combine(combine(userSets, allSubIds, allChain, ::Triple), nodes.nodes, disabledNodes, activeSetId) { (user, all, allChain), pool, disabled, active ->
            val builtIn = SubscriptionSet(SetId.ALL, "", -1, all, allChain)
            (listOf(builtIn) + user).map { set -> entry(set, pool, disabled, active, isAll = set.id.isAll) }
        }.distinctUntilChanged()

    /** Ordered chain of [id], [SetId.ALL] included; empty for a set without one or an unknown set. */
    fun observeChain(id: SetId): Flow<List<SubId>> = chains.map { it[id].orEmpty() }.distinctUntilChanged()

    suspend fun chain(id: SetId): List<SubId> = sets.chain(id.value).map(::SubId)

    /** Replaces the chain of [id]; false for an unknown set, a subscription outside it or a duplicate. Empty clears it. */
    suspend fun setChain(id: SetId, chain: List<SubId>): Boolean = db.withTransaction {
        val members = membersOf(id) ?: return@withTransaction false
        if (chain.toSet().size != chain.size || chain.any { it.value !in members }) return@withTransaction false
        sets.replaceChain(id.value, chain.map { it.value })
        true
    }

    /** Inserts [sub] at [at] (clamped; null appends); false when it is already in the chain or [setChain] would refuse. */
    suspend fun addToChain(id: SetId, sub: SubId, at: Int? = null): Boolean = editChain(id) { chain ->
        if (sub in chain) null else chain.toMutableList().apply { add((at ?: size).coerceIn(0, size), sub) }
    }

    /** False when [sub] is not in the chain of [id]. */
    suspend fun removeFromChain(id: SetId, sub: SubId): Boolean = editChain(id) { chain ->
        if (sub !in chain) null else chain - sub
    }

    /** Moves [sub] to index [to] (clamped); false when [sub] is not in the chain of [id]. */
    suspend fun moveInChain(id: SetId, sub: SubId, to: Int): Boolean = editChain(id) { chain ->
        if (sub !in chain) null else (chain - sub).toMutableList().apply { add(to.coerceIn(0, size), sub) }
    }

    private suspend fun editChain(id: SetId, edit: (List<SubId>) -> List<SubId>?): Boolean = db.withTransaction {
        val next = edit(chain(id)) ?: return@withTransaction false
        setChain(id, next)
    }

    /** Every subscription for [SetId.ALL]; null for an unknown set. */
    private suspend fun membersOf(id: SetId): Set<Long>? =
        if (id.isAll) existingSubs() else sets.getSet(id.value)?.let { sets.members(it.id).toSet() }

    /** One transactional read per change, so a running session never sees half of a multi-table write. */
    override val selection: Flow<PoolSelection> =
        db.invalidationTracker.createFlow(*SELECTION_TABLES)
            .map { db.withTransaction { currentSelection(currentState()) } }
            .distinctUntilChanged()

    /** Appended after the last set; null for a blank [name]. Unknown subscriptions are ignored. */
    suspend fun createSet(name: String, members: Set<SubId> = emptySet()): SetId? {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return null
        return db.withTransaction {
            val id = sets.insert(SubSetEntity(name = clean, position = sets.maxPosition() + 1))
            sets.replaceMembers(id, members.map { it.value }, existingSubs())
            SetId(id)
        }
    }

    /** False for [SetId.ALL], a missing set or a blank name. */
    suspend fun renameSet(id: SetId, name: String): Boolean {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return false
        if (id.isAll) return false
        val row = sets.getSet(id.value) ?: return false
        return sets.update(row.copy(name = clean)) > 0
    }

    /** False for [SetId.ALL] or a missing set; deleting the active set activates [SetId.ALL]. */
    suspend fun deleteSet(id: SetId): Boolean {
        if (id.isAll) return false
        return db.withTransaction {
            if (sets.deleteSet(id.value) == 0) return@withTransaction false
            val s = currentState()
            if (s.activeSetId == id.value) state.putState(s.copy(activeSetId = SetId.ALL.value))
            true
        }
    }

    /** User sets follow [ids]; ones missing from it keep their relative order after them, [SetId.ALL] stays first. */
    suspend fun reorderSets(ids: List<SetId>) {
        db.withTransaction {
            val rank = ids.withIndex().associate { (i, id) -> id.value to i }
            val sorted = sets.getSets().sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.position }, { it.id }))
            sets.updateAll(sorted.mapIndexed { i, s -> s.copy(position = i) })
        }
    }

    /** False for [SetId.ALL] or a missing set; may leave the set empty. */
    suspend fun setMembers(id: SetId, members: Set<SubId>): Boolean {
        if (id.isAll) return false
        return db.withTransaction {
            if (sets.getSet(id.value) == null) return@withTransaction false
            sets.replaceMembers(id.value, members.map { it.value }, existingSubs())
            true
        }
    }

    /** User sets containing [sub]; [SetId.ALL] and unknown ids are ignored. */
    suspend fun setSubscriptionSets(sub: SubId, setIds: Set<SetId>) {
        if (subscriptions.get(sub.value) == null) return
        sets.replaceSetsOf(sub.value, setIds.filterNot { it.isAll }.map { it.value })
    }

    suspend fun setsOf(sub: SubId): Set<SetId> = sets.setsOf(sub.value).mapTo(HashSet(), ::SetId)

    fun observeSetsOf(sub: SubId): Flow<Set<SetId>> = sets.observeSetsOf(sub.value).map { ids -> ids.mapTo(HashSet(), ::SetId) }

    /** False when [id] names no set. */
    suspend fun setActiveSet(id: SetId): Boolean = db.withTransaction {
        if (!id.isAll && sets.getSet(id.value) == null) return@withTransaction false
        state.putState(currentState().copy(activeSetId = id.value))
        true
    }

    /** A pin must name an enabled runnable node of any subscription; its country is kept as the fallback. */
    suspend fun setLocation(choice: LocationChoice): Boolean {
        val normalized = when (choice) {
            LocationChoice.Any, is LocationChoice.Node -> choice
            is LocationChoice.Country -> choice.iso.trim().takeIf { it.isNotEmpty() }?.let(LocationChoice::country) ?: return false
        }
        val pinCountry = if (normalized is LocationChoice.Node) {
            val node = pinnable(normalized.id) ?: return false
            node.country?.let(LocationChoice::country)?.iso
        } else {
            null
        }
        reconcileLock.withLock {
            db.withTransaction { state.putState(currentState().copy(location = LocationChoice.encode(normalized), pinCountry = pinCountry)) }
        }
        return true
    }

    /** Disabling the pinned node triggers its fallback. */
    suspend fun setNodeEnabled(id: NodeId, enabled: Boolean) {
        if (enabled) {
            state.enable(id.hash)
            return
        }
        val fallback = reconcileLock.withLock {
            db.withTransaction {
                state.disable(DisabledNodeEntity(id.hash))
                fallbackLocked()
            }
        }
        fallback?.let { _fallbacks.emit(it) }
    }

    /** Re-points a pin whose node is gone or disabled; call after anything that changes the pool. */
    suspend fun reconcile() {
        val fallback = reconcileLock.withLock { db.withTransaction { fallbackLocked() } }
        fallback?.let { _fallbacks.emit(it) }
    }

    private suspend fun fallbackLocked(): LocationFallback? {
        val s = currentState()
        val pin = LocationChoice.decode(s.location) as? LocationChoice.Node ?: return null
        val pool = nodes.snapshot()
        val sel = currentSelection(s)
        if (Pools.pinned(pool, sel) != null) return null
        val country = s.pinCountry?.let(LocationChoice::country)?.takeIf { Pools.effective(pool, sel.copy(choice = it)).isNotEmpty() }
        val to: LocationChoice = country ?: LocationChoice.Any
        state.putState(s.copy(location = LocationChoice.encode(to), pinCountry = null))
        return LocationFallback(pin.id, to)
    }

    private suspend fun pinnable(id: NodeId): Node? {
        val disabled = state.getDisabled().toSet()
        return nodes.snapshot().firstOrNull { it.id == id && Pools.usable(it) && it.id.hash !in disabled }
    }

    private suspend fun currentSelection(s: SelectionStateEntity): PoolSelection {
        val active = s.activeSetId.takeIf { it != SetId.ALL.value && sets.getSet(it) != null }
        return PoolSelection(
            setId = active?.let(::SetId) ?: SetId.ALL,
            subs = active?.let { sets.members(it).mapTo(HashSet(), ::SubId) },
            choice = LocationChoice.decode(s.location),
            disabled = state.getDisabled().mapTo(HashSet(), ::NodeId),
            chain = sets.chain(active ?: SetId.ALL.value).map(::SubId),
        )
    }

    private suspend fun currentState(): SelectionStateEntity = state.getState() ?: SelectionStateEntity()

    private suspend fun existingSubs(): Set<Long> = subscriptions.getAll().mapTo(HashSet()) { it.id }

    private fun resolveActive(stored: Long, user: List<SubscriptionSet>): SetId =
        user.firstOrNull { it.id.value == stored }?.id ?: SetId.ALL

    private companion object {
        val SELECTION_TABLES = arrayOf("selection_state", "sub_set", "sub_set_member", "disabled_node", "sub_chain")
    }

    private fun entry(set: SubscriptionSet, pool: List<Node>, disabled: Set<NodeId>, active: SetId, isAll: Boolean): SubscriptionSetEntry {
        val subs = if (isAll) null else set.members
        return SubscriptionSetEntry(
            set = set,
            subscriptionCount = set.members.size,
            supportedNodeCount = Pools.setPool(pool, PoolSelection(set.id, subs)).size,
            enabledNodeCount = Pools.setPool(pool, PoolSelection(set.id, subs, disabled = disabled)).size,
            isActive = set.id == active,
        )
    }
}
