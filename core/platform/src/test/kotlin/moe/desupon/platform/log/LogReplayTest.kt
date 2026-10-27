package moe.desupon.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogReplayTest {
    private fun e(n: Int, level: CoreLogLevel = CoreLogLevel.WARN) = CoreLogEntry(level, "line $n")

    private fun lines(range: IntRange) = range.map { e(it) }

    @Test
    fun linesBetweenResetsPassThrough() {
        val replay = LogReplay(ringSize = 10)
        assertEquals(lines(1..3), replay.accept(lines(1..3)))
        assertEquals(lines(4..4), replay.accept(lines(4..4)))
    }

    @Test
    fun reopenedStreamResendsTheRingAndOnlyNewLinesPass() {
        val replay = LogReplay(ringSize = 10)
        replay.accept(lines(1..5))
        replay.reset()
        assertEquals(lines(6..7), replay.accept(lines(1..7)))
        assertEquals(lines(8..8), replay.accept(lines(8..8)))
    }

    @Test
    fun ringThatLostItsHeadStillLinesUp() {
        val replay = LogReplay(ringSize = 5)
        replay.accept(lines(1..5))
        replay.reset()
        // The server ring kept 5 lines: 1 and 2 fell out while 6 and 7 came in.
        assertEquals(lines(6..7), replay.accept(lines(3..7)))
    }

    @Test
    fun emptiedRingStartsOver() {
        val replay = LogReplay(ringSize = 10)
        replay.accept(lines(1..3))
        replay.reset()
        assertEquals(emptyList(), replay.accept(emptyList()))
        assertEquals(lines(1..2), replay.accept(lines(1..2)), "a fresh service may repeat old text")
    }

    @Test
    fun freshRingWithNewLinesIsAllNew() {
        val replay = LogReplay(ringSize = 10)
        replay.accept(lines(1..3))
        replay.reset()
        assertEquals(lines(10..12), replay.accept(lines(10..12)))
    }

    @Test
    fun levelIsPartOfALine() {
        val replay = LogReplay(ringSize = 10)
        replay.accept(listOf(e(1)))
        replay.reset()
        assertEquals(listOf(e(1, CoreLogLevel.ERROR)), replay.accept(listOf(e(1, CoreLogLevel.ERROR))))
    }

    @Test
    fun repeatedLinesAreKeptWhenNew() {
        val replay = LogReplay(ringSize = 10)
        val same = CoreLogEntry(CoreLogLevel.WARN, "timeout")
        assertEquals(listOf(same, same), replay.accept(listOf(same, same)))
        replay.reset()
        assertEquals(listOf(same), replay.accept(listOf(same, same, same)))
    }
}
