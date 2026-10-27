package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState

/**
 * Simple mode's reconnect after a failed session: waits 30 s, 60 s, 2 min, then 5 min per attempt, or only until the network changes.
 * Confined to the thread of [scope].
 *
 * @param enabled simple mode; null until the setting is read.
 * @param networks the current network's identity, null while offline.
 */
class AutoRetry(
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean?>,
    private val networks: Flow<Any?>,
) {
    private var attempt = 0
    private var wait: Job? = null

    val waiting: Boolean get() = wait != null

    suspend fun awaitSettings() {
        enabled.filterNotNull().first()
    }

    fun wants(failure: ConnState.Failed?): Boolean = failure != null && enabled.value == true && retryable(failure)

    /** Runs [retry] once due, or [giveUp] if simple mode is turned off first; replaces a pending wait. */
    fun schedule(retry: () -> Unit, giveUp: () -> Unit) {
        val delayMs = delayMs(attempt++)
        wait?.cancel()
        wait = scope.launch {
            val due = merge(
                flow {
                    delay(delayMs)
                    emit(true)
                },
                networks.distinctUntilChanged().drop(1).filterNotNull().map { true },
                enabled.filter { it == false }.map { false },
            ).first()
            wait = null
            if (due) retry() else giveUp()
        }
    }

    /** Drops a pending wait; the backoff goes on. */
    fun cancelWait() {
        wait?.cancel()
        wait = null
    }

    /** The user stopped or a session connected: no pending wait, and the next failure starts the backoff over. */
    fun reset() {
        cancelWait()
        attempt = 0
    }

    companion object {
        private val DELAYS_MS = longArrayOf(30_000, 60_000, 120_000)
        private const val STEADY_MS = 300_000L

        fun delayMs(attempt: Int): Long = DELAYS_MS.getOrElse(attempt) { STEADY_MS }

        /** Lost consent, a refused foreground start and an empty pool need the user. */
        fun retryable(failure: ConnState.Failed): Boolean = failure.reason !in NEEDS_USER

        private val NEEDS_USER = setOf(FailReason.NOT_PREPARED, FailReason.START_BLOCKED, FailReason.NO_NODES)
    }
}
