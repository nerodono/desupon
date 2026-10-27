package moe.desupon.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.ConnState

/**
 * One-time system prompts tied to the connection: notifications once a session starts, the battery exemption once it connects.
 * Each is asked at most once and never while another one is open.
 */
internal class ConnectPrompts(private val notifications: Prompt, private val battery: Prompt) {
    interface Prompt {
        /** Not asked yet and still useful. */
        suspend fun due(): Boolean

        suspend fun markAsked()

        /** Returns once the user answered. */
        suspend fun ask()
    }

    private enum class Phase { IDLE, ACTIVE, CONNECTED }

    /** Collect while the activity is in the foreground. */
    suspend fun run(states: Flow<ConnState>) {
        states.map { it.phase() }.distinctUntilChanged().conflate().collect { phase ->
            if (phase == Phase.IDLE) return@collect
            notifications.askIfDue()
            if (phase == Phase.CONNECTED) battery.askIfDue()
        }
    }

    private suspend fun Prompt.askIfDue() {
        if (!due()) return
        markAsked()
        ask()
    }

    private fun ConnState.phase(): Phase = when (this) {
        ConnState.Idle, is ConnState.Failed -> Phase.IDLE
        is ConnState.Connected -> Phase.CONNECTED
        else -> Phase.ACTIVE
    }
}
