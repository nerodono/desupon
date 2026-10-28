package moe.desupon.platform.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogIntakeTest {
    private val out = mutableListOf<CoreLogEntry>()
    private val intake = LogIntake(ringSize = 3000) { out += it }

    private fun lines(range: IntRange) = range.map { CoreLogEntry(CoreLogLevel.WARN, "WARN[0001] line $it") }

    /** As the command client delivers a stream: the reset mark, the ring, then live batches. */
    private fun LogIntake.Session.open(ring: List<CoreLogEntry>) {
        cleared()
        lines(ring)
    }

    @Test
    fun aRedialedStreamAddsOnlyNewLines() {
        intake.session().open(lines(1..10))
        intake.session().open(lines(1..12))
        assertEquals(lines(1..12), out)
    }

    @Test
    fun batchesOfAReplacedStreamAreIgnoredBeforeAndAfterTheNewSnapshot() {
        val old = intake.session()
        old.open(lines(1..100))
        val next = intake.session()
        old.lines(lines(101..110))
        next.open(lines(1..110))
        old.lines(lines(101..110))
        assertEquals(lines(1..110), out)
    }

    @Test
    fun aResetAfterTheSnapshotEmptiesTheMirror() {
        val s = intake.session()
        s.open(lines(1..3))
        s.cleared()
        s.lines(lines(1..2))
        assertEquals(lines(1..3) + lines(1..2), out)
    }

    @Test
    fun nothingPassesAfterClose() {
        val s = intake.session()
        s.open(lines(1..2))
        intake.close()
        s.lines(lines(3..4))
        assertEquals(lines(1..2), out)
    }
}
