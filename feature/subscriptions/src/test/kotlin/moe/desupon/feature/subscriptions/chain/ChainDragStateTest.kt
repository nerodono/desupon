package moe.desupon.feature.subscriptions.chain

import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainDragStateTest {
    private val a = SubId(1)
    private val b = SubId(2)
    private val c = SubId(3)

    private fun key(id: SubId) = ChainRow.Sub(id).key

    @Test
    fun `dragging a rest card across the boundary adds it and reports the crossing`() {
        val s = ChainDragState(listOf(a), listOf(a, b, c))
        s.start(c)
        assertFalse(s.move(key(c), key(b)))
        assertTrue(s.move(key(c), ChainRow.BOUNDARY_KEY))
        assertEquals(listOf(a, c), s.chain)
        assertEquals(ChainOp.Add(c, 1), s.drop())
        assertNull(s.dragging)
    }

    @Test
    fun `drop settles the rest back into member order`() {
        val s = ChainDragState(listOf(a, b), listOf(a, b, c))
        s.start(b)
        s.move(key(b), ChainRow.BOUNDARY_KEY)
        s.move(key(b), key(c))
        assertEquals(ChainOp.Remove(b), s.drop())
        assertEquals(ChainList.rows(listOf(a), listOf(a, b, c)), s.rows)
    }

    @Test
    fun `reordering only the rest yields no op and restores order`() {
        val s = ChainDragState(emptyList(), listOf(a, b, c))
        s.start(c)
        s.move(key(c), key(b))
        assertNull(s.drop())
        assertEquals(ChainList.rows(emptyList(), listOf(a, b, c)), s.rows)
    }

    @Test
    fun `stale input does not revert an edit in flight`() {
        val s = ChainDragState(emptyList(), listOf(a, b))
        s.start(b)
        s.move(key(b), ChainRow.BOUNDARY_KEY)
        s.drop()
        s.sync(emptyList(), listOf(a, b))
        assertEquals(listOf(b), s.chain)
        s.sync(listOf(b), listOf(a, b))
        assertEquals(listOf(b), s.chain)
    }

    @Test
    fun `upstream changes during a drag apply on drop`() {
        val s = ChainDragState(emptyList(), listOf(a, b))
        s.start(a)
        s.sync(emptyList(), listOf(a, b, c))
        assertEquals(3, s.rows.size)
        s.move(key(a), ChainRow.BOUNDARY_KEY)
        assertEquals(ChainOp.Add(a, 0), s.drop())
        assertEquals(ChainList.rows(listOf(a), listOf(a, b, c)), s.rows)
    }
}
