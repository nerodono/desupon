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
    private val checks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val woke = mutableListOf<Long>()

    private fun TestScope.watch() {
        WakeDetector({ suspended }).wakes(checks).onEach { woke += it }.launchIn(backgroundScope)
        runCurrent()
    }

    private fun TestScope.tick() {
        advanceTimeBy(WakeDetector.TICK_MS)
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
        checks.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf(60_000L), woke)

        tick()
        assertEquals(listOf(60_000L), woke)
    }
}
