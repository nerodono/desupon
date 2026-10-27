package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class AgoTest {
    private val now = 1_800_000_000_000L

    @Test
    fun coarseUnits() {
        assertEquals(Ago.JustNow, Ago.of(now - 59_000, now))
        assertEquals(Ago.JustNow, Ago.of(now + 3_600_000, now))
        assertEquals(Ago.Minutes(59), Ago.of(now - 59 * 60_000, now))
        assertEquals(Ago.Hours(23), Ago.of(now - 1439 * 60_000L, now))
        assertEquals(Ago.Days(5), Ago.of(now - 5 * 86_400_000L, now))
    }
}
