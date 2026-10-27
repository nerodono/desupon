package moe.desupon.brain

import moe.desupon.brain.plan.NodeScore
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.probe.Throughput
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import moe.desupon.model.node.NodeId

/** Durations are monotonic milliseconds. */
data class BrainConfig(
    /** Pool probe timeout of a node without a known connect time, on Wi-Fi and other non-cellular networks. */
    val probeTimeoutMs: Int = 5_000,
    val cellularProbeTimeoutMs: Int = 3_000,
    /** A usable node with a known connect time gets this multiple of it, within [minProbeTimeoutMs]..[maxProbeTimeoutMs]. */
    val connectTimeoutFactor: Double = 3.0,
    val minProbeTimeoutMs: Int = 1_500,
    val maxProbeTimeoutMs: Int = 5_000,
    val poolConcurrency: Int = 32,
    val cellularPoolConcurrency: Int = 24,
    /** Nodes per pool probe call; stats and the UI update after each. */
    val sweepBatch: Int = 16,
    val cellularSweepBatch: Int = 12,
    /** A connect sequence goes on once this many nodes passed, or the last good one did; the sweep finishes behind it. */
    val earlyPassing: Int = 3,
    val liveConcurrency: Int = 4,
    /** Active check of the selected node while traffic flows and the screen is on. */
    val checkIntervalMs: Long = 45_000,
    /** Re-probe after a single failure instead of waiting for the next check. */
    val confirmDelayMs: Long = 3_000,
    val failuresToSwitch: Int = 2,
    /** A failing current node gets a core network reset before its confirmation probe, at most once per this long. */
    val resetCooldownMs: Long = 60_000,
    /** After a wake from a long sleep, probe failures neither persist nor move the last good node for this long. */
    val wakeGraceMs: Long = 20_000,
    val flapWindowMs: Long = 5 * 60_000L,
    val maxSwitchesPerWindow: Int = 3,
    val upwardChecks: Int = 3,
    val upwardSpanMs: Long = 3 * 60_000L,
    val upwardMinOnCurrentMs: Long = 10 * 60_000L,
    val retryBaseMs: Long = 30_000,
    val retryMaxMs: Long = 5 * 60_000L,
    val stallWindowMs: Long = 10_000,
    val stallMinUplinkBytes: Long = 2_048,
    val rttAlpha: Double = 0.3,
    /** WireGuard-family nodes loaded across all steps of a priority chain, earliest steps first. */
    val chainMaxEndpoints: Int = 6,
    /** Network memory older than this is pruned on connect. */
    val memoryTtlMs: Long = 30L * 24 * 60 * 60_000L,
    /** Same as [moe.desupon.brain.plan.PlannerConfig.verifiedTtlMs]; decides which nodes get a connect-based timeout. */
    val verifiedTtlMs: Long = 24 * 60 * 60_000L,
    val throughputUrl: String = THROUGHPUT_URL,
    val throughputBytes: Int = 65_536,
    val throughputTimeoutMs: Int = 3_000,
    val throughputPassBytes: Int = 49_152,
    /** Below the pass size but at least this much marks a node throttled; less fails it. */
    val throughputThrottledBytes: Int = 16_384,
    val throughputFinalists: Int = 3,
    /** Throughput is checked after connect only on a network not seen for this long. */
    val throughputAfterMs: Long = 6 * 60 * 60_000L,
) {
    fun poolConcurrency(transport: Transport): Int = if (transport == Transport.CELLULAR) cellularPoolConcurrency else poolConcurrency

    fun sweepBatch(transport: Transport): Int = if (transport == Transport.CELLULAR) cellularSweepBatch else sweepBatch

    fun unknownTimeoutMs(transport: Transport): Int = if (transport == Transport.CELLULAR) cellularProbeTimeoutMs else probeTimeoutMs

    /** Connect-based for a usable node with a known connect time, else [unknownTimeoutMs]. */
    fun probeTimeoutMs(transport: Transport, stat: ProbeStat?, nowMs: Long): Int {
        val usable = NodeScore.group(NodeScore.standing(stat, nowMs, verifiedTtlMs)) == 0
        val connect = stat?.ewmaConnectMs ?: 0.0
        if (!usable || connect <= 0.0) return unknownTimeoutMs(transport)
        return (connect * connectTimeoutFactor).toInt().coerceIn(minProbeTimeoutMs, maxProbeTimeoutMs)
    }

    companion object {
        const val THROUGHPUT_URL = "https://speed.cloudflare.com/__down?bytes=65536"
    }
}

/** [ConnState.Failed] reasons; the UI maps them to text. */
object FailReason {
    const val NO_NODES = "no_nodes"
    const val CONFIG = "config"
    const val CORE = "core"

    /** VPN consent is missing or was taken by another app. */
    const val NOT_PREPARED = "not_prepared"

    /** The system refused to start the service, e.g. from the background. */
    const val START_BLOCKED = "start_blocked"

    /** The chosen country has no node in the active set, or none of them answers; detail is the ISO code. */
    const val COUNTRY_UNAVAILABLE = "country_unavailable"

    /** The pinned node is gone, disabled or dead; detail is its hash. */
    const val NODE_UNAVAILABLE = "node_unavailable"
}

internal fun ProbeResult?.passed(): Boolean = this is ProbeResult.Ok && stage >= ProbeStage.GET204

internal fun ProbeStat?.next(id: NodeId, key: NetworkKey, result: ProbeResult, nowMs: Long, alpha: Double, decay: Double = RECENT_DECAY): ProbeStat {
    val base = this ?: ProbeStat(id, key, 0.0, 0, 0, 0, null, null)
    val okRecent = base.okRecent * decay
    val failRecent = base.failRecent * decay
    if (!result.passed()) {
        return base.copy(
            fail = base.fail + 1,
            failStreak = base.failStreak + 1,
            lastStage = result.stage,
            lastFailAtMs = nowMs,
            okRecent = okRecent,
            failRecent = failRecent + 1,
        )
    }
    val ok = result as ProbeResult.Ok
    val rtt = ok.rttMs.toDouble()
    val connect = ok.connectMs.toDouble()
    val first = base.rttSamples == 0
    return base.copy(
        ewmaRttMs = if (first) rtt else alpha * rtt + (1 - alpha) * base.ewmaRttMs,
        madMs = if (first) 0.0 else alpha * kotlin.math.abs(rtt - base.ewmaRttMs) + (1 - alpha) * base.madMs,
        ewmaConnectMs = if (first || base.ewmaConnectMs <= 0.0) connect else alpha * connect + (1 - alpha) * base.ewmaConnectMs,
        rttSamples = base.rttSamples + 1,
        ok = base.ok + 1,
        failStreak = 0,
        lastStage = result.stage,
        lastOkAtMs = nowMs,
        okRecent = okRecent + 1,
        failRecent = failRecent,
    )
}

/** Per-sample decay of [ProbeStat.okRecent] and [ProbeStat.failRecent]: about the last five probes count. */
internal const val RECENT_DECAY = 0.8

/** Folds one throughput check into [ProbeStat.ewmaKbps]; [throttled] marks a node that delivered too little. */
internal fun ProbeStat?.withThroughput(id: NodeId, key: NetworkKey, sample: Throughput, throttled: Boolean, alpha: Double): ProbeStat {
    val base = this ?: ProbeStat(id, key, 0.0, 0, 0, 0, null, null)
    val kbps = sample.kbps
    return base.copy(ewmaKbps = base.ewmaKbps?.let { alpha * kbps + (1 - alpha) * it } ?: kbps, throttled = throttled)
}
