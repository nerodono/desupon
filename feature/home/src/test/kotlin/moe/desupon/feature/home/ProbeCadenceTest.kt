package moe.desupon.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeCadenceTest {
    @Test
    fun probesAtOnceThenAfterEachReturnedWait() = runTest {
        val times = mutableListOf<Long>()
        val waits = ArrayDeque(listOf(15_000L, 3_000L, 15_000L))
        val job = launch { probeCadence(MutableSharedFlow()) { times += currentTime; waits.removeFirstOrNull() ?: 15_000L } }
        advanceTimeBy(34_000)
        assertEquals(listOf(0L, 15_000L, 18_000L, 33_000L), times)
        job.cancel()
    }

    @Test
    fun restartProbesAtOnceAndResetsTheWait() = runTest {
        val times = mutableListOf<Long>()
        val restarts = MutableSharedFlow<Unit>()
        val job = launch { probeCadence(restarts) { times += currentTime; 15_000L } }
        advanceTimeBy(10_000)
        restarts.emit(Unit)
        runCurrent()
        advanceTimeBy(20_000)
        assertEquals(listOf(0L, 10_000L, 25_000L), times)
        job.cancel()
    }

    @Test
    fun cancellationStopsProbing() = runTest {
        var count = 0
        val job = launch { probeCadence(MutableSharedFlow()) { count++; 15_000L } }
        advanceTimeBy(16_000)
        job.cancel()
        advanceTimeBy(60_000)
        assertEquals(2, count)
    }
}
