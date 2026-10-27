package moe.desupon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.VpnControl
import moe.desupon.brain.probe.PoolProbes
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.data.location.LocationListData
import moe.desupon.data.repo.LocationFallback
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId

class HomeViewModel(
    private val vpn: VpnControl,
    subscriptions: Flow<List<SubscriptionEntry>>,
    sets: Flow<List<SubscriptionSetEntry>>,
    locations: Flow<LocationListData>,
    fallbacks: Flow<LocationFallback>,
    private val clock: Clock,
) : ViewModel() {
    private data class Conn(val state: ConnState, val sessionStartMs: Long?)

    private val conn: Flow<Conn> = vpn.state
        .runningFold(Conn(ConnState.Idle, null)) { prev, s -> Conn(s, nextSessionStart(prev.sessionStartMs, s)) }
        .drop(1)

    /** Keeps "days left" current on a screen left open across midnight. */
    private val minutes: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(TICK_MS)
        }
    }

    private val runningStep = combine(vpn.activeStep, vpn.state) { step, s -> step.takeIf { s.running } }

    private val choiceAndSets: Flow<SelectionContext> =
        combine(vpn.location, vpn.activeSetId, sets, vpn.activeChain, runningStep) { choice, setId, s, chain, step ->
            SelectionContext(choice, setId, s, chain = chain, step = step)
        }

    /** The home row reads the list only to name a pinned node, sparing the network watch otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val homeSelection: Flow<SelectionContext> = choiceAndSets.flatMapLatest { ctx ->
        if (ctx.choice is LocationChoice.Node) locations.map { ctx.copy(list = it) } else flowOf(ctx)
    }

    val state: StateFlow<HomeUiState> =
        combine(conn, subscriptions, minutes, vpn.liveSubscriptions, homeSelection) { c, subs, now, live, sel ->
            homeUiState(c.state, subs, c.sessionStartMs, now, live, sel)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

    /** Outlives sheet subscriptions, so reopening the sheet mid-probe still shows the previous values. */
    private var rttMemory = RttMemory()

    private val sheetLocations: Flow<LocationListData> = locations.map { data ->
        val (filled, memory) = carryRtts(rttMemory, data)
        rttMemory = memory
        filled
    }

    private val forcedProbe = MutableStateFlow(false)

    /** Null until the inputs arrive; collect only while the sheet is open. */
    val sheet: StateFlow<LocationSheetState?> =
        combine(choiceAndSets, sheetLocations, vpn.networkMode, forcedProbe, subscriptions) { sel, list, mode, forced, subs ->
            locationSheetState(list, sel.sets, sel.setId, sel.choice, mode, forced, subs, chainLine(subs, sel.chain, sel.step))
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)

    /** One-shot UI effects; buffered while nobody collects. */
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val sheetOpen = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            fallbacks.collect { f -> _events.send(HomeEvent.PinFellBack((f.to as? LocationChoice.Country)?.iso?.let(::countryName))) }
        }
    }

    /** Caller must have obtained VPN consent first. */
    fun connect() {
        viewModelScope.launch { vpn.connect() }
    }

    fun disconnect() {
        viewModelScope.launch { vpn.disconnect() }
    }

    fun selectLocation(choice: LocationChoice) {
        viewModelScope.launch {
            if (!vpn.setLocation(choice)) _events.send(HomeEvent.LocationRejected)
        }
    }

    fun selectSet(id: SetId) {
        viewModelScope.launch { vpn.setActiveSet(id) }
    }

    /** A running session re-plans by itself; a failed or idle one is reconnected. */
    fun useAnyCountry() {
        viewModelScope.launch {
            if (!vpn.setLocation(LocationChoice.Any)) {
                _events.send(HomeEvent.LocationRejected)
                return@launch
            }
            val s = vpn.state.value
            if (s is ConnState.Idle || s is ConnState.Failed) _events.send(HomeEvent.Connect)
        }
    }

    /** The user's explicit refresh; the only probe request that reports throttling. */
    fun refreshProbes() {
        viewModelScope.launch {
            forcedProbe.value = true
            val result = try {
                vpn.requestProbe(forced = true)
            } finally {
                forcedProbe.value = false
            }
            if (result is ProbeRequestResult.Throttled) _events.send(HomeEvent.ProbeThrottled(throttleSeconds(result.retryInMs)))
        }
    }

    fun setSheetOpen(open: Boolean) {
        sheetOpen.value = open
    }

    /** Run while Home is visible: probes at once and every auto interval, and again at once whenever the sheet opens. */
    suspend fun runProbeCadence() {
        val opens = sheetOpen.drop(1).filter { it }.map { }
        probeCadence(opens) {
            when (val r = vpn.requestProbe(forced = false)) {
                is ProbeRequestResult.Throttled -> r.retryInMs.coerceAtLeast(MIN_RETRY_MS)
                else -> PoolProbes.AUTO_INTERVAL_MS
            }
        }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIN_RETRY_MS = 1_000L
    }
}
