package moe.desupon.platform.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoreLogBufferTest {
    private val source = MutableSharedFlow<CoreLogEntry>(extraBufferCapacity = 64)
    private val threshold = MutableStateFlow(CoreLogBuffer.DEFAULT_THRESHOLD)
    private var attachListener: ((Boolean) -> Unit)? = null
    private var now = 1_000L

    private fun TestScope.buffer(capacity: Int = CoreLogBuffer.CAPACITY) = CoreLogBuffer(
        source = source,
        sessions = { listener -> attachListener = listener.also { it(false) } },
        threshold = threshold,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        clock = { now },
        capacity = capacity,
    )

    private fun CoreLogBuffer.texts() = snapshot().map {
        when (it) {
            is LogLine.Entry -> "${it.level} ${it.text}"
            is LogLine.Session -> if (it.started) "started" else "stopped"
        }
    }

    @Test
    fun keepsLevelsUpToTheThreshold() = runTest {
        val b = buffer()
        source.emit(CoreLogEntry(CoreLogLevel.ERROR, "e"))
        source.emit(CoreLogEntry(CoreLogLevel.NOTICE, "sing-box started"))
        source.emit(CoreLogEntry(CoreLogLevel.INFO, "outbound connection to example.com:443"))
        assertEquals(listOf("ERROR e", "NOTICE sing-box started"), b.texts())

        threshold.value = CoreLogLevel.DEBUG
        source.emit(CoreLogEntry(CoreLogLevel.DEBUG, "d"))
        source.emit(CoreLogEntry(CoreLogLevel.TRACE, "t"))
        assertEquals(listOf("ERROR e", "NOTICE sing-box started", "DEBUG d"), b.texts())
    }

    @Test
    fun oldestLinesLeaveFirst() = runTest {
        val b = buffer(capacity = 3)
        repeat(5) { source.emit(CoreLogEntry(CoreLogLevel.WARN, "w$it")) }
        assertEquals(listOf("WARN w2", "WARN w3", "WARN w4"), b.texts())
        assertEquals(listOf(3L, 4L, 5L), b.snapshot().map { it.seq })
    }

    @Test
    fun sessionsAreMarkedOnTransitionsOnly() = runTest {
        val b = buffer()
        assertEquals(emptyList(), b.texts(), "the state at subscription is not an event")
        attachListener!!(true)
        now = 2_000L
        source.emit(CoreLogEntry(CoreLogLevel.WARN, "w"))
        attachListener!!(true)
        attachListener!!(false)
        attachListener!!(false)
        assertEquals(listOf("started", "WARN w", "stopped"), b.texts())
        assertEquals(1_000L, b.snapshot().first().atMs)
    }

    @Test
    fun clearEmptiesAndBumpsTheVersion() = runTest {
        val b = buffer()
        source.emit(CoreLogEntry(CoreLogLevel.ERROR, "e"))
        val before = b.version.value
        b.clear()
        assertTrue(b.snapshot().isEmpty())
        assertTrue(b.version.value > before)
        source.emit(CoreLogEntry(CoreLogLevel.ERROR, "e2"))
        assertEquals(2L, b.snapshot().single().seq, "numbering continues after a clear")
    }
}
