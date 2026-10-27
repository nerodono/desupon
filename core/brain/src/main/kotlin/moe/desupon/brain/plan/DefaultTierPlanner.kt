package moe.desupon.brain.plan

import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.TierPlan
import moe.desupon.brain.port.TierPlanner
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import moe.desupon.model.probe.ProbeStat

/** [Node.labels] values the planner reads; the first four match what the subscription parser derives from names. */
object TierLabels {
    const val ONLY_MOBILE = "ONLY_MOBILE"
    const val ONLY_WIFI = "ONLY_WIFI"
    const val LTE = "LTE"
    const val WHITELIST = "WHITELIST"

    /** Added by the brain to nodes that passed GET204 under WHITELIST on the planned network. */
    const val LEARNED_RU = "LEARNED_RU"
}

data class PlannerConfig(
    val perTier: Int = 12,
    /** WireGuard-family endpoints per tier; each idle one still costs radio wake-ups. */
    val endpointsPerTier: Int = 3,
    /** DIRECT candidates kept under WHITELIST, enough to notice the filter lifting. */
    val whitelistDirectSample: Int = 2,
    /** A success older than this ranks as unknown, not verified. */
    val verifiedTtlMs: Long = 24 * 60 * 60_000L,
)

/** Plans only [Tier.DIRECT] and [Tier.RU]. */
class DefaultTierPlanner(
    private val clock: Clock,
    private val config: PlannerConfig = PlannerConfig(),
) : TierPlanner {
    override fun plan(mode: NetworkMode, key: NetworkKey, pool: List<Node>, stats: Map<NodeId, ProbeStat>): TierPlan {
        val now = clock.nowMs()
        val byTier = pool
            .asSequence()
            .filter { it.capability == Capability.SUPPORTED && it.protocol != Protocol.FREETURN && fits(it, key.transport) }
            .distinctBy { it.id }
            .groupBy { tierOf(it, key.transport) }
        val ranking = ranking(stats, key.transport, now)
        val topK = LinkedHashMap<Tier, List<Node>>()
        for (tier in tierOrder(mode)) {
            val limit = if (mode == NetworkMode.WHITELIST && tier == Tier.DIRECT) config.whitelistDirectSample else config.perTier
            val picked = take(byTier[tier].orEmpty().sortedWith(ranking), limit)
            if (picked.isNotEmpty()) topK[tier] = picked
        }
        return TierPlan(topK.keys.toList(), topK)
    }

    private fun take(ranked: List<Node>, limit: Int): List<Node> {
        val out = ArrayList<Node>(minOf(limit, ranked.size))
        var endpoints = 0
        for (node in ranked) {
            if (out.size >= limit) break
            if (node.isWireGuardFamily()) {
                if (endpoints >= config.endpointsPerTier) continue
                endpoints++
            }
            out += node
        }
        return out
    }

    private fun ranking(stats: Map<NodeId, ProbeStat>, transport: Transport, now: Long): Comparator<Node> =
        NodeScore.comparator<Node>({ stats[it.id] }, now, config.verifiedTtlMs)
            .thenBy { hintRank(it, transport) }
            .thenBy { stats[it.id]?.lastOkAtMs?.let { ok -> -ok } ?: Long.MAX_VALUE }

    companion object {
        const val RU_COUNTRY = "RU"

        fun tierOrder(mode: NetworkMode): List<Tier> = when (mode) {
            NetworkMode.OPEN, NetworkMode.UNKNOWN -> listOf(Tier.DIRECT, Tier.RU)
            NetworkMode.WHITELIST, NetworkMode.OFFLINE -> listOf(Tier.RU, Tier.DIRECT)
        }

        fun tierOf(node: Node, transport: Transport): Tier =
            if (hintRank(node, transport) < NO_HINT) Tier.RU else Tier.DIRECT

        fun fits(node: Node, transport: Transport): Boolean {
            val mobile = TierLabels.ONLY_MOBILE in node.labels
            val wifi = TierLabels.ONLY_WIFI in node.labels
            return when {
                mobile && !wifi -> transport == Transport.CELLULAR
                wifi && !mobile -> transport != Transport.CELLULAR
                else -> true
            }
        }

        const val NO_HINT = 4

        /** Lower is a stronger sign the node is reachable under RU whitelists. */
        fun hintRank(node: Node, transport: Transport): Int = when {
            TierLabels.LEARNED_RU in node.labels -> 0
            TierLabels.WHITELIST in node.labels -> 1
            TierLabels.LTE in node.labels && transport == Transport.CELLULAR -> 2
            node.country.equals(RU_COUNTRY, ignoreCase = true) -> 3
            else -> NO_HINT
        }

        private fun Node.isWireGuardFamily(): Boolean =
            isEndpoint || protocol == Protocol.WIREGUARD || protocol == Protocol.AMNEZIAWG
    }
}
