package moe.desupon.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.NodeRepo
import moe.desupon.data.db.NodeDao
import moe.desupon.data.db.PoolRow
import moe.desupon.data.db.toNode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.parser.NodePool

class NodeRepository internal constructor(private val dao: NodeDao) : NodeRepo {
    /** Subscriptions in creation order, nodes in body order, merged by [NodeId]. */
    override val nodes: Flow<List<Node>> = dao.observePool().map(::toPool).distinctUntilChanged()

    suspend fun snapshot(): List<Node> = toPool(dao.getPool())

    /** Why each unsupported node cannot run, as reported by the parser. */
    suspend fun unsupportedReasons(): Map<NodeId, String> =
        dao.unsupportedReasons().associate { NodeId(it.id) to it.unsupportedReason }

    private fun toPool(rows: List<PoolRow>): List<Node> =
        NodePool.merge(rows.groupBy { it.subId }.values.map { group -> group.mapNotNull { it.node.toNode(it.subId) } })
}
