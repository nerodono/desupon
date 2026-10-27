package moe.desupon.brain.port

import kotlinx.coroutines.flow.StateFlow
import moe.desupon.model.turn.TurnState

/** The brain's only view of the TURN tier. */
interface TurnTierPort {
    val state: StateFlow<TurnState>

    /** Wakes the dormant session; independent of the box, so it may run before a reload. */
    suspend fun warmStart()

    suspend fun stop()

    fun onNetworkChanged(dnsServers: List<String>)

    fun onScreenOn(sleptMs: Long)
}
