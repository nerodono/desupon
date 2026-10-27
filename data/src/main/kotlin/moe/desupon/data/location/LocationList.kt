package moe.desupon.data.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.PoolSelection
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import java.util.Locale
import kotlin.math.roundToInt

enum class ProbeState { OK, CHECKING, DEAD, UNKNOWN }

/** [title] is null when the subscription has neither a user name nor a provider title. */
data class SubscriptionRef(val id: SubId, val title: String?)

data class LocationNode(
    val id: NodeId,
    val displayName: String,
    /** Upper-case ISO code; null for nodes without a known country. */
    val country: String?,
    val protocol: Protocol,
    val subscriptions: List<SubscriptionRef>,
    /** Smoothed RTT on the current network; null unless [state] is [ProbeState.OK]. */
    val rttMs: Int?,
    val state: ProbeState,
    val inActiveSet: Boolean,
)

data class LocationCountry(
    val iso: String,
    val nodeCount: Int,
    val bestRttMs: Int?,
    /** CHECKING while any node is probed, else OK when any answers, DEAD when all failed, UNKNOWN otherwise. */
    val state: ProbeState,
    /** Pool order. */
    val nodes: List<LocationNode>,
)

/** The location sheet's data for the active set: enabled runnable nodes only; sorting is the UI's. */
data class LocationListData(
    val setId: SetId,
    val choice: LocationChoice,
    /** By ISO code. */
    val countries: List<LocationCountry>,
    /** Nodes without a country. */
    val other: List<LocationNode>,
    /** A pinned node outside the active set, so it can still show as selected. */
    val pinnedOutsideSet: LocationNode?,
    /** Network the RTTs and states belong to; null without a network. */
    val network: NetworkKey?,
)

object LocationListBuilder {
    fun build(
        pool: List<Node>,
        subscriptions: List<SubscriptionEntry>,
        selection: PoolSelection,
        stats: Map<NodeId, ProbeStat>,
        probing: Set<NodeId>,
        network: NetworkKey?,
    ): LocationListData {
        val titles = subscriptions.associate { it.info.id to it.info.title }
        fun row(node: Node, inSet: Boolean): LocationNode {
            val state = stateOf(node.id, stats, probing)
            return LocationNode(
                id = node.id,
                displayName = node.displayName,
                country = node.country?.uppercase(Locale.ROOT),
                protocol = node.protocol,
                subscriptions = node.subs.sortedBy { it.value }.map { SubscriptionRef(it, titles[it]) },
                rttMs = if (state == ProbeState.OK) stats[node.id]?.ewmaRttMs?.roundToInt() else null,
                state = state,
                inActiveSet = inSet,
            )
        }
        val set = Pools.setPool(pool, selection).map { row(it, inSet = true) }
        val pin = Pools.pinned(pool, selection)?.takeIf { p -> set.none { it.id == p.id } }?.let { row(it, inSet = false) }
        val countries = set.filter { it.country != null }.groupBy { it.country!! }.toSortedMap().map { (iso, nodes) ->
            val ok = nodes.filter { it.state == ProbeState.OK }
            LocationCountry(
                iso = iso,
                nodeCount = nodes.size,
                bestRttMs = ok.mapNotNull { it.rttMs }.minOrNull(),
                state = when {
                    nodes.any { it.state == ProbeState.CHECKING } -> ProbeState.CHECKING
                    ok.isNotEmpty() -> ProbeState.OK
                    nodes.all { it.state == ProbeState.DEAD } -> ProbeState.DEAD
                    else -> ProbeState.UNKNOWN
                },
                nodes = nodes,
            )
        }
        return LocationListData(selection.setId, selection.choice, countries, set.filter { it.country == null }, pin, network)
    }

    private fun stateOf(id: NodeId, stats: Map<NodeId, ProbeStat>, probing: Set<NodeId>): ProbeState {
        if (id in probing) return ProbeState.CHECKING
        val s = stats[id] ?: return ProbeState.UNKNOWN
        // One failure after a success is not yet dead, matching the planner's suspect standing.
        return when {
            s.failStreak >= 2 || (s.failStreak > 0 && s.ok == 0) -> ProbeState.DEAD
            s.ok > 0 -> ProbeState.OK
            else -> ProbeState.UNKNOWN
        }
    }
}

/** Live [LocationListData]: re-emits on pool, selection, probe stat, network and probing changes. */
class LocationListSource(
    private val nodes: Flow<List<Node>>,
    private val subscriptions: Flow<List<SubscriptionEntry>>,
    private val selection: Flow<PoolSelection>,
    private val stats: (NetworkKey) -> Flow<Map<NodeId, ProbeStat>>,
    private val network: Flow<NetworkKey?>,
    private val probing: Flow<Set<NodeId>>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val data: Flow<LocationListData> = run {
        val keyed = network.distinctUntilChanged().flatMapLatest { key ->
            if (key == null) flowOf<Pair<NetworkKey?, Map<NodeId, ProbeStat>>>(null to emptyMap()) else stats(key).map { key to it }
        }
        combine(nodes, subscriptions, selection, keyed, probing) { pool, subs, sel, (key, st), checking ->
            LocationListBuilder.build(pool, subs, sel, st, checking, key)
        }.distinctUntilChanged()
    }
}
