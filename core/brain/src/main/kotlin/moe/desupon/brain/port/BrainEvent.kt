package moe.desupon.brain.port

import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.probe.ProbeResult

sealed interface BrainEvent {
    data class Probe(val tag: String, val result: ProbeResult) : BrainEvent

    /** Proxied connections send but receive nothing. */
    data object PassiveStall : BrainEvent

    data class NetworkChanged(
        val key: NetworkKey,
        val network: NetworkHandle,
        val dnsServers: List<String>,
        /** False when the network has no global IPv6 address and default route. */
        val ipv6: Boolean = true,
    ) : BrainEvent

    /** No usable underlying network; live checks and failover pause until the next [NetworkChanged]. */
    data object NetworkLost : BrainEvent

    data class TurnUpdate(val streams: Int, val total: Int, val fatal: String?) : BrainEvent

    data object Tick : BrainEvent
}
