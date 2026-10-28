package moe.desupon.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogReplayTest {
    private fun e(n: Int, level: CoreLogLevel = CoreLogLevel.WARN) = CoreLogEntry(level, "WARN[0000] line $n")

    private fun lines(range: IntRange) = range.map { e(it) }

    @Test
    fun theFirstSnapshotIsAllNew() {
        val replay = LogReplay(ringSize = 3000)
        assertEquals(lines(1..5), replay.snapshot(lines(1..5)))
        assertEquals(lines(6..6), replay.live(lines(6..6)))
    }

    @Test
    fun aReopenedStreamPassesOnlyWhatFollowsOurLastLines() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(lines(1..5))
        replay.live(lines(6..100))
        assertEquals(lines(101..102), replay.snapshot(lines(1..102)))
        assertEquals(lines(103..103), replay.live(lines(103..103)))
    }

    @Test
    fun aLineTheServerDroppedForUsDoesNotReplayTheRing() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(emptyList())
        replay.live(lines(0..99).filterNot { it == e(50) })
        assertEquals(lines(100..101), replay.snapshot(lines(0..101)))
    }

    @Test
    fun aGapAmongOurLastLinesStillAnchors() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(emptyList())
        replay.live(lines(0..99).filterNot { it == e(97) })
        assertEquals(lines(100..101), replay.snapshot(lines(0..101)))
    }

    @Test
    fun aRingThatLostItsHeadStillLinesUp() {
        val replay = LogReplay(ringSize = 5)
        replay.snapshot(lines(1..5))
        // The server ring kept 5 lines: 1 and 2 fell out while 6 and 7 came in.
        assertEquals(lines(6..7), replay.snapshot(lines(3..7)))
    }

    @Test
    fun aRingThatMovedPastEverythingOursIsAllNew() {
        val replay = LogReplay(ringSize = 5)
        replay.snapshot(lines(1..5))
        assertEquals(lines(10..14), replay.snapshot(lines(10..14)))
    }

    @Test
    fun withoutAnAnchorOnlyUnseenLinesPass() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(lines(1..3))
        // Our last line is gone from the server ring, the others are still there.
        assertEquals(lines(10..11), replay.snapshot(lines(1..2) + lines(10..11)))
    }

    @Test
    fun repeatedIdenticalLinesAfterTheAnchorAreKept() {
        val replay = LogReplay(ringSize = 3000)
        val x = CoreLogEntry(CoreLogLevel.WARN, "WARN[0007] timeout")
        replay.snapshot(lines(1..5) + x)
        assertEquals(listOf(x, x), replay.snapshot(lines(1..5) + listOf(x, x, x)))
    }

    @Test
    fun theFirstLiveBatchDropsWhatRepeatsTheSnapshotTail() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(lines(1..10))
        // Lines 9 and 10 were pushed before the snapshot and emitted to the new subscription after it.
        assertEquals(lines(11..12), replay.live(lines(9..12)))
        assertEquals(lines(9..10), replay.live(lines(9..10)), "only the first live batch is checked")
    }

    @Test
    fun aResetMidStreamMakesWhatFollowsNew() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(lines(1..3))
        replay.cleared()
        assertEquals(lines(1..2), replay.live(lines(1..2)), "a fresh service may repeat old text")
        assertEquals(lines(3..3), replay.snapshot(lines(1..3)))
    }

    @Test
    fun levelIsPartOfALine() {
        val replay = LogReplay(ringSize = 3000)
        replay.snapshot(listOf(e(1)))
        assertEquals(listOf(e(1, CoreLogLevel.ERROR)), replay.snapshot(listOf(e(1), e(1, CoreLogLevel.ERROR))))
    }
}
