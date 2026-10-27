package moe.desupon.model.probe

import moe.desupon.model.network.NetworkKey
import moe.desupon.model.node.NodeId

/** Probe stages in order; [GET204] passes only on HTTP 204 exactly. */
enum class ProbeStage { TRANSPORT, TLS, GET204, THROUGHPUT }

sealed interface ProbeResult {
    val stage: ProbeStage

    /**
     * [stage] is the last stage passed. [rttMs] is a warm request over an established connection when [warm],
     * else the first request; [connectMs] is the time until the connection to the target was usable.
     */
    data class Ok(
        override val stage: ProbeStage,
        val rttMs: Int,
        val connectMs: Int = 0,
        val firstMs: Int = rttMs,
        val warm: Boolean = false,
    ) : ProbeResult

    /** [stage] is the stage that failed. */
    data class Fail(override val stage: ProbeStage, val reason: String) : ProbeResult
}

/** One capped download through a node: [bytes] of the body within [bodyMs]; [ok] means the server answered 200. */
data class Throughput(val ok: Boolean, val bytes: Int, val bodyMs: Int) {
    val kbps: Double get() = if (bodyMs <= 0) bytes * 8.0 else bytes * 8.0 / bodyMs
}

/**
 * Probe history of one node on one network. [rttSamples] counts samples in [ewmaRttMs]; zero means it holds nothing
 * comparable yet. [madMs] is the smoothed absolute deviation of the RTT; [okRecent]/[failRecent] decay per sample.
 */
data class ProbeStat(
    val nodeId: NodeId,
    val network: NetworkKey,
    val ewmaRttMs: Double,
    val ok: Int,
    val fail: Int,
    val failStreak: Int,
    val lastStage: ProbeStage?,
    val lastOkAtMs: Long?,
    val ewmaConnectMs: Double = 0.0,
    val madMs: Double = 0.0,
    val lastFailAtMs: Long? = null,
    val okRecent: Double = 0.0,
    val failRecent: Double = 0.0,
    val rttSamples: Int = 0,
    val ewmaKbps: Double? = null,
    val throttled: Boolean = false,
) {
    /** Share of recent probes that failed; falls back to lifetime counts. */
    val failureRatio: Double
        get() = when {
            okRecent + failRecent > 0 -> failRecent / (okRecent + failRecent)
            ok + fail > 0 -> fail.toDouble() / (ok + fail)
            else -> 0.0
        }
}
