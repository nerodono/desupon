package moe.desupon.brain.port

import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.Throughput

const val PROBE_URL = "https://www.gstatic.com/generate_204"

/** Excludes a socket from the tunnel, e.g. `VpnService.protect`. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

data class ProbeRequest(
    /** Probe config: outbounds only, no inbounds, TUN, cache file or FreeTurn. */
    val nodesJson: Secret,
    val url: String = PROBE_URL,
    val timeoutMs: Int,
    val concurrency: Int,
    /** DNS servers of the underlying network, comma-separated. */
    val dnsCsv: String,
    /** Network to bind probe sockets to; null means the current default. */
    val network: NetworkHandle?,
    /** The underlying network has no IPv6 or the user turned it off, so AAAA lookups are skipped. */
    val ipv4Only: Boolean = false,
    /** Per-tag timeouts overriding [timeoutMs]. */
    val timeouts: Map<String, Int> = emptyMap(),
)

interface Prober {
    /** Probes a pool on a standalone box, before or beside the running TUN; results keyed by outbound tag. */
    suspend fun probePool(request: ProbeRequest, protector: SocketProtector): Map<String, ProbeResult>

    /** Probes one outbound of the running box. */
    suspend fun probeLive(tag: String, url: String, timeoutMs: Int): ProbeResult

    /** Downloads up to [maxBytes] of [url] through one outbound of the running box; null when it cannot run. */
    suspend fun throughputLive(tag: String, url: String, maxBytes: Int, timeoutMs: Int): Throughput? = null
}
