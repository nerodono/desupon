package moe.desupon.brain.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import moe.desupon.brain.BrainConfig
import moe.desupon.brain.SelectorBrain
import moe.desupon.brain.next
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.NodeRepo
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.port.ProbeRequest
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.Prober
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SocketProtector
import moe.desupon.config.ConfigException
import moe.desupon.config.ConfigGenerator
import moe.desupon.model.Secret
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.selection.LocationChoice
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ProbeRequestResult {
    /** [probed] nodes have fresh results in the probe stats of the current network; [full] is false when only [Pools.hotTargets] were. */
    data class Done(val probed: Int, val full: Boolean = true) : ProbeRequestResult

    /** A pool probe finished less than the minimum interval ago. */
    data class Throttled(val retryInMs: Long) : ProbeRequestResult

    data object Busy : ProbeRequestResult

    data object NoNetwork : ProbeRequestResult

    data object NoNodes : ProbeRequestResult

    /** This [moe.desupon.brain.port.VpnControl] cannot probe. */
    data object Unavailable : ProbeRequestResult
}

/** Nodes being pool-probed right now and when the last full pool probe finished; shared by the brain and off-tunnel probes. */
class ProbeActivity(private val clock: Clock) {
    private val counts = HashMap<NodeId, Int>()
    private val _probing = MutableStateFlow<Set<NodeId>>(emptySet())
    val probing: StateFlow<Set<NodeId>> = _probing.asStateFlow()

    /** Monotonic time; null before the first full pool probe of this process. */
    @Volatile
    var lastFullAtMs: Long? = null
        private set

    fun begin(ids: Collection<NodeId>) = synchronized(counts) {
        for (id in ids) counts[id] = (counts[id] ?: 0) + 1
        _probing.value = counts.keys.toSet()
    }

    /** [full] marks a completed probe of the whole set pool. */
    fun end(ids: Collection<NodeId>, full: Boolean = false) = synchronized(counts) {
        for (id in ids) {
            val left = (counts[id] ?: 0) - 1
            if (left > 0) counts[id] = left else counts.remove(id)
        }
        if (full) markFull()
        _probing.value = counts.keys.toSet()
    }

    fun markFull() {
        lastFullAtMs = clock.elapsedMs()
    }

    suspend fun <T> track(ids: Collection<NodeId>, full: Boolean = false, block: suspend () -> T): T {
        begin(ids)
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            end(ids, full && ok)
        }
    }
}

/**
 * Probes [ordered] in batches of [batch], up to [parallel] batches at once and started in order; each batch is shown in [activity]
 * while in flight and its results go to [out] as soon as it finishes. Closes [out] when done or cancelled.
 */
suspend fun runSweep(
    ordered: List<Node>,
    batch: Int,
    parallel: Int,
    activity: ProbeActivity,
    out: SendChannel<Map<NodeId, ProbeResult>>,
    probe: suspend (List<Node>) -> Map<NodeId, ProbeResult>,
) {
    try {
        coroutineScope {
            val permits = Semaphore(parallel.coerceAtLeast(1))
            for (chunk in ordered.chunked(batch.coerceAtLeast(1))) {
                permits.acquire()
                launch {
                    try {
                        out.send(activity.track(chunk.map { it.id }) { probe(chunk) })
                    } finally {
                        permits.release()
                    }
                }
            }
        }
    } finally {
        out.close()
    }
}

/**
 * On-demand probes of the active set's pool, through the running brain or off-tunnel; work outlives the caller so results always land.
 * The whole pool is swept at most every [wifiFullIntervalMs] ([cellularFullIntervalMs] on cellular); requests in between
 * cover only [Pools.hotTargets].
 */
class PoolProbes(
    private val offTunnel: suspend (full: Boolean) -> ProbeRequestResult,
    private val activity: ProbeActivity,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val autoIntervalMs: Long = AUTO_INTERVAL_MS,
    private val forcedIntervalMs: Long = FORCED_INTERVAL_MS,
    private val wifiFullIntervalMs: Long = WIFI_FULL_INTERVAL_MS,
    private val cellularFullIntervalMs: Long = CELLULAR_FULL_INTERVAL_MS,
    private val transport: () -> Transport? = { null },
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastDoneAtMs: Long? = null

    @Volatile
    private var brain: SelectorBrain? = null

    val probing: StateFlow<Set<NodeId>> get() = activity.probing

    fun attach(brain: SelectorBrain) {
        this.brain = brain
    }

    fun detach(brain: SelectorBrain) {
        if (this.brain === brain) this.brain = null
    }

    /** [forced] is the user's refresh: it shortens the minimum interval since the last probe instead of skipping it. */
    suspend fun request(forced: Boolean = false): ProbeRequestResult {
        val min = if (forced) forcedIntervalMs else autoIntervalMs
        val now = clock.elapsedMs()
        val lastFull = activity.lastFullAtMs
        val since = listOfNotNull(lastFull, lastDoneAtMs).maxOrNull()?.let { now - it }
        if (since != null && since < min) return ProbeRequestResult.Throttled(min - since)
        if (!busy.compareAndSet(false, true)) return ProbeRequestResult.Busy
        val fullInterval = if (transport() == Transport.CELLULAR) cellularFullIntervalMs else wifiFullIntervalMs
        val full = lastFull == null || now - lastFull >= fullInterval
        val work = scope.async {
            try {
                (brain?.probeSetPool(full) ?: offTunnel(full)).also { if (it is ProbeRequestResult.Done) lastDoneAtMs = clock.elapsedMs() }
            } finally {
                busy.set(false)
            }
        }
        return work.await()
    }

    companion object {
        const val AUTO_INTERVAL_MS = 15_000L
        const val FORCED_INTERVAL_MS = 10_000L
        const val WIFI_FULL_INTERVAL_MS = 2 * 60_000L
        const val CELLULAR_FULL_INTERVAL_MS = 5 * 60_000L
    }
}

