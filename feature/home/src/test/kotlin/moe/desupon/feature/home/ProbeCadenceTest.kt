package moe.desupon.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeCadenceTest {
    @Test
    fun probesAtOnceThenAfterEachReturnedWait() = runTest {
        val times = mutableListOf<Long>()
        val waits = ArrayDeque(listOf(15_000L, 3_000L, 15_000L))
        val job = launch { probeCadence(MutableStateFlow(true)) { times += currentTime; waits.removeFirstOrNull() ?: 15_000L } }
        advanceTimeBy(34_000)
        assertEquals(listOf(0L, 15_000L, 18_000L, 33_000L), times)
        job.cancel()
    }

    @Test
    fun closedSheetNeverProbesAndReopeningStartsAFreshWait() = runTest {
        val times = mutableListOf<Long>()
        val visible = MutableStateFlow(false)
        val job = launch { probeCadence(visible) { times += currentTime; 15_000L } }
        advanceTimeBy(10_000)
        assertTrue(times.isEmpty())
        visible.value = true
        runCurrent()
        advanceTimeBy(5_000)
        visible.value = false
        advanceTimeBy(60_000)
        assertEquals(listOf(10_000L), times)
        visible.value = true
        runCurrent()
        advanceTimeBy(16_000)
        assertEquals(listOf(10_000L, 75_000L, 90_000L), times)
        job.cancel()
    }

    @Test
    fun closingSheetCancelsItsPendingRequest() = runTest {
        val visible = MutableStateFlow(true)
        var cancelled = false
        val job = launch {
            probeCadence(visible) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        runCurrent()
        visible.value = false
        runCurrent()
        assertTrue(cancelled)
        job.cancel()
    }

    @Test
    fun cancellationStopsProbing() = runTest {
        var count = 0
        val job = launch { probeCadence(MutableStateFlow(true)) { count++; 15_000L } }
        advanceTimeBy(16_000)
        job.cancel()
        advanceTimeBy(60_000)
        assertEquals(2, count)
    }
}
