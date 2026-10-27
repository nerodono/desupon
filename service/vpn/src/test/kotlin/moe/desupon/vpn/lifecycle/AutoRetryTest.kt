package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoRetryTest {
    private val enabled = MutableStateFlow<Boolean?>(true)
    private val network = MutableStateFlow<Any?>("wifi")
    private val wakes = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    private var retries = 0
    private var giveUps = 0

    private fun TestScope.retry() = AutoRetry(backgroundScope, enabled, network, wakes)

    private fun AutoRetry.fail() = schedule(retry = { retries++ }, giveUp = { giveUps++ })

    /** Fires the pending retry after exactly [ms]: not a millisecond earlier. */
    private fun TestScope.assertFiresAfter(ms: Long) {
        val before = retries
        advanceTimeBy(ms - 1)
        runCurrent()
        assertEquals(before, retries, "fired before $ms ms")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 1, retries, "did not fire at $ms ms")
    }

    @Test
    fun `backs off 30 s, 60 s, 2 min, then every 5 min`() = runTest {
        val r = retry()
        for (ms in listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L)) {
            r.fail()
            assertTrue(r.waiting)
            assertFiresAfter(ms)
            assertFalse(r.waiting)
        }
    }

    @Test
    fun `a new network retries at once, losing one does not`() = runTest {
        val r = retry()
        r.fail()
        runCurrent()
        network.value = null
        runCurrent()
        assertEquals(0, retries)
        network.value = "cell"
        runCurrent()
        assertEquals(1, retries)
        assertFalse(r.waiting)

        r.fail()
        assertFiresAfter(60_000)
    }

    @Test
    fun `a wake from a long sleep retries at once`() = runTest {
        val r = retry()
        wakes.tryEmit(60_000)
        r.fail()
        runCurrent()
        assertEquals(0, retries, "a wake before the wait")

        wakes.tryEmit(60_000)
        runCurrent()
        assertEquals(1, retries)
        assertFalse(r.waiting)

        r.fail()
        assertFiresAfter(60_000)
    }

    @Test
    fun `the network current when waiting starts does not count as a change`() = runTest {
        retry().fail()
        runCurrent()
        network.value = "wifi"
        runCurrent()
        assertEquals(0, retries)
    }

    @Test
    fun `a user stop cancels the wait and restarts the backoff`() = runTest {
        val r = retry()
        r.fail()
        assertFiresAfter(30_000)
        r.fail()
        advanceTimeBy(10_000)
        r.reset()
        advanceTimeBy(3_600_000)
        network.value = "cell"
        runCurrent()
        assertEquals(1, retries)
        assertFalse(r.waiting)

        r.fail()
        assertFiresAfter(30_000)
    }

    @Test
    fun `a manual start drops the wait but keeps the backoff`() = runTest {
        val r = retry()
        r.fail()
        r.cancelWait()
        advanceTimeBy(3_600_000)
        assertEquals(0, retries)
        r.fail()
        assertFiresAfter(60_000)
    }

    @Test
    fun `turning simple mode off gives up instead of retrying`() = runTest {
        val r = retry()
        r.fail()
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(1, giveUps)
        assertEquals(0, retries)
        advanceTimeBy(3_600_000)
        assertEquals(0, retries)
    }

    @Test
    fun `only simple mode retries, and only failures retrying can fix`() = runTest {
        val r = retry()
        assertTrue(r.wants(ConnState.Failed(FailReason.CORE)))
        assertTrue(r.wants(ConnState.Failed(FailReason.CONFIG)))
        assertFalse(r.wants(ConnState.Failed(FailReason.NO_NODES)))
        assertFalse(r.wants(null))
        assertFalse(r.wants(ConnState.Failed(FailReason.NOT_PREPARED)))
        assertFalse(r.wants(ConnState.Failed(FailReason.START_BLOCKED)))
        enabled.value = false
        assertFalse(r.wants(ConnState.Failed(FailReason.CORE)))
        enabled.value = null
        assertFalse(r.wants(ConnState.Failed(FailReason.CORE)))
    }
}
