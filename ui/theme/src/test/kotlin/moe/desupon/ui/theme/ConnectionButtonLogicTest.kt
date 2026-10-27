package moe.desupon.ui.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionButtonLogicTest {
    @Test
    fun formatsElapsedAsHoursMinutesSeconds() {
        assertEquals("00:00:00", formatElapsed(0))
        assertEquals("00:29:05", formatElapsed(29 * 60 + 5))
        assertEquals("01:02:05", formatElapsed(3725))
        assertEquals("100:00:00", formatElapsed(360_000))
        assertEquals("00:00:00", formatElapsed(-5))
    }

    @Test
    fun singleOrNoSubscriptionIsOneFullRing() {
        assertEquals(listOf(ArcSegment(-90f, 360f)), checkingSegments(0))
        assertEquals(listOf(ArcSegment(-90f, 360f)), checkingSegments(1))
    }

    @Test
    fun segmentsTileTheRingClockwiseFromTop() {
        val segs = checkingSegments(3)
        assertEquals(3, segs.size)
        val gap = 120f - segs[0].sweepDegrees
        assertEquals(8f, gap, 1e-4f)
        assertEquals(-90f + gap / 2, segs[0].startDegrees, 1e-4f)
        segs.zipWithNext().forEach { (a, b) ->
            assertEquals(gap, b.startDegrees - (a.startDegrees + a.sweepDegrees), 1e-4f)
        }
        assertEquals(360f, segs.sumOf { it.sweepDegrees.toDouble() }.toFloat() + 3 * gap, 1e-3f)
    }

    @Test
    fun gapsShrinkForManySubscriptions() {
        val segs = checkingSegments(60)
        assertTrue(segs.all { it.sweepDegrees > 0f })
        assertEquals(6f * 0.7f, segs[0].sweepDegrees, 1e-4f)
    }

    @Test
    fun everyStateMapsToDistinctGlyphOrRing() {
        val kinds = listOf(
            ConnectionButtonState.Disconnected,
            ConnectionButtonState.Checking(listOf(true)),
            ConnectionButtonState.Connecting,
            ConnectionButtonState.Connected,
            ConnectionButtonState.Switching,
            ConnectionButtonState.Error,
        ).map { it.kind }
        assertEquals(Kind.entries.toList(), kinds)
    }

    @Test
    fun rotatesAroundPivotClockwise() {
        val p = rotateAround(22f, 12f, 12f, 12f, 90f)
        assertTrue(abs(p.x - 12f) < 1e-4f && abs(p.y - 22f) < 1e-4f)
    }

    @Test
    fun desuTextPicksVariant() {
        val t = DesuText(neutral = "Подключено", desu = "Готово, десу")
        assertEquals("Подключено", t.pick(desuMode = false))
        assertEquals("Готово, десу", t.pick(desuMode = true))
    }
}
