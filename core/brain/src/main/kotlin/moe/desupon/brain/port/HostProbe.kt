package moe.desupon.brain.port

import moe.desupon.model.network.NetworkHandle

/** Reachability of a plain host over [network] itself, never through the tunnel. */
fun interface HostProbe {
    /** True when a connection (TLS handshake for 443) to [host] completes within [timeoutMs]. */
    suspend fun reachable(host: String, port: Int, network: NetworkHandle, timeoutMs: Int): Boolean
}
