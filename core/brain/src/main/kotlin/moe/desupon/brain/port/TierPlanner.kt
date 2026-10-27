package moe.desupon.brain.port

import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier
import moe.desupon.model.probe.ProbeStat

fun interface TierPlanner {
    fun plan(mode: NetworkMode, key: NetworkKey, pool: List<Node>, stats: Map<NodeId, ProbeStat>): TierPlan
}

/** [order] is the failover order of tiers; [topK] holds the candidates loaded into the config, best first. */
data class TierPlan(
    val order: List<Tier>,
    val topK: Map<Tier, List<Node>>,
)
