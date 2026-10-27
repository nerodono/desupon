package moe.desupon.brain.pool

import moe.desupon.brain.plan.DefaultTierPlanner
import moe.desupon.brain.plan.NodeScore
import moe.desupon.brain.plan.PlannerConfig
import moe.desupon.brain.plan.Standing
import moe.desupon.brain.port.PoolSelection
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.subscription.SubId

/** Which nodes a [PoolSelection] puts in play; shared by the brain, off-tunnel probes and the location list. */
object Pools {
    /** Runnable by the main config; FreeTurn is a tier of its own. */
    fun usable(node: Node): Boolean = node.capability == Capability.SUPPORTED && node.protocol != Protocol.FREETURN

    /** Enabled usable nodes of the active set, deduplicated, in pool order. */
    fun setPool(all: List<Node>, sel: PoolSelection): List<Node> = all
        .asSequence()
        .filter { usable(it) && it.id !in sel.disabled }
        .filter { n -> sel.subs == null || n.subs.any { it in sel.subs } }
        .distinctBy { it.id }
        .toList()

    /** Nodes selection runs over: the set pool, narrowed to a country, or the pinned node from any subscription. */
    fun effective(all: List<Node>, sel: PoolSelection): List<Node> = when (val c = sel.choice) {
        LocationChoice.Any -> setPool(all, sel)
        is LocationChoice.Country -> setPool(all, sel).filter { it.country.equals(c.iso, ignoreCase = true) }
        is LocationChoice.Node -> pinned(all, sel)?.let(::listOf).orEmpty()
    }

    /** The pinned node when it is enabled and usable, whatever the active set. */
    fun pinned(all: List<Node>, sel: PoolSelection): Node? {
        val c = sel.choice as? LocationChoice.Node ?: return null
        return all.firstOrNull { it.id == c.id && usable(it) && it.id !in sel.disabled }
    }

    /** What an on-demand pool probe covers: the set pool plus a pin outside it. */
    fun probeTargets(all: List<Node>, sel: PoolSelection): List<Node> {
        val set = setPool(all, sel)
        val pin = pinned(all, sel)
        return if (pin == null || set.any { it.id == pin.id }) set else set + pin
    }

    /** Nodes a UI-driven probe between full sweeps covers by rank, besides the kept ones. */
    const val UI_TOP = 20

    /**
     * [keep] first, then at most [limit] more of [targets]: the nodes of chain step [step] (the first non-empty one when null),
     * then the other steps in chain order, best-scored first inside each.
     */
    fun hotTargets(
        targets: List<Node>,
        chain: List<SubId>,
        step: Int?,
        keep: List<NodeId>,
        stats: Map<NodeId, ProbeStat>,
        limit: Int = UI_TOP,
        nowMs: Long = System.currentTimeMillis(),
        verifiedTtlMs: Long = PlannerConfig().verifiedTtlMs,
    ): List<Node> {
        val stepOf = targets.associate { it.id to ChainStep.indexOf(chain, it.subs) }
        val active = step ?: stepOf.values.minOrNull()
        val byId = targets.associateBy { it.id }
        val kept = keep.mapNotNull { byId[it] }.distinctBy { it.id }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val ranked = targets
            .filter { it.id !in keptIds }
            .sortedWith(
                compareBy<Node> { if (stepOf.getValue(it.id) == active) -1 else stepOf.getValue(it.id) }
                    .then(NodeScore.comparator({ stats[it.id] }, nowMs, verifiedTtlMs)),
            )
        return kept + ranked.distinctBy { it.id }.take(limit)
    }

    /**
     * Order of a pool sweep: [lastGood], then chain steps in order; inside a step the RU tier first under WHITELIST,
     * then usable nodes by score, hinted unknown ones, other unknown ones, failing ones.
     */
    fun sweepOrder(
        nodes: List<Node>,
        stats: Map<NodeId, ProbeStat>,
        chain: List<SubId>,
        lastGood: NodeId?,
        mode: NetworkMode,
        transport: Transport,
        nowMs: Long,
        verifiedTtlMs: Long = PlannerConfig().verifiedTtlMs,
    ): List<Node> {
        val noHint = DefaultTierPlanner.NO_HINT
        fun cls(n: Node): Int = when (NodeScore.standing(stats[n.id], nowMs, verifiedTtlMs)) {
            Standing.VERIFIED, Standing.SUSPECT -> 0
            Standing.UNKNOWN -> if (DefaultTierPlanner.hintRank(n, transport) < noHint) 1 else 2
            Standing.FAILING -> 3
        }
        return nodes.distinctBy { it.id }.sortedWith(
            compareBy<Node>(
                { if (it.id == lastGood) 0 else 1 },
                { ChainStep.indexOf(chain, it.subs) },
                { if (mode == NetworkMode.WHITELIST && DefaultTierPlanner.tierOf(it, transport) != Tier.RU) 1 else 0 },
                { cls(it) },
            )
                .thenBy { n -> if (cls(n) == 0) NodeScore.score(stats.getValue(n.id), nowMs, verifiedTtlMs) else 0.0 }
                .thenBy { n -> if (cls(n) == 1) DefaultTierPlanner.hintRank(n, transport) else 0 }
                .thenBy { n -> if (cls(n) == 3) stats.getValue(n.id).failStreak else 0 },
        )
    }
}
