package moe.desupon.brain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.turn.TurnProfile

interface NodeRepo {
    /** Deduplicated pool across all subscriptions, unsupported nodes included. */
    val nodes: Flow<List<Node>>
}

interface SplitRuleRepo {
    /** Sorted by [SplitRule.order]. */
    val rules: Flow<List<SplitRule>>
}

interface SettingsPort {
    val settings: Flow<ConnectionSettings>
}

interface TurnProfileRepo {
    /** Profile used for the TURN tier; null when TURN is not configured. */
    val active: Flow<TurnProfile?>
}

interface NetworkMemoryRepo {
    suspend fun get(key: NetworkKey): NetworkMemory?

    suspend fun put(memory: NetworkMemory)

    suspend fun pruneOlderThan(epochMs: Long)
}

interface ProbeStatRepo {
    suspend fun forNetwork(key: NetworkKey): Map<NodeId, ProbeStat>

    suspend fun upsert(stats: Collection<ProbeStat>)
}

/** Nodes promoted to the RU tier per network after passing GET204 there under WHITELIST. */
interface TierLearningRepo {
    suspend fun promoted(key: NetworkKey): Set<NodeId>

    suspend fun promote(key: NetworkKey, nodes: Set<NodeId>)
}

/** The user's selection as the brain sees it. */
data class PoolSelection(
    val setId: SetId = SetId.ALL,
    /** Members of the active set; null for [SetId.ALL]. */
    val subs: Set<SubId>? = null,
    val choice: LocationChoice = LocationChoice.Any,
    val disabled: Set<NodeId> = emptySet(),
    /** Priority chain of the active set; see [moe.desupon.model.selection.ChainStep]. */
    val chain: List<SubId> = emptyList(),
)

interface SelectionPort {
    val selection: Flow<PoolSelection>

    /** Every subscription, any location, nothing disabled. */
    object All : SelectionPort {
        override val selection: Flow<PoolSelection> = flowOf(PoolSelection())
    }
}
