package moe.desupon.feature.subscriptions.chain

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.desupon.model.subscription.SubId

/** Rows of a priority box while the user drags; upstream changes arriving mid-drag are applied on drop. */
@Stable
internal class ChainDragState(chain: List<SubId>, members: List<SubId>) {
    private var sourceChain = chain
    private var sourceMembers = members
    private var startChain: List<SubId>? = null

    var rows: List<ChainRow> by mutableStateOf(ChainList.rows(chain, members))
        private set

    var dragging: SubId? by mutableStateOf(null)
        private set

    val chain: List<SubId> get() = ChainList.chainOf(rows)

    /** No-op unless [chain] or [members] differ from the last values seen, so an in-flight edit is not reverted by stale input. */
    fun sync(chain: List<SubId>, members: List<SubId>) {
        if (chain == sourceChain && members == sourceMembers) return
        sourceChain = chain
        sourceMembers = members
        if (dragging == null) rows = ChainList.rows(chain, members)
    }

    fun start(sub: SubId) {
        if (dragging != null) return
        dragging = sub
        startChain = chain
    }

    /** True when the move carried the dragged row across the boundary. */
    fun move(fromKey: Any, toKey: Any): Boolean {
        val before = rows
        val next = ChainList.move(before, fromKey, toKey) ?: return false
        rows = next
        val moved = before.first { it.key == fromKey }
        return (before.indexOf(moved) < ChainList.boundaryIndex(before)) != (next.indexOf(moved) < ChainList.boundaryIndex(next))
    }

    /** Ends the drag, settles the rows into their canonical order and returns the edit to persist, if any. */
    fun drop(): ChainOp? {
        val sub = dragging ?: return null
        val before = startChain ?: sourceChain
        val op = ChainList.dropOp(before, rows, sub)
        dragging = null
        startChain = null
        val expected = op?.applyTo(sourceChain) ?: sourceChain
        rows = ChainList.rows(expected, sourceMembers)
        return op
    }
}
