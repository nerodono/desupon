package moe.desupon.feature.subscriptions.chain

import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChainListTest {
    private val a = SubId(1)
    private val b = SubId(2)
    private val c = SubId(3)
    private val d = SubId(4)

    private fun sub(id: SubId) = ChainRow.Sub(id)

    @Test
    fun `rows put the chain before the boundary and the rest in member order`() {
        val rows = ChainList.rows(listOf(c, a), listOf(a, b, c, d))
        assertEquals(listOf(sub(c), sub(a), ChainRow.Boundary, sub(b), sub(d)), rows)
        assertEquals(listOf(c, a), ChainList.chainOf(rows))
    }

    @Test
    fun `rows drop chain entries outside the members and duplicates`() {
        val rows = ChainList.rows(listOf(d, a, a), listOf(a, b))
        assertEquals(listOf(sub(a), ChainRow.Boundary, sub(b)), rows)
    }

    @Test
    fun `empty chain starts with the boundary`() {
        val rows = ChainList.rows(emptyList(), listOf(a, b))
        assertEquals(0, ChainList.boundaryIndex(rows))
        assertEquals(emptyList(), ChainList.chainOf(rows))
    }

    @Test
    fun `move by keys shifts the row and ignores unknown keys`() {
        val rows = ChainList.rows(listOf(a, b), listOf(a, b, c))
        assertEquals(listOf(sub(b), sub(a), ChainRow.Boundary, sub(c)), ChainList.move(rows, sub(a).key, sub(b).key))
        assertEquals(listOf(sub(a), sub(b), sub(c), ChainRow.Boundary), ChainList.move(rows, sub(c).key, ChainRow.BOUNDARY_KEY))
        assertNull(ChainList.move(rows, "nope", sub(a).key))
        assertNull(ChainList.move(rows, sub(a).key, sub(a).key))
    }

    @Test
    fun `reordering inside the box is a move`() {
        val before = listOf(a, b, c)
        val after = listOf(sub(b), sub(c), sub(a), ChainRow.Boundary, sub(d))
        val op = ChainList.dropOp(before, after, a)
        assertEquals(ChainOp.Move(a, 2), op)
        assertEquals(listOf(b, c, a), op!!.applyTo(before))
    }

    @Test
    fun `dragging into the box adds at the drop position`() {
        val before = listOf(a, b)
        val after = listOf(sub(a), sub(c), sub(b), ChainRow.Boundary, sub(d))
        val op = ChainList.dropOp(before, after, c)
        assertEquals(ChainOp.Add(c, 1), op)
        assertEquals(listOf(a, c, b), op!!.applyTo(before))
    }

    @Test
    fun `dragging into an empty box adds as the first step`() {
        val after = listOf(sub(b), ChainRow.Boundary, sub(a))
        assertEquals(ChainOp.Add(b, 0), ChainList.dropOp(emptyList(), after, b))
    }

    @Test
    fun `dragging out of the box removes`() {
        val before = listOf(a, b)
        val after = listOf(sub(a), ChainRow.Boundary, sub(c), sub(b))
        val op = ChainList.dropOp(before, after, b)
        assertEquals(ChainOp.Remove(b), op)
        assertEquals(listOf(a), op!!.applyTo(before))
    }

    @Test
    fun `dropping back in place or within the rest changes nothing`() {
        val before = listOf(a)
        assertNull(ChainList.dropOp(before, listOf(sub(a), ChainRow.Boundary, sub(b), sub(c)), a))
        assertNull(ChainList.dropOp(before, listOf(sub(a), ChainRow.Boundary, sub(c), sub(b)), b))
    }

    @Test
    fun `ops mirror the repository refusals and clamping`() {
        assertNull(ChainOp.Add(a).applyTo(listOf(a)))
        assertNull(ChainOp.Remove(a).applyTo(listOf(b)))
        assertNull(ChainOp.Move(a, 0).applyTo(listOf(b)))
        assertEquals(listOf(b, a), ChainOp.Add(a).applyTo(listOf(b)))
        assertEquals(listOf(b, a), ChainOp.Add(a, 99).applyTo(listOf(b)))
        assertEquals(listOf(a, b), ChainOp.Add(a, -3).applyTo(listOf(b)))
        assertEquals(listOf(b, c, a), ChainOp.Move(a, 99).applyTo(listOf(a, b, c)))
    }

    @Test
    fun `menu offers add outside the chain and moves within it`() {
        val outside = ChainMenu.of(listOf(a, b), c)
        assertEquals(ChainOp.Add(c), outside.add)
        assertNull(outside.up)
        assertNull(outside.down)
        assertNull(outside.remove)

        val first = ChainMenu.of(listOf(a, b, c), a)
        assertNull(first.add)
        assertNull(first.up)
        assertEquals(listOf(b, a, c), first.down!!.applyTo(listOf(a, b, c)))
        assertEquals(ChainOp.Remove(a), first.remove)

        val last = ChainMenu.of(listOf(a, b, c), c)
        assertNull(last.down)
        assertEquals(listOf(a, c, b), last.up!!.applyTo(listOf(a, b, c)))
    }

    @Test
    fun `an edit of a filtered chain keeps hidden rows in place`() {
        // a and c are subscriptions, b and d configs; the subscriptions tab shows a, c.
        val full = listOf(a, b, c)
        val subs = setOf(a, c)
        val moveUp = ChainList.toFull(ChainOp.Move(c, 0), full, subs)!!
        assertEquals(listOf(c, a, b), moveUp.applyTo(full))
        val moveDown = ChainList.toFull(ChainOp.Move(a, 1), full, subs)!!
        assertEquals(listOf(b, c, a), moveDown.applyTo(full))
        assertEquals(ChainOp.Remove(c), ChainList.toFull(ChainOp.Remove(c), full, subs))
        assertNull(ChainList.toFull(ChainOp.Remove(d), full, setOf(b, d)))
    }

    @Test
    fun `an add lands before its visible successor or after its predecessor`() {
        val full = listOf(a, b, c)
        val configs = setOf(b, d)
        assertEquals(listOf(a, d, b, c), ChainList.toFull(ChainOp.Add(d, 0), full, configs)!!.applyTo(full))
        assertEquals(listOf(a, b, d, c), ChainList.toFull(ChainOp.Add(d), full, configs)!!.applyTo(full))
        assertEquals(listOf(a, b, c, d), ChainList.toFull(ChainOp.Add(d), full, setOf(d))!!.applyTo(full))
    }

    @Test
    fun `a move that keeps the order of the full chain is dropped`() {
        val full = listOf(a, b, c)
        assertNull(ChainList.toFull(ChainOp.Move(a, 0), full, setOf(a, c)))
        assertNull(ChainList.toFull(ChainOp.Add(a), full, setOf(a, c)))
    }
}
