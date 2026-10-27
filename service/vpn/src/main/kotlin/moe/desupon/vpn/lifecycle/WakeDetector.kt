package moe.desupon.vpn.lifecycle

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Spots the device waking from a sleep long enough for tunnel state to die: server-side QUIC idle timeouts and NAT mappings
 * run on while the CPU is suspended, but timers here and in the core stand still.
 *
 * @param suspendedMs time spent suspended since boot; elapsedRealtime counts it, uptimeMillis does not.
 */
class WakeDetector(
    private val suspendedMs: () -> Long = { SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() },
    private val thresholdMs: Long = THRESHOLD_MS,
    private val tickMs: Long = TICK_MS,
) {
    /** How long the device slept, whenever that exceeds [thresholdMs] between two checks: one every [tickMs] of awake time, and each of [checks]. */
    fun wakes(checks: Flow<Unit>): Flow<Long> = flow {
        var mark = suspendedMs()
        merge(ticks(), checks).collect {
            val now = suspendedMs()
            val slept = now - mark
            mark = now
            if (slept >= thresholdMs) emit(slept)
        }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            delay(tickMs)
            emit(Unit)
        }
    }

    companion object {
        /** Just under the 30 s QUIC idle timeout. */
        const val THRESHOLD_MS = 20_000L
        const val TICK_MS = 5_000L
    }
}
