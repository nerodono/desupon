@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class WakeDetectorTest {
    private var suspended = 1_000_000L
    private var screenOn = true
    private val checks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val woke = mutableListOf<Long>()

    private fun TestScope.watch() {
        WakeDetector({ suspended }, { screenOn }).wakes(checks).onEach { woke += it }.launchIn(backgroundScope)
        runCurrent()
    }

    private fun TestScope.tick() {
        advanceTimeBy(WakeDetector.TICK_MS)
        runCurrent()
    }

    private fun TestScope.check() {
        checks.tryEmit(Unit)
        runCurrent()
    }

    @Test
    fun `a long suspend between two ticks is reported with its length`() = runTest {
        watch()
        tick()
        assertEquals(emptyList(), woke, "time slept before watching")

        suspended += 45_000
        tick()
        assertEquals(listOf(45_000L), woke)
        tick()
        assertEquals(listOf(45_000L), woke)
    }

    @Test
    fun `regular ticks and short suspends report nothing, however many`() = runTest {
        watch()
        repeat(50) {
            tick()
            suspended += WakeDetector.THRESHOLD_MS - 1
        }
        tick()
        assertEquals(emptyList(), woke)
    }

    @Test
    fun `a check reports the sleep at once and the next tick does not repeat it`() = runTest {
        watch()
        advanceTimeBy(2_000)
        suspended += 60_000
        check()
        assertEquals(listOf(60_000L), woke)

        tick()
        assertEquals(listOf(60_000L), woke)
    }

    @Test
    fun `with the screen off, brief wake-ups between ticks report nothing however much sleep they add up to`() = runTest {
        screenOn = false
        watch()
        repeat(20) {
            repeat(100) { suspended += 500 }
            tick()
        }
        assertEquals(emptyList(), woke)
    }

    @Test
    fun `a long sleep a screen-off tick passed over is reported by the next check`() = runTest {
        screenOn = false
        watch()
        suspended += 45_000
        tick()
        suspended += 5_000
        tick()
        assertEquals(emptyList(), woke)

        screenOn = true
        check()
        assertEquals(listOf(50_000L), woke)
    }

    @Test
    fun `screen-off ticks move past short sleeps, so a check reports only the sleep after the last of them`() = runTest {
        screenOn = false
        watch()
        suspended += 15_000
        tick()
        suspended += 15_000
        check()
        assertEquals(emptyList(), woke)

        suspended += 25_000
        check()
        assertEquals(listOf(25_000L), woke)
    }
}
