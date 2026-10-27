package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderTest {
    private val ids = listOf(10L, 20L, 30L, 40L)

    @Test
    fun movesForwardAndBackward() {
        assertEquals(listOf(20L, 30L, 10L, 40L), Reorder.move(ids, 0, 2))
        assertEquals(listOf(40L, 10L, 20L, 30L), Reorder.move(ids, 3, 0))
    }

    @Test
    fun outOfRangeOrSameIndexIsNoOp() {
        assertEquals(ids, Reorder.move(ids, 1, 1))
        assertEquals(ids, Reorder.move(ids, -1, 2))
        assertEquals(ids, Reorder.move(ids, 0, 4))
        assertEquals(emptyList(), Reorder.move(emptyList<Long>(), 0, 0))
    }

    @Test
    fun shiftByOneStep() {
        assertEquals(listOf(10L, 30L, 20L, 40L), Reorder.shift(ids, 30L, -1))
        assertEquals(listOf(10L, 30L, 20L, 40L), Reorder.shift(ids, 20L, 1))
    }

    @Test
    fun shiftAtEdgesOrUnknownIsNoOp() {
        assertEquals(ids, Reorder.shift(ids, 10L, -1))
        assertEquals(ids, Reorder.shift(ids, 40L, 1))
        assertEquals(ids, Reorder.shift(ids, 99L, 1))
    }
}