/** Probes the active set's pool with sockets bound to the underlying network and DNS, for when no session runs. */
class OffTunnelPoolProbe(
    private val prober: Prober,
    private val configGen: ConfigGenerator,
    private val nodes: NodeRepo,
    private val selection: SelectionPort,
    private val settings: SettingsPort,
    private val stats: ProbeStatRepo,
    private val network: suspend () -> BrainEvent.NetworkChanged?,
    private val protector: SocketProtector,
    private val activity: ProbeActivity,
    private val clock: Clock,
    private val config: BrainConfig = BrainConfig(),
) {
    /** Unless [full], only [Pools.hotTargets] around the pin, the chosen country and the first chain step; stats land per batch. */
    suspend fun run(full: Boolean = true): ProbeRequestResult {
        val sel = selection.selection.first()
        val all = Pools.probeTargets(nodes.nodes.first(), sel)
        if (all.isEmpty()) return ProbeRequestResult.NoNodes
        val net = network() ?: return ProbeRequestResult.NoNetwork
        val known = HashMap(attempt(emptyMap()) { stats.forNetwork(net.key) })
        val now = clock.nowMs()
        val targets = if (full) all else Pools.hotTargets(all, sel.chain, null, keepFor(sel, all), known, nowMs = now, verifiedTtlMs = config.verifiedTtlMs)
        val whole = targets.size == all.size
        val dns = settings.settings.first().dns
        val ordered = Pools.sweepOrder(targets, known, sel.chain, null, NetworkMode.UNKNOWN, net.key.transport, now, config.verifiedTtlMs)
        val byTag = targets.associateBy { it.id.tag }
        val out = Channel<Map<NodeId, ProbeResult>>(Channel.UNLIMITED)
        var probed = 0
        coroutineScope {
            launch {
                runSweep(ordered, config.sweepBatch(net.key.transport), sweepParallel(config, net.key.transport), activity, out) { batch ->
                    val probe = try {
                        configGen.probeConfig(batch, dns)
                    } catch (_: ConfigException) {
                        return@runSweep emptyMap()
                    }
                    val request = poolRequest(config, probe.json, net, dns, batch.associate { it.id.tag to config.probeTimeoutMs(net.key.transport, known[it.id], now) })
                    val raw = attempt(emptyMap()) { prober.probePool(request, protector) }
                    raw.entries.mapNotNull { (tag, r) -> byTag[tag]?.let { it.id to r } }.toMap()
                }
            }
            for (results in out) {
                if (results.isEmpty()) continue
                probed += results.size
                val at = clock.nowMs()
                val updated = results.map { (id, r) -> known[id].next(id, net.key, r, at, config.rttAlpha).also { known[id] = it } }
                attempt(Unit) { stats.upsert(updated) }
            }
        }
        if (whole) activity.markFull()
        return ProbeRequestResult.Done(probed, whole)
    }
}

/** The pin and the chosen country's nodes, which a UI probe covers besides the top-ranked ones. */
internal fun keepFor(sel: PoolSelection, all: List<Node>): List<NodeId> = when (val c = sel.choice) {
    LocationChoice.Any -> emptyList()
    is LocationChoice.Country -> all.filter { it.country.equals(c.iso, ignoreCase = true) }.map { it.id }
    is LocationChoice.Node -> listOf(c.id)
}

/** Batches in flight at once, so a sweep keeps about the transport's concurrency busy. */
internal fun sweepParallel(config: BrainConfig, transport: Transport): Int =
    (config.poolConcurrency(transport) / config.sweepBatch(transport)).coerceAtLeast(1)

/** One pool probe call; a verified node's timeout goes in [timeouts] only when it differs from the default. */
internal fun poolRequest(
    config: BrainConfig,
    nodesJson: Secret,
    net: BrainEvent.NetworkChanged,
    dns: DnsSettings,
    timeouts: Map<String, Int>,
): ProbeRequest {
    val default = config.unknownTimeoutMs(net.key.transport)
    return ProbeRequest(
        nodesJson = nodesJson,
        timeoutMs = default,
        concurrency = config.poolConcurrency(net.key.transport),
        dnsCsv = net.dnsServers.joinToString(","),
        network = net.network,
        ipv4Only = !net.ipv6 || dns.ipv4Only,
        timeouts = timeouts.filterValues { it != default },
    )
}

private inline fun <T> attempt(fallback: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    fallback
}
