package moe.desupon.brain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.desupon.brain.plan.DefaultTierPlanner
import moe.desupon.brain.plan.NodeScore
import moe.desupon.brain.plan.TierLabels
import moe.desupon.brain.pool.Pools
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.brain.port.NetworkMemoryRepo
import moe.desupon.brain.port.NodeRepo
import moe.desupon.brain.port.PROBE_URL
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.Prober
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SocketProtector
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.brain.port.TierLearningRepo
import moe.desupon.brain.port.TierPlanner
import moe.desupon.brain.port.VpnControl
import moe.desupon.brain.probe.ProbeActivity
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.brain.probe.keepFor
import moe.desupon.brain.probe.poolRequest
import moe.desupon.brain.probe.runSweep
import moe.desupon.brain.probe.sweepParallel
import moe.desupon.config.ConfigException
import moe.desupon.config.ConfigGenerator
import moe.desupon.config.LocalRuleSet
import moe.desupon.config.MainConfigRequest
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.probe.Throughput
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.subscription.SubId

/**
 * Picks and keeps the `proxy` selector member over the DIRECT and RU tiers.
 * All per-session state lives in one coroutine on [scope], so events are handled strictly one at a time.
 */
class SelectorBrain(
    private val libbox: LibboxControl,
    private val prober: Prober,
    private val protector: SocketProtector,
    private val planner: TierPlanner,
    private val detector: NetModeDetector,
    private val nodeRepo: NodeRepo,
    private val splitRules: SplitRuleRepo,
    private val settings: SettingsPort,
    private val memory: NetworkMemoryRepo,
    private val statRepo: ProbeStatRepo,
    private val learning: TierLearningRepo,
    private val configGen: ConfigGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: BrainConfig = BrainConfig(),
    private val ruleSets: Map<String, LocalRuleSet> = emptyMap(),
    private val selfPackage: String = ConfigGenerator.SELF_PACKAGE,
    private val selectionPort: SelectionPort = SelectionPort.All,
    private val activity: ProbeActivity = ProbeActivity(clock),
) : VpnControl {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    override val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _live = MutableStateFlow<Set<SubId>>(emptySet())
    override val liveSubscriptions: StateFlow<Set<SubId>> = _live.asStateFlow()

    private val _mode = MutableStateFlow<NetworkMode?>(null)
    override val networkMode: StateFlow<NetworkMode?> = _mode.asStateFlow()

    override val probing: StateFlow<Set<NodeId>> get() = activity.probing

    private val _step = MutableStateFlow<ChainStep?>(null)
    override val activeStep: StateFlow<ChainStep?> = _step.asStateFlow()

    private val pendingProbes = HashSet<CompletableDeferred<ProbeRequestResult?>>()

    private val network = MutableStateFlow<BrainEvent.NetworkChanged?>(null)
    private val screenOn = MutableStateFlow(true)
    private val lifecycle = Mutex()
    private var session: Job? = null

    @Volatile
    private var sessionEvents: SendChannel<Event>? = null

    /** Platform signals; the latest network is kept while disconnected for the next [connect]. */
    fun onEvent(event: BrainEvent) {
        when (event) {
            is BrainEvent.NetworkChanged -> network.value = event
            BrainEvent.NetworkLost -> network.value = null
            BrainEvent.PassiveStall, BrainEvent.Tick -> sessionEvents?.trySend(Event.CheckNow)
            is BrainEvent.Probe, is BrainEvent.TurnUpdate -> Unit
        }
    }

    fun onScreenChanged(on: Boolean) {
        screenOn.value = on
    }

    /** The device slept long enough for tunnel state to die meanwhile: the core starts over and the current node is checked. */
    fun onWake() {
        sessionEvents?.trySend(Event.Wake)
    }

    /** Installed apps changed what the per-app split resolves to; the running config is reloaded on the current node. */
    fun onAppsChanged() {
        sessionEvents?.trySend(Event.AppsChanged)
    }

    /** Returns once the first attempt has settled; waits for a network if none was reported yet. */
    override suspend fun connect() {
        val settled = CompletableDeferred<Unit>()
        lifecycle.withLock {
            if (session?.isActive == true) return
            session = scope.launch { runSession(settled) }.also { job -> job.invokeOnCompletion { settled.complete(Unit) } }
        }
        settled.await()
    }

    override suspend fun disconnect() {
        lifecycle.withLock {
            val job = session
            session = null
            if (job != null) {
                job.cancelAndJoin()
                withContext(NonCancellable) { runCatching { libbox.stop() } }
            }
            _state.value = ConnState.Idle
            _live.value = emptySet()
            _mode.value = null
            _step.value = null
        }
    }

    /**
     * Probes the active set's pool inside the running session, WireGuard-family nodes loaded in the box through it;
     * null when no session runs or it ends first. Unless [full], only [Pools.hotTargets] are probed.
     */
    suspend fun probeSetPool(full: Boolean = true): ProbeRequestResult? {
        val events = sessionEvents ?: return null
        val done = CompletableDeferred<ProbeRequestResult?>()
        synchronized(pendingProbes) { pendingProbes += done }
        try {
            if (events.trySend(Event.ProbeSet(done, full)).isFailure) return null
            return done.await()
        } finally {
            synchronized(pendingProbes) { pendingProbes -= done }
        }
    }

    private suspend fun runSession(settled: CompletableDeferred<Unit>) {
        val events = Channel<Event>(Channel.UNLIMITED)
        sessionEvents = events
        try {
            coroutineScope { Session(this, events).run { settled.complete(Unit) } }
        } catch (e: Fatal) {
            _state.value = ConnState.Failed(e.reason, e.detail)
            _live.value = emptySet()
            _mode.value = null
            _step.value = null
            withContext(NonCancellable) { runCatching { libbox.stop() } }
        } finally {
            if (sessionEvents === events) sessionEvents = null
            events.close()
            synchronized(pendingProbes) { pendingProbes.toList() }.forEach { it.complete(null) }
            settled.complete(Unit)
        }
    }

    private sealed interface Event {
        data class Net(val net: BrainEvent.NetworkChanged) : Event

        data object Offline : Event

        data class Split(val split: SplitSettings) : Event

        data class Selection(val selection: PoolSelection) : Event

        data object CheckNow : Event

        data object Wake : Event

        data object AppsChanged : Event

        class ProbeSet(val done: CompletableDeferred<ProbeRequestResult?>, val full: Boolean) : Event

        /** One batch of a sweep that finishes behind the event loop. */
        class Batch(val key: NetworkKey, val results: Map<NodeId, ProbeResult>) : Event

        /** A sweep finished; [done] is the UI request that started it, if any. */
        class SweepDone(val probed: Int, val full: Boolean, val done: CompletableDeferred<ProbeRequestResult?>?) : Event

        /** [finalists] in preference order, the current node first. */
        class ThroughputDone(val key: NetworkKey, val finalists: List<NodeId>, val results: Map<NodeId, Throughput?>) : Event
    }

    private data class SplitSettings(val rules: List<SplitRule>, val settings: ConnectionSettings)

    /** Candidates of one network-mode tier inside one chain step; [step] is the chain index, its size for the rest. */
    private class Level(val step: Int, val tier: Tier, val nodes: List<Node>)

    /** Levels in failover order: chain steps in order, tiers inside each. */
    private class Plan(val levels: List<Level>) {
        /** What the config loads, per tier. */
        val topK: Map<Tier, List<Node>> = LinkedHashMap<Tier, List<Node>>().also { m ->
            for (l in levels) m[l.tier] = m[l.tier].orEmpty() + l.nodes
        }

        val nodes: List<Node> get() = levels.flatMap { it.nodes }

        fun isEmpty(): Boolean = levels.isEmpty()

        fun levelOf(c: Cand): Int = levels.indexOfFirst { it.step == c.step && it.tier == c.tier }

        companion object {
            val EMPTY = Plan(emptyList())
        }
    }

    private class Loaded(
        val plan: Plan,
        val requested: Set<NodeId>,
        val tags: Set<String>,
        val split: SplitSettings,
        val config: Secret,
    )

    private data class Cand(val node: Node, val tier: Tier, val step: Int) {
        val tag: String get() = node.id.tag
    }

    private data class Upward(val id: NodeId, val firstPassMs: Long, val passes: Int)

    private class Fatal(val reason: String, val detail: String? = null) : Exception(reason)

    private inner class Session(private val scope: CoroutineScope, private val events: Channel<Event>) {
        private lateinit var net: BrainEvent.NetworkChanged
        private lateinit var split: SplitSettings
        private lateinit var selection: PoolSelection
        private var mode = NetworkMode.UNKNOWN
        private var pool: List<Node> = emptyList()
        private var poolById: Map<NodeId, Node> = emptyMap()
        private val stats = HashMap<NodeId, ProbeStat>()
        private val promoted = HashSet<NodeId>()
        private var remembered: NetworkMemory? = null
        private var plan = Plan.EMPTY
        private var loaded: Loaded? = null

        private var current: Cand? = null
        private var currentSinceMs = 0L
        private var failures = 0
        private val switches = ArrayDeque<Long>()
        private var upward: Upward? = null
        private var sessionStartMs: Long? = null
        private var degradedSinceMs: Long? = null

        private var nextCheckAt: Long? = null
        private var recheckAt: Long? = null
        private var retryAt: Long? = null
        private var retryBackoff = config.retryBaseMs
        private var offline = false

        /** When the core last started over on fresh connections: a reload or a network reset. */
        private var freshSinceMs: Long? = null
        private var graceUntilMs: Long? = null
        private var graceResetSpent = false

        /** The current network was not seen within [BrainConfig.throughputAfterMs] before this session entered it. */
        private var unseen = false
        private val throughputChecked = HashSet<NetworkKey>()

        @Volatile
        private var trafficFlowing = false

        suspend fun run(onEstablished: () -> Unit) {
            _state.value = ConnState.Connecting(NetworkMode.UNKNOWN)
            _live.value = emptySet()
            _step.value = null
            net = network.filterNotNull().first()
            split = SplitSettings(splitRules.rules.first(), settings.settings.first())
            selection = selectionPort.selection.first()
            establish()
            _live.value = emptySet()
            onEstablished()
            scope.launch { network.collect { events.send(if (it == null) Event.Offline else Event.Net(it)) } }
            scope.launch { combine(splitRules.rules, settings.settings, ::SplitSettings).collect { events.send(Event.Split(it)) } }
            scope.launch { selectionPort.selection.collect { events.send(Event.Selection(it)) } }
            scope.launch { watchTraffic() }
            scope.launch { watchStall() }
            loop()
        }

        // region connect sequence

        private suspend fun establish() {
            guard(Unit) { memory.pruneOlderThan(clock.nowMs() - config.memoryTtlMs) }
            loadNetwork()
            refreshPool()
            if (pool.isEmpty()) throw unavailable()
            mode = remembered?.mode ?: NetworkMode.UNKNOWN
            bringUp()
            detectMode()
            _state.value = ConnState.Connecting(mode)
            val fresh = probePool()
            _live.value = fresh.filterValues { it.passed() }.keys.flatMapTo(HashSet()) { poolById[it]?.subs.orEmpty() }
            replan()
            if (plan.isEmpty()) throw unavailable()
            val cands = planCandidates()
            val good = remembered?.lastGood
            val best = cands.firstOrNull { fresh[it.node.id].passed() }
            val seed = cands.firstOrNull { it.node.id == good && (best == null || it.step <= best.step) && (fresh[it.node.id] == null || fresh[it.node.id].passed()) }
                ?: best
            val chosen = if (needsReload()) reload(plan, split, seed?.node?.id) else selectLoaded(seed?.node?.id)
            setCurrent(chosen)
            if (live(chosen.node).passed()) becomeConnected(chosen) else failover(rescue = false)
            // A narrowed choice the user can widen fails at once instead of idling in Degraded.
            if (selection.choice != LocationChoice.Any && _state.value is ConnState.Degraded) throw unavailable()
            checkThroughput(fresh)
        }

        /** Why the selection yields nothing usable: a [FailReason] and its detail. */
        private fun unavailableReason(): Pair<String, String?> = when (val c = selection.choice) {
            LocationChoice.Any -> FailReason.NO_NODES to null
            is LocationChoice.Country -> FailReason.COUNTRY_UNAVAILABLE to c.iso
            is LocationChoice.Node -> FailReason.NODE_UNAVAILABLE to c.id.hash
        }

        private fun unavailable(): Fatal = unavailableReason().let { (reason, detail) -> Fatal(reason, detail) }

        /**
         * Raises the TUN before the network is detected or probed, so no app traffic leaves outside it meanwhile:
         * on the remembered mode's plan holding this network's last good node, else on [ConfigGenerator.placeholderConfig].
         */
        private suspend fun bringUp() {
            val good = remembered?.lastGood?.takeIf { it in poolById }
            if (good != null) {
                replan()
                if (plan.nodes.any { it.id == good }) {
                    try {
                        reload(plan, split, good)
                        return
                    } catch (_: Fatal) {
                        loaded = null
                    }
                }
            }
            val placeholder = try {
                configGen.placeholderConfig(split.settings.lanBypass)
            } catch (_: ConfigException) {
                throw Fatal(FailReason.CONFIG)
            }
            core { libbox.reload(placeholder.raw(), split.settings.apps) }
        }

        private suspend fun enterNetwork() {
            loadNetwork()
            detectMode()
        }

        private suspend fun loadNetwork() {
            stats.clear()
            stats.putAll(guard(emptyMap()) { statRepo.forNetwork(net.key) })
            promoted.clear()
            promoted.addAll(guard(emptySet()) { learning.promoted(net.key) })
            remembered = guard(null) { memory.get(net.key) }
            unseen = remembered.let { it == null || clock.nowMs() - it.updatedAtMs >= config.throughputAfterMs }
        }

        private suspend fun detectMode() {
            mode = guard(NetworkMode.UNKNOWN) { detector.detect(net.key, net.network) }
            _mode.value = mode
            if (remembered?.mode != mode) persist(remembered?.lastGood)
        }

        private suspend fun refreshPool() {
            pool = Pools.effective(nodeRepo.nodes.first(), selection)
            poolById = pool.associateBy { it.id }
        }

        private suspend fun replan() {
            refreshPool()
            val labeled = pool.map { if (it.id in promoted) it.copy(labels = it.labels + TierLabels.LEARNED_RU) else it }
            if (selection.choice is LocationChoice.Node) {
                // The planner's transport and per-tier filters do not apply to an explicit pin.
                val pin = labeled.firstOrNull()
                plan = if (pin == null) {
                    Plan.EMPTY
                } else {
                    Plan(listOf(Level(0, DefaultTierPlanner.tierOf(pin, net.key.transport), listOf(poolById.getValue(pin.id)))))
                }
                return
            }
            val known = stats.toMap()
            val byStep = labeled.groupBy { ChainStep.indexOf(selection.chain, it.subs) }
            val levels = ArrayList<Level>()
            for (step in byStep.keys.sorted()) {
                val raw = planner.plan(mode, net.key, byStep.getValue(step), known)
                for (tier in raw.order.distinct()) {
                    if (tier !in TIERS) continue
                    val nodes = raw.topK[tier].orEmpty().mapNotNull { poolById[it.id] }
                    if (nodes.isNotEmpty()) levels += Level(step, tier, nodes)
                }
            }
            plan = Plan(if (byStep.size > 1) capEndpoints(levels) else levels)
        }

        /** Planned per step, every step would bring its own endpoints; the earliest ones keep their place. */
        private fun capEndpoints(levels: List<Level>): List<Level> {
            var left = config.chainMaxEndpoints
            return levels.mapNotNull { l ->
                val kept = l.nodes.filter { n -> !n.isWireGuardFamily() || left-- > 0 }
                if (kept.isEmpty()) null else Level(l.step, l.tier, kept)
            }
        }

        /**
         * Every reload re-selects, since the selector would otherwise restore its cached member.
         * Once a config has loaded, a failing reload falls back to it instead of failing the session.
         */
        private suspend fun reload(target: Plan, split: SplitSettings, preferred: NodeId?): Cand {
            val previous = loaded
            val generated = try {
                configGen.mainConfig(
                    MainConfigRequest(
                        selection = target.topK,
                        mode = mode,
                        rules = split.rules,
                        ruleSets = ruleSets,
                        dns = split.settings.dns,
                        lanBypass = split.settings.lanBypass,
                        selfPackage = selfPackage,
                        preferred = preferred,
                    ),
                )
            } catch (_: ConfigException) {
                if (previous == null) throw Fatal(FailReason.CONFIG)
                return restore(previous, coreTouched = false)
            }
            try {
                libbox.reload(generated.json.raw(), split.settings.apps)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (previous == null) throw Fatal(FailReason.CORE)
                return restore(previous, coreTouched = true)
            }
            freshSinceMs = clock.elapsedMs()
            loaded = Loaded(target, target.nodes.mapTo(HashSet()) { it.id }, generated.nodeTags.toSet(), split, generated.json)
            return selectLoaded(preferred)
        }

        /** Reinstates [previous] as the loaded config and plan on the current node; a second core failure is fatal. */
        private suspend fun restore(previous: Loaded, coreTouched: Boolean): Cand {
            if (coreTouched) {
                core { libbox.reload(previous.config.raw(), previous.split.settings.apps) }
                freshSinceMs = clock.elapsedMs()
            }
            loaded = previous
            plan = previous.plan
            return selectLoaded(current?.node?.id)
        }

        private suspend fun selectLoaded(preferred: NodeId?): Cand {
            val l = loaded!!
            val cands = candidates()
            val chosen = cands.firstOrNull { it.node.id == preferred }
                ?: cands.firstOrNull()
                ?: l.plan.levels.firstNotNullOf { lv -> lv.nodes.firstOrNull { it.id.tag in l.tags }?.let { Cand(it, lv.tier, lv.step) } }
            selectProxy(chosen.tag)
            return chosen
        }

        // endregion
        // region event loop

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun loop() {
            while (true) {
                val due = if (offline) null else listOfNotNull(recheckAt, retryAt, nextCheckAt).minOrNull()
                val event = if (due == null) {
                    events.receive()
                } else {
                    val wait = due - clock.elapsedMs()
                    if (wait <= 0) null else select<Event?> { events.onReceive { it }; onTimeout(wait) { null } }
                }
                if (event == null) fireTimers() else handle(event)
            }
        }

        private suspend fun handle(event: Event) = when (event) {
            is Event.Net -> if (offline && event.net == net) resume() else onNetwork(event.net)
            Event.Offline -> goOffline()
            is Event.Split -> onSplit(event.split)
            is Event.Selection -> onSelection(event.selection)
            Event.CheckNow -> if (offline) Unit else check()
            Event.Wake -> onWake()
            Event.AppsChanged -> loaded?.let { reapply(it.plan, split) } ?: Unit
            is Event.ProbeSet -> startSetProbe(event.done, event.full)
            is Event.Batch -> if (event.key == net.key) record(event.results) else Unit
            is Event.SweepDone -> {
                if (event.full) activity.markFull()
                event.done?.complete(ProbeRequestResult.Done(event.probed, event.full))
                Unit
            }
            is Event.ThroughputDone -> onThroughput(event)
        }

        /** Timers freeze and nothing is probed, so a dead network spends no failure, switch or backoff budget. */
        private fun goOffline() {
            offline = true
            failures = 0
            upward = null
        }

        /** The same network is back: pending timers fire now, else the current node is checked at once. */
        private suspend fun resume() {
            offline = false
            val now = clock.elapsedMs()
            retryAt?.let { retryAt = now }
            nextCheckAt?.let { nextCheckAt = now + config.checkIntervalMs }
            if (retryAt == null && recheckAt == null) check()
        }

        /**
         * Server-side QUIC idle timeouts and NAT mappings ran on while the device slept: the core starts over, then the current
         * node is checked at once, as on [resume]. Failures of the grace window that follows count for nothing durable.
         */
        private suspend fun onWake() {
            failures = 0
            upward = null
            // Unstable keeps its exit check.
            if (_state.value !is ConnState.Unstable) recheckAt = null
            graceUntilMs = clock.elapsedMs() + config.wakeGraceMs
            graceResetSpent = false
            resetNetwork()
            if (offline) return
            val now = clock.elapsedMs()
            retryAt?.let { retryAt = now }
            nextCheckAt?.let { nextCheckAt = now + config.checkIntervalMs }
            if (retryAt == null) check()
        }

        private fun inGrace(): Boolean = graceUntilMs.let { it != null && clock.elapsedMs() < it }

        /** Once per grace window, else once per [BrainConfig.resetCooldownMs] since the core last started over. */
        private fun resetDue(): Boolean {
            if (inGrace()) {
                if (graceResetSpent) return false
                graceResetSpent = true
                return true
            }
            return freshSinceMs.let { it == null || clock.elapsedMs() - it >= config.resetCooldownMs }
        }

        /** A refused reset leaves the session as it is. */
        private suspend fun resetNetwork() {
            freshSinceMs = clock.elapsedMs()
            guard(Unit) { libbox.resetNetwork() }
        }

        private suspend fun fireTimers() {
            val now = clock.elapsedMs()
            recheckAt?.let {
                if (now >= it) {
                    recheckAt = null
                    check()
                    return
                }
            }
            retryAt?.let {
                if (now >= it) {
                    if (screenOn.value) {
                        retryAt = null
                        recover()
                    } else {
                        retryAt = now + config.checkIntervalMs
                    }
                    return
                }
            }
            nextCheckAt?.let {
                if (now >= it) {
                    nextCheckAt = now + config.checkIntervalMs
                    if (screenOn.value && trafficFlowing) check()
                }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun watchTraffic() {
            screenOn
                .flatMapLatest { on -> if (on) libbox.status.map { it.uplinkRate > 0 || it.downlinkRate > 0 } else flowOf(false) }
                .collect { trafficFlowing = it }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun watchStall() {
            screenOn
                .flatMapLatest { on ->
                    if (!on) {
                        emptyFlow()
                    } else {
                        flow {
                            val stall = StallDetector(ConfigGenerator.TAG_PROXY, config.stallWindowMs, config.stallMinUplinkBytes)
                            libbox.connections.collect { if (stall.onSample(it, clock.elapsedMs())) emit(Unit) }
                        }
                    }
                }
                .collect { events.send(Event.CheckNow) }
        }

        // endregion
        // region failover FSM

        private suspend fun check() {
            val cur = current ?: return
            val passed = live(cur.node).passed()
            val st = _state.value
            if (passed) {
                failures = 0
                if (st !is ConnState.Unstable) recheckAt = null
                when (st) {
                    is ConnState.Degraded -> becomeConnected(cur)
                    is ConnState.Unstable -> if (!flapLimited()) becomeConnected(cur)
                    is ConnState.Connected -> {
                        rememberGood(cur.node.id)
                        considerUpward()
                    }
                    else -> Unit
                }
                return
            }
            // Lost with the network itself; the offline event that follows pauses checks.
            if (network.value == null) return
            // The confirmation then runs on fresh connections, so state that died unnoticed never gets the node blamed.
            if (failures == 0 && resetDue()) resetNetwork()
            failures++
            if (st is ConnState.Unstable && flapLimited()) return
            if (failures >= config.failuresToSwitch) failover() else recheckAt = clock.elapsedMs() + config.confirmDelayMs
        }

        private suspend fun failover(rescue: Boolean = true) {
            val cur = current ?: return
            recheckAt = null
            if (flapLimited()) {
                pinUnstable()
                return
            }
            val next = firstPassing(failoverOrder(cur))
            when {
                next != null -> switchTo(next)
                // With the screen off only live checks run; the retry that follows waits for the screen.
                rescue && screenOn.value -> rescue()
                else -> degrade()
            }
        }

        /** Same level (same country first), then the levels after it, then the ones before. */
        private fun failoverOrder(cur: Cand): List<Cand> {
            val cands = candidates().filter { it.node.id != cur.node.id }
            val at = plan.levelOf(cur)
            val sameCountry = cur.node.country
            val sameLevel = cands.filter { it.step == cur.step && it.tier == cur.tier }
                .sortedBy { if (sameCountry != null && it.node.country.equals(sameCountry, ignoreCase = true)) 0 else 1 }
            val later = cands.filter { plan.levelOf(it) > at }
            val earlier = cands.filter { plan.levelOf(it) in 0 until at }
            return sameLevel + later + earlier
        }

        /** Re-probes the whole pool off-TUN and reloads only if the new plan needs nodes not loaded yet. */
        private suspend fun rescue() {
            val before = current ?: return
            val fresh = probePool()
            replan()
            if (!plan.isEmpty() && needsReload()) {
                val pick = planCandidates().firstOrNull { fresh[it.node.id].passed() }
                setCurrent(reload(plan, split, pick?.node?.id))
            }
            val cur = current!!
            val next = firstPassing(keepFirstInStep(candidates().filter { fresh[it.node.id].passed() }, cur.node.id))
            when {
                next == null -> degrade()
                next.node.id == cur.node.id -> {
                    if (cur.node.id != before.node.id) switches.addLast(clock.elapsedMs())
                    becomeConnected(cur)
                }
                else -> switchTo(next)
            }
            checkThroughput(fresh)
        }

        private suspend fun recover() {
            val cur = current ?: return
            if (live(cur.node).passed()) becomeConnected(cur) else failover()
        }

        private fun degrade() {
            val cur = current ?: return
            val since = degradedSinceMs ?: clock.nowMs().also { degradedSinceMs = it }
            val (reason, detail) = if (selection.choice == LocationChoice.Any) null to null else unavailableReason()
            _state.value = ConnState.Degraded(cur.node, since, reason, detail)
            nextCheckAt = null
            recheckAt = null
            retryAt = clock.elapsedMs() + retryBackoff
            retryBackoff = (retryBackoff * 2).coerceAtMost(config.retryMaxMs)
        }

        private fun flapLimited(): Boolean {
            val now = clock.elapsedMs()
            while (switches.isNotEmpty() && now - switches.first() >= config.flapWindowMs) switches.removeFirst()
            return switches.size >= config.maxSwitchesPerWindow
        }

        private suspend fun pinUnstable() {
            val cur = current ?: return
            val best = (listOf(cur) + candidates().filter { it.node.id != cur.node.id }).minWith(byScore())
            if (best.node.id != cur.node.id) {
                selectProxy(best.tag)
                setCurrent(best)
            }
            failures = 0
            retryAt = null
            nextCheckAt = clock.elapsedMs() + config.checkIntervalMs
            recheckAt = switches.first() + config.flapWindowMs
            _state.value = ConnState.Unstable(best.node)
        }

        private suspend fun considerUpward() {
            val cur = current ?: return
            val at = plan.levelOf(cur)
            if (at <= 0) {
                upward = null
                return
            }
            val higher = candidates().filter { plan.levelOf(it) in 0 until at }
            if (higher.isEmpty()) return
            val target = upward?.let { u -> higher.firstOrNull { it.node.id == u.id } }
                ?: higher.minWith(byScore())
            if (!live(target.node).passed()) {
                upward = null
                return
            }
            val now = clock.elapsedMs()
            val track = upward?.takeIf { it.id == target.node.id }?.let { it.copy(passes = it.passes + 1) }
                ?: Upward(target.node.id, now, 1)
            upward = track
            val ready = track.passes >= config.upwardChecks &&
                now - track.firstPassMs >= config.upwardSpanMs &&
                now - currentSinceMs >= config.upwardMinOnCurrentMs
            if (ready && !flapLimited()) switchTo(target)
        }

        private suspend fun switchTo(next: Cand) {
            current?.let { _state.value = ConnState.Switching(it.node, next.node) }
            selectProxy(next.tag)
            switches.addLast(clock.elapsedMs())
            becomeConnected(next)
        }

        private fun setCurrent(c: Cand) {
            if (current?.node?.id != c.node.id) {
                currentSinceMs = clock.elapsedMs()
                upward = null
            }
            current = c
            _step.value = stepOf(c.node)
        }

        /** Null while no chain is in effect: none set, or a pin bypasses it. */
        private fun stepOf(node: Node): ChainStep? =
            if (selection.chain.isEmpty() || selection.choice is LocationChoice.Node) null else ChainStep.of(selection.chain, node.subs)

        /** [order] by chain step, [keep] first inside its own step, so it stays only while no earlier step is usable. */
        private fun keepFirstInStep(order: List<Cand>, keep: NodeId?): List<Cand> =
            order.sortedWith(compareBy({ it.step }, { if (it.node.id == keep) 0 else 1 }))

        private suspend fun becomeConnected(c: Cand) {
            setCurrent(c)
            failures = 0
            recheckAt = null
            retryAt = null
            retryBackoff = config.retryBaseMs
            degradedSinceMs = null
            nextCheckAt = clock.elapsedMs() + config.checkIntervalMs
            val start = sessionStartMs ?: clock.nowMs().also { sessionStartMs = it }
            _state.value = ConnState.Connected(c.node, c.tier, start)
            rememberGood(c.node.id)
        }

        // endregion
        // region network and split changes

        /** Never reloads unless the new plan needs nodes that are not loaded or split settings changed. */
        private suspend fun onNetwork(next: BrainEvent.NetworkChanged) {
            offline = false
            if (next == net) return
            net = next
            enterNetwork()
            failures = 0
            upward = null
            recheckAt = null
            val fresh = liveAll(loaded!!.plan.nodes)
            replan()
            if (plan.isEmpty()) {
                rescue()
                return
            }
            if (needsReload()) {
                val pick = pickOnNetwork(planCandidates(), fresh) ?: planCandidates().firstOrNull { it.node.id == remembered?.lastGood }
                val chosen = reload(plan, split, pick?.node?.id)
                setCurrent(chosen)
                if (fresh[chosen.node.id].passed() || live(chosen.node).passed()) becomeConnected(chosen) else failover()
                return
            }
            val pick = pickOnNetwork(candidates(), fresh)
            if (pick == null) {
                rescue()
                return
            }
            if (pick.node.id != current?.node?.id) selectProxy(pick.tag)
            becomeConnected(pick)
        }

        /** Inside the best passing step: last good node of this network, else staying put when the current node leads the best passing level. */
        private fun pickOnNetwork(cands: List<Cand>, fresh: Map<NodeId, ProbeResult>): Cand? {
            val best = cands.firstOrNull { fresh[it.node.id].passed() } ?: return null
            val passing = cands.filter { it.step == best.step && fresh[it.node.id].passed() }
            remembered?.lastGood?.let { good -> passing.firstOrNull { it.node.id == good }?.let { return it } }
            val cur = current
            return passing.firstOrNull { it.node.id == cur?.node?.id && it.tier == best.tier } ?: best
        }

        private suspend fun onSplit(next: SplitSettings) {
            split = next
            val l = loaded ?: return
            if (l.split != next) reapply(l.plan, next)
        }

        private suspend fun reapply(target: Plan, split: SplitSettings) {
            val chosen = reload(target, split, current?.node?.id)
            setCurrent(chosen)
            if (_state.value is ConnState.Connected) becomeConnected(chosen)
        }

        /** Re-plans without spending flap budget: keeps a still-qualifying current node, else a loaded candidate, else reloads once. */
        private suspend fun onSelection(next: PoolSelection) {
            if (next == selection) return
            selection = next
            failures = 0
            upward = null
            recheckAt = null
            replan()
            if (pool.isEmpty() || plan.isEmpty()) throw unavailable()
            val cur = current
            if (needsReload()) {
                val fresh = probePool()
                replan()
                if (plan.isEmpty()) throw unavailable()
                val pick = keepFirstInStep(planCandidates().filter { fresh[it.node.id].passed() }, cur?.node?.id).firstOrNull()
                val chosen = reload(plan, split, pick?.node?.id)
                setCurrent(chosen)
                if (live(chosen.node).passed()) becomeConnected(chosen) else failover(rescue = false)
                return
            }
            val order = keepFirstInStep(candidates(), cur?.node?.id)
            var pick = firstPassing(order)
            // The node this selection lands on gets fresh connections before it is passed over, as in [check].
            if (order.isNotEmpty() && pick != order.first() && resetDue()) {
                resetNetwork()
                pick = firstPassing(order)
            }
            if (pick == null) {
                val fallback = order.firstOrNull() ?: cur ?: throw unavailable()
                if (fallback.node.id != cur?.node?.id) selectProxy(fallback.tag)
                setCurrent(fallback)
                degrade()
                return
            }
            if (cur != null && pick.node.id != cur.node.id) {
                _state.value = ConnState.Switching(cur.node, pick.node)
                selectProxy(pick.tag)
            }
            becomeConnected(pick)
        }

        private fun needsReload(): Boolean {
            val l = loaded ?: return true
            return l.split != split || plan.nodes.any { it.id !in l.requested }
        }

        // endregion
        // region probes and bookkeeping

        private fun planCandidates(): List<Cand> =
            plan.levels.flatMap { l -> l.nodes.map { Cand(it, l.tier, l.step) } }

        private fun candidates(): List<Cand> {
            val tags = loaded?.tags ?: return emptyList()
            return planCandidates().filter { it.tag in tags }
        }

        /**
         * Sweeps the effective pool in rank order and returns once [BrainConfig.earlyPassing] nodes passed, or the last good one did,
         * and no chain step before the earliest passing one has a node still out; the rest finishes behind the event loop.
         * WireGuard-family nodes already running in the box are checked through it.
         */
        private suspend fun probePool(): Map<NodeId, ProbeResult> {
            val full = selection.choice == LocationChoice.Any
            val good = remembered?.lastGood
            val ordered = sweepOrder(pool, good)
            val stepOf = ordered.associate { it.id to ChainStep.indexOf(selection.chain, it.subs) }
            val out = launchSweep(ordered)
            val fresh = HashMap<NodeId, ProbeResult>()
            var passed = 0
            var early = false
            for (batch in out) {
                record(batch)
                fresh += batch
                passed += batch.values.count { it.passed() }
                val enough = passed >= config.earlyPassing || (good != null && fresh[good].passed())
                if (enough && earlierStepsProbed(fresh, stepOf)) {
                    early = true
                    break
                }
            }
            if (!early) {
                if (full) activity.markFull()
                return fresh
            }
            val on = net.key
            scope.launch {
                var probed = fresh.size
                for (batch in out) {
                    probed += batch.size
                    events.send(Event.Batch(on, batch))
                }
                events.send(Event.SweepDone(probed, full, null))
            }
            return fresh
        }

        /** Batches finish out of order, so a later step can pass before an earlier one has been heard from. */
        private fun earlierStepsProbed(fresh: Map<NodeId, ProbeResult>, stepOf: Map<NodeId, Int>): Boolean {
            val best = fresh.entries.filter { it.value.passed() }.minOfOrNull { stepOf.getValue(it.key) } ?: return true
            return stepOf.none { (id, step) -> step < best && id !in fresh }
        }

        /** The location list's probe of the set pool; batches land in the stats as they finish. */
        private suspend fun startSetProbe(done: CompletableDeferred<ProbeRequestResult?>, full: Boolean) {
            val all = Pools.probeTargets(guard(emptyList()) { nodeRepo.nodes.first() }, selection)
            if (all.isEmpty()) {
                done.complete(ProbeRequestResult.NoNodes)
                return
            }
            val cur = current
            val targets = if (full) {
                all
            } else {
                val keep = listOfNotNull(cur?.node?.id) + keepFor(selection, all)
                val step = cur?.node?.let { ChainStep.indexOf(selection.chain, it.subs) }
                Pools.hotTargets(all, selection.chain, step, keep, stats.toMap(), nowMs = clock.nowMs(), verifiedTtlMs = config.verifiedTtlMs)
            }
            val whole = targets.size == all.size
            val out = launchSweep(sweepOrder(targets, current?.node?.id))
            val on = net.key
            scope.launch {
                var probed = 0
                for (batch in out) {
                    probed += batch.size
                    events.send(Event.Batch(on, batch))
                }
                events.send(Event.SweepDone(probed, whole, done))
            }
        }

        private fun sweepOrder(nodes: List<Node>, first: NodeId?): List<Node> {
            val labeled = nodes.map { if (it.id in promoted) it.copy(labels = it.labels + TierLabels.LEARNED_RU) else it }
            val byId = nodes.associateBy { it.id }
            return Pools.sweepOrder(labeled, stats.toMap(), selection.chain, first, mode, net.key.transport, clock.nowMs(), config.verifiedTtlMs)
                .map { byId.getValue(it.id) }
        }

        /** Starts a sweep beside the event loop; touches no session state, its batches arrive on the returned channel. */
        private fun launchSweep(ordered: List<Node>): Channel<Map<NodeId, ProbeResult>> {
            val out = Channel<Map<NodeId, ProbeResult>>(Channel.UNLIMITED)
            val on = net
            val running = loaded?.tags.orEmpty()
            val dns = split.settings.dns
            val now = clock.nowMs()
            val timeouts = ordered.associate { it.id.tag to config.probeTimeoutMs(on.key.transport, stats[it.id], now) }
            scope.launch {
                runSweep(ordered, config.sweepBatch(on.key.transport), sweepParallel(config, on.key.transport), activity, out) { batch ->
                    probeNodes(batch, running, on, dns, timeouts)
                }
            }
            return out
        }

        /** Touches no session state. */
        private suspend fun probeNodes(
            nodes: List<Node>,
            running: Set<String>,
            on: BrainEvent.NetworkChanged,
            dns: DnsSettings,
            timeouts: Map<String, Int>,
        ): Map<NodeId, ProbeResult> {
            val (inBox, offBox) = nodes.partition { (it.isEndpoint || it.protocol in WG_FAMILY) && it.id.tag in running }
            return probeOffBox(offBox, on, dns, timeouts) + liveResults(inBox, on)
        }

        private suspend fun probeOffBox(
            nodes: List<Node>,
            on: BrainEvent.NetworkChanged,
            dns: DnsSettings,
            timeouts: Map<String, Int>,
        ): Map<NodeId, ProbeResult> {
            if (nodes.isEmpty()) return emptyMap()
            val probe = try {
                configGen.probeConfig(nodes, dns)
            } catch (_: ConfigException) {
                return emptyMap()
            }
            val request = poolRequest(config, probe.json, on, nodes.associate { it.id.tag to (timeouts[it.id.tag] ?: config.unknownTimeoutMs(on.key.transport)) })
            val byTag = nodes.associateBy { it.id.tag }
            val raw = guard(emptyMap()) { prober.probePool(request, protector) }
            return raw.entries.mapNotNull { (tag, r) -> byTag[tag]?.let { it.id to r } }.toMap()
        }

        private suspend fun live(node: Node): ProbeResult =
            probeOne(node, net).also { record(mapOf(node.id to it)) }

        private suspend fun liveAll(nodes: List<Node>): Map<NodeId, ProbeResult> = liveResults(nodes, net).also { record(it) }

        private suspend fun liveResults(nodes: List<Node>, on: BrainEvent.NetworkChanged): Map<NodeId, ProbeResult> {
            if (nodes.isEmpty()) return emptyMap()
            val permits = Semaphore(config.liveConcurrency)
            return coroutineScope {
                nodes.distinctBy { it.id }.map { n -> async { permits.withPermit { n.id to probeOne(n, on) } } }.awaitAll().toMap()
            }
        }

        private suspend fun probeOne(node: Node, on: BrainEvent.NetworkChanged): ProbeResult =
            guard(ProbeResult.Fail(ProbeStage.GET204, "probe error")) {
                prober.probeLive(node.id.tag, PROBE_URL, config.unknownTimeoutMs(on.key.transport))
            }

        private fun byScore(): Comparator<Cand> = NodeScore.comparator({ stats[it.node.id] }, clock.nowMs(), config.verifiedTtlMs)

        /**
         * Once per session and network not seen recently: downloads a little through the current node and the next passing
         * candidates, one at a time beside the event loop; [onThroughput] judges the outcome.
         */
        private fun checkThroughput(fresh: Map<NodeId, ProbeResult>) {
            val cur = current ?: return
            if (!unseen || net.key in throughputChecked || _state.value !is ConnState.Connected) return
            throughputChecked += net.key
            val finalists = (listOf(cur) + candidates().filter { it.node.id != cur.node.id && fresh[it.node.id].passed() })
                .take(config.throughputFinalists)
                .map { it.node.id }
            val on = net.key
            scope.launch {
                val results = LinkedHashMap<NodeId, Throughput?>()
                for (id in finalists) {
                    results[id] = guard(null) { prober.throughputLive(id.tag, config.throughputUrl, config.throughputBytes, config.throughputTimeoutMs) }
                }
                events.send(Event.ThroughputDone(on, finalists, results))
            }
        }

        /**
         * Records throughput: short of the pass size a node is throttled, short of the throttled size it also counts a failed probe.
         * A current node that fell short gives way to the first finalist that passed.
         */
        private suspend fun onThroughput(event: Event.ThroughputDone) {
            if (event.key != net.key) return
            val passed = HashSet<NodeId>()
            val starved = HashMap<NodeId, ProbeResult>()
            val updated = event.results.mapNotNull { (id, sample) ->
                sample ?: return@mapNotNull null
                val bytes = if (sample.ok) sample.bytes else 0
                if (bytes >= config.throughputPassBytes) passed += id
                if (bytes < config.throughputThrottledBytes) starved[id] = ProbeResult.Fail(ProbeStage.THROUGHPUT, "throughput")
                stats[id].withThroughput(id, net.key, sample, throttled = id !in passed, alpha = config.rttAlpha).also { stats[id] = it }
            }
            if (updated.isEmpty()) return
            guard(Unit) { statRepo.upsert(updated) }
            record(starved)
            val cur = current ?: return
            if (cur.node.id in passed || cur.node.id !in event.results || event.results[cur.node.id] == null) return
            if (_state.value !is ConnState.Connected || flapLimited()) return
            val better = event.finalists.firstOrNull { it in passed } ?: return
            candidates().firstOrNull { it.node.id == better }?.let { switchTo(it) }
        }

        private suspend fun firstPassing(order: List<Cand>): Cand? {
            for (chunk in order.chunked(config.liveConcurrency.coerceAtLeast(1))) {
                val results = liveAll(chunk.map { it.node })
                chunk.firstOrNull { results[it.node.id].passed() }?.let { return it }
            }
            return null
        }

        /** Inside the wake grace window only passes count: a failure there may be state that died in sleep. */
        private suspend fun record(probed: Map<NodeId, ProbeResult>) {
            val results = if (inGrace()) probed.filterValues { it.passed() } else probed
            if (results.isEmpty()) return
            val now = clock.nowMs()
            val updated = results.map { (id, r) -> stats[id].next(id, net.key, r, now, config.rttAlpha).also { stats[id] = it } }
            guard(Unit) { statRepo.upsert(updated) }
            if (mode == NetworkMode.WHITELIST) {
                val learned = results.filterValues { it.passed() }.keys - promoted
                if (learned.isNotEmpty()) {
                    promoted += learned
                    guard(Unit) { learning.promote(net.key, learned) }
                }
            }
        }

        /** A switch inside the wake grace window may be forced by state that died in sleep, so it keeps the last good node. */
        private suspend fun rememberGood(id: NodeId) {
            val m = remembered
            val good = m?.lastGood?.takeIf { inGrace() } ?: id
            if (m?.lastGood != good || m.mode != mode) persist(good)
        }

        private suspend fun persist(lastGood: NodeId?) {
            val m = NetworkMemory(net.key, mode, lastGood, clock.nowMs())
            remembered = m
            guard(Unit) { memory.put(m) }
        }

        private suspend fun selectProxy(tag: String) = core { libbox.select(ConfigGenerator.TAG_PROXY, tag) }

        // endregion
    }

    private companion object {
        val TIERS = setOf(Tier.DIRECT, Tier.RU)
        val WG_FAMILY = setOf(Protocol.WIREGUARD, Protocol.AMNEZIAWG)

        fun Node.isWireGuardFamily(): Boolean = isEndpoint || protocol in WG_FAMILY

        /** Port failures degrade to [fallback]; cancellation still propagates. */
        inline fun <T> guard(fallback: T, block: () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

        inline fun core(block: () -> Unit) = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw Fatal(FailReason.CORE)
        }
    }
}
