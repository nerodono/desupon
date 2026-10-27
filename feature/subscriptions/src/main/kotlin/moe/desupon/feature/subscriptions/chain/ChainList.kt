package moe.desupon.feature.subscriptions.chain

import moe.desupon.data.repo.SelectionRepository
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId

/** One reorderable row of a priority box: a subscription, or the boundary between the chain above it and the rest below. */
internal sealed interface ChainRow {
    val key: Any

    data class Sub(val id: SubId) : ChainRow {
        override val key: Any get() = "chain:${id.value}"
    }

    data object Boundary : ChainRow {
        override val key: Any get() = BOUNDARY_KEY
    }

    companion object {
        const val BOUNDARY_KEY = "chain-boundary"
    }
}

/** A single chain edit; [applyTo] mirrors [SelectionRepository] and returns null where it would refuse. */
internal sealed interface ChainOp {
    val sub: SubId

    fun applyTo(chain: List<SubId>): List<SubId>?

    /** [at] null appends. */
    data class Add(override val sub: SubId, val at: Int? = null) : ChainOp {
        override fun applyTo(chain: List<SubId>): List<SubId>? =
            if (sub in chain) null else chain.toMutableList().apply { add((at ?: size).coerceIn(0, size), sub) }
    }

    data class Remove(override val sub: SubId) : ChainOp {
        override fun applyTo(chain: List<SubId>): List<SubId>? = if (sub !in chain) null else chain - sub
    }

    data class Move(override val sub: SubId, val to: Int) : ChainOp {
        override fun applyTo(chain: List<SubId>): List<SubId>? =
            if (sub !in chain) null else (chain - sub).toMutableList().apply { add(to.coerceIn(0, size), sub) }
    }
}

internal suspend fun SelectionRepository.apply(set: SetId, op: ChainOp): Boolean = when (op) {
    is ChainOp.Add -> addToChain(set, op.sub, op.at)
    is ChainOp.Remove -> removeFromChain(set, op.sub)
    is ChainOp.Move -> moveInChain(set, op.sub, op.to)
}

/** Menu equivalents of dragging one subscription; each op is null when unavailable. */
internal data class ChainMenu(val sub: SubId, val index: Int?, val chainSize: Int) {
    val add: ChainOp? get() = if (index == null) ChainOp.Add(sub) else null
    val up: ChainOp? get() = index?.takeIf { it > 0 }?.let { ChainOp.Move(sub, it - 1) }
    val down: ChainOp? get() = index?.takeIf { it < chainSize - 1 }?.let { ChainOp.Move(sub, it + 1) }
    val remove: ChainOp? get() = index?.let { ChainOp.Remove(sub) }

    companion object {
        fun of(chain: List<SubId>, sub: SubId) = ChainMenu(sub, chain.indexOf(sub).takeIf { it >= 0 }, chain.size)
    }
}

internal object ChainList {
    /** Chain members in chain order, the boundary, then the other [members] in their order; chain entries outside [members] are dropped. */
    fun rows(chain: List<SubId>, members: List<SubId>): List<ChainRow> {
        val present = members.toSet()
        val head = chain.distinct().filter { it in present }
        val inHead = head.toSet()
        return head.map(ChainRow::Sub) + ChainRow.Boundary + members.filterNot { it in inHead }.map(ChainRow::Sub)
    }

    fun boundaryIndex(rows: List<ChainRow>): Int = rows.indexOf(ChainRow.Boundary)

    fun chainOf(rows: List<ChainRow>): List<SubId> =
        rows.subList(0, boundaryIndex(rows).coerceAtLeast(0)).map { (it as ChainRow.Sub).id }

    /** [rows] with the row keyed [fromKey] moved to the index of [toKey]; null when either key is unknown or they match. */
    fun move(rows: List<ChainRow>, fromKey: Any, toKey: Any): List<ChainRow>? {
        val from = rows.indexOfFirst { it.key == fromKey }
        val to = rows.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0 || from == to) return null
        return rows.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** The edit that turns [before] into the chain of [after], where only [sub] was dragged; null when its place in the chain is unchanged. */
    fun dropOp(before: List<SubId>, after: List<ChainRow>, sub: SubId): ChainOp? {
        val chain = chainOf(after)
        val was = sub in before
        val now = chain.indexOf(sub)
        return when {
            was && now < 0 -> ChainOp.Remove(sub)
            !was && now >= 0 -> ChainOp.Add(sub, now)
            was && chain != before -> ChainOp.Move(sub, now)
            else -> null
        }
    }
}
