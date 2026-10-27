package moe.desupon.vpn.lifecycle

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Spots the device waking from a sleep long enough for tunnel state to die: server-side QUIC idle timeouts and NAT mappings
 * run on while the CPU is suspended, but timers here and in the core stand still.
 *
 * @param suspendedMs time spent suspended since boot; elapsedRealtime counts it, uptimeMillis does not.
 * @param interactive whether the screen is on.
 */
class WakeDetector(
    private val suspendedMs: () -> Long = { SystemClock.elapsedRealtime() - SystemClock.uptimeMillis() },
    private val interactive: () -> Boolean = { true },
    private val thresholdMs: Long = THRESHOLD_MS,
    private val tickMs: Long = TICK_MS,
) {
    /**
     * How long the device slept since the last check, whenever that reaches [thresholdMs]: on each of [checks], and on a tick
     * every [tickMs] of awake time while [interactive]. With the screen off the CPU wakes briefly for pushes and alarms, so a
     * tick there cannot tell one long sleep from many short ones: it only moves past a sleep below [thresholdMs] and leaves
     * a longer one to the next check.
     */
    fun wakes(checks: Flow<Unit>): Flow<Long> = flow {
        var mark = suspendedMs()
        merge(ticks().map { true }, checks.map { false }).collect { tick ->
            val now = suspendedMs()
            val slept = now - mark
            when {
                slept < thresholdMs -> mark = now
                !tick || interactive() -> {
                    mark = now
                    emit(slept)
                }
            }
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
