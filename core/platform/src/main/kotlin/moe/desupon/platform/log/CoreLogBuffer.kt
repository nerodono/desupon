package moe.desupon.platform.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LogLine {
    /** Increases by one per line kept, across [CoreLogBuffer.clear]. */
    val seq: Long
    val atMs: Long

    data class Entry(override val seq: Long, override val atMs: Long, val level: CoreLogLevel, val text: String) : LogLine

    /** The VPN service started or stopped the core. */
    data class Session(override val seq: Long, override val atMs: Long, val started: Boolean) : LogLine
}

/** The most verbose level [CoreLogBuffer] keeps; bound by the app to the user's setting. */
fun interface CoreLogThreshold {
    fun levels(): Flow<CoreLogLevel>
}

/** The core log of this process in memory only: the last [capacity] lines no more verbose than the threshold. */
class CoreLogBuffer(
    source: Flow<CoreLogEntry>,
    sessions: (listener: (Boolean) -> Unit) -> Unit,
    threshold: Flow<CoreLogLevel>,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = CAPACITY,
) {
    private val lines = ArrayDeque<LogLine>()
    private var seq = 0L
    private var attached: Boolean? = null

    @Volatile
    private var keep = DEFAULT_THRESHOLD

    private val _version = MutableStateFlow(0L)

    /** Changes whenever [snapshot] would return something else. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        scope.launch { threshold.collect { keep = it } }
        scope.launch { source.collect(::append) }
        sessions(::onAttach)
    }

    fun snapshot(): List<LogLine> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        _version.update { it + 1 }
    }

    private fun append(entry: CoreLogEntry) {
        if (entry.level.core > keep.core) return
        add { LogLine.Entry(it, clock(), entry.level, entry.message) }
    }

    /** Marks starts and stops; the report made on subscribing counts only when the core is already running. */
    private fun onAttach(value: Boolean) {
        val previous = synchronized(lines) { attached.also { attached = value } }
        if (previous == value || (previous == null && !value)) return
        add { LogLine.Session(it, clock(), value) }
    }

    private fun add(line: (Long) -> LogLine) {
        synchronized(lines) {
            lines.addLast(line(++seq))
            if (lines.size > capacity) lines.removeFirst()
        }
        _version.update { it + 1 }
    }

    companion object {
        const val CAPACITY = 2_000

        /** Errors, warnings and the core's start notices. */
        val DEFAULT_THRESHOLD = CoreLogLevel.NOTICE
    }
}
