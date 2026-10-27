package moe.desupon.feature.home

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.VpnControl
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.data.location.ProbeState
import moe.desupon.data.repo.LocationFallback
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.ConnectionButtonState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private class FakeVpn : VpnControl {
        override val state = MutableStateFlow<ConnState>(ConnState.Idle)
        override val liveSubscriptions = MutableStateFlow<Set<SubId>>(emptySet())
        override val networkMode = MutableStateFlow<NetworkMode?>(null)
        override val probing = MutableStateFlow<Set<NodeId>>(emptySet())
        override val activeSetId = MutableStateFlow(SetId.ALL)
        override val location = MutableStateFlow<LocationChoice>(LocationChoice.Any)
        override val activeChain = MutableStateFlow<List<SubId>>(emptyList())
        override val activeStep = MutableStateFlow<ChainStep?>(null)
        var onProbe: () -> Unit = {}
        var connects = 0
        var disconnects = 0
        var acceptLocation = true
        var probeResult: ProbeRequestResult = ProbeRequestResult.Done(1)
        val probeRequests = mutableListOf<Boolean>()
        var probeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun connect() {
            connects++
        }

        override suspend fun disconnect() {
            disconnects++
        }

        override suspend fun setActiveSet(id: SetId): Boolean {
            activeSetId.value = id
            return true
        }

        override suspend fun setLocation(choice: LocationChoice): Boolean {
            if (acceptLocation) location.value = choice
            return acceptLocation
        }

        override suspend fun requestProbe(forced: Boolean): ProbeRequestResult {
            probeRequests += forced
            onProbe()
            probeGate?.await()
            return probeResult
        }
    }

    private object FixedClock : Clock {
        override fun nowMs() = NOW_MS

        override fun elapsedMs() = 0L
    }

    private val vpn = FakeVpn()
    private val subs = MutableStateFlow<List<SubscriptionEntry>>(emptyList())
    private val sets = MutableStateFlow(listOf(setEntry(0, "", 1, 2, active = true), setEntry(4, "Работа", 2)))
    private val locations = MutableStateFlow(listData())
    private val fallbacks = MutableSharedFlow<LocationFallback>(extraBufferCapacity = 4)

    private fun vm() = HomeViewModel(vpn, subs, sets, locations, fallbacks, FixedClock)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun timerStartIsKeptWhileDegraded() = runTest {
        val vm = vm()
        val n = node("X", 1)
        vm.state.test {
            assertEquals(HomeUiState.Empty, expectMostRecentItem())
            subs.value = listOf(sub(1))
            assertEquals(ConnectionButtonState.Disconnected, assertIs<HomeUiState.Ready>(awaitItem()).button)
            vpn.state.value = ConnState.Connected(n, Tier.DIRECT, connectedAtMs = 500)
            assertEquals(500, assertIs<HomeUiState.Ready>(awaitItem()).sessionStartMs)
            vpn.state.value = ConnState.Degraded(n, sinceMs = 900)
            val degraded = assertIs<HomeUiState.Ready>(awaitItem())
            assertEquals(Caption.Degraded, degraded.caption)
            assertEquals(500, degraded.sessionStartMs)
            vpn.state.value = ConnState.Idle
            assertEquals(null, assertIs<HomeUiState.Ready>(awaitItem()).sessionStartMs)
        }
    }

    @Test
    fun actionsReachVpnControl() = runTest {
        val vm = vm()
        vm.connect()
        vm.disconnect()
        assertEquals(1, vpn.connects)
        assertEquals(1, vpn.disconnects)
    }

    @Test
    fun poolCheckProgressLightsArcs() = runTest {
        val vm = vm()
        subs.value = listOf(sub(1), sub(2))
        vpn.state.value = ConnState.Connecting(NetworkMode.OPEN)
        vm.state.test {
            assertEquals(ConnectionButtonState.Checking(listOf(false, false)), assertIs<HomeUiState.Ready>(expectMostRecentItem()).button)
            vpn.liveSubscriptions.value = setOf(SubId(2))
            assertEquals(ConnectionButtonState.Checking(listOf(false, true)), assertIs<HomeUiState.Ready>(awaitItem()).button)
        }
    }

    @Test
    fun bottomRowFollowsChoiceAndActiveSet() = runTest {
        val vm = vm()
        subs.value = listOf(sub(1), sub(2))
        vm.state.test {
            assertEquals(Place.AnyCountry, assertIs<HomeUiState.Ready>(expectMostRecentItem()).location.place)
            vm.selectLocation(LocationChoice.country("NL"))
            assertEquals(Place.Named("🇳🇱", "Нидерланды"), assertIs<HomeUiState.Ready>(awaitItem()).location.place)
            vm.selectSet(SetId(4))
            assertEquals("Работа", assertIs<HomeUiState.Ready>(awaitItem()).location.setName)
        }
    }

    @Test
    fun sheetReflectsListModeAndOnlyTheUsersProbe() = runTest {
        val vm = vm()
        locations.value = listData(countries = listOf(locCountry("NL", ProbeState.OK, 131), locCountry("RU", ProbeState.UNKNOWN)))
        vpn.location.value = LocationChoice.country("NL")
        vm.sheet.test {
            val first = assertNotNull(expectMostRecentItem())
            assertEquals(listOf("RU", "NL"), first.countries.map { it.iso })
            assertEquals(listOf(SetId.ALL, SetId(4)), first.sets.map { it.id })
            assertEquals(false, first.whitelistBanner)
            vpn.networkMode.value = NetworkMode.WHITELIST
            assertTrue(assertNotNull(awaitItem()).whitelistBanner)
            vpn.probing.value = setOf(nodeId("a"))
            expectNoEvents()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            vpn.probeGate = gate
            vm.refreshProbes()
            assertTrue(assertNotNull(awaitItem()).refreshing)
            gate.complete(Unit)
            assertEquals(false, assertNotNull(awaitItem()).refreshing)
        }
    }

    @Test
    fun rejectedLocationAndForcedThrottleBecomeEvents() = runTest {
        val vm = vm()
        vm.events.test {
            vpn.acceptLocation = false
            vm.selectLocation(LocationChoice.Node(nodeId("gone")))
            assertEquals(HomeEvent.LocationRejected, awaitItem())

            vpn.probeResult = ProbeRequestResult.Throttled(retryInMs = 12_300)
            vm.refreshProbes()
            assertEquals(HomeEvent.ProbeThrottled(13), awaitItem())
            assertEquals(listOf(true), vpn.probeRequests)
            expectNoEvents()
        }
    }

    @Test
    fun cadenceProbesEvery15sAndAtOnceWhenTheSheetOpens() = runTest {
        val vm = vm()
        val times = mutableListOf<Long>()
        vpn.onProbe = { times += currentTime }
        val cadence = backgroundScope.launch { vm.runProbeCadence() }
        runCurrent()
        advanceTimeBy(31_000)
        assertEquals(listOf(0L, 15_000L, 30_000L), times)

        vm.setSheetOpen(true)
        runCurrent()
        advanceTimeBy(16_000)
        vm.setSheetOpen(false)
        advanceTimeBy(15_000)
        assertEquals(listOf(0L, 15_000L, 30_000L, 31_000L, 46_000L, 61_000L), times)

        cadence.cancel()
        advanceTimeBy(60_000)
        assertEquals(6, times.size)
        assertTrue(vpn.probeRequests.none { it })
    }

    @Test
    fun cadenceWaitsOutThrottleSilently() = runTest {
        val vm = vm()
        val times = mutableListOf<Long>()
        vpn.onProbe = { times += currentTime }
        vpn.probeResult = ProbeRequestResult.Throttled(retryInMs = 4_000)
        vm.events.test {
            backgroundScope.launch { vm.runProbeCadence() }
            runCurrent()
            vpn.probeResult = ProbeRequestResult.Done(3)
            advanceTimeBy(20_000)
            assertEquals(listOf(0L, 4_000L, 19_000L), times)
            expectNoEvents()
        }
    }

    @Test
    fun chainHighlightsTheStepOnlyWhileRunning() = runTest {
        val vm = vm()
        subs.value = listOf(sub(1, "Финка"), sub(2, "OverSecure"))
        vpn.activeChain.value = listOf(SubId(1), SubId(2))
        vpn.activeStep.value = ChainStep.Sub(1, SubId(2))
        vm.state.test {
            val idle = assertIs<HomeUiState.Ready>(expectMostRecentItem()).location.chain
            assertEquals(ChainLine(listOf(SubscriptionLabel("Финка", 1), SubscriptionLabel("OverSecure", 2))), idle)
            vpn.state.value = ConnState.Connected(node("X", 2), Tier.DIRECT, connectedAtMs = 1)
            assertEquals(1, assertIs<HomeUiState.Ready>(expectMostRecentItem()).location.chain?.active)
            vpn.activeStep.value = ChainStep.Rest
            val rest = assertNotNull(assertIs<HomeUiState.Ready>(awaitItem()).location.chain)
            assertEquals(null, rest.active)
            assertTrue(rest.rest)
        }
        vm.sheet.test {
            assertEquals(true, assertNotNull(expectMostRecentItem()).chain?.rest)
        }
    }

    @Test
    fun sheetKeepsLastRttWhileRechecking() = runTest {
        val vm = vm()
        val ok = listData(countries = listOf(locCountry("NL", ProbeState.OK, 90, listOf(locNode("Ams", "NL", ProbeState.OK, 90)))))
        val checking = listData(countries = listOf(locCountry("NL", ProbeState.CHECKING, null, listOf(locNode("Ams", "NL", ProbeState.CHECKING)))))
        locations.value = ok
        vm.sheet.test {
            assertEquals(90, assertNotNull(expectMostRecentItem()).countries.single().bestRttMs)
            locations.value = checking
            val row = assertNotNull(awaitItem()).countries.single()
            assertEquals(ProbeState.CHECKING, row.state)
            assertEquals(90, row.bestRttMs)
            assertEquals(90, row.nodes.single().rttMs)
        }
    }

    @Test
    fun anyCountryReconnectsOnlyWhenNotRunning() = runTest {
        val vm = vm()
        vpn.location.value = LocationChoice.country("NL")
        vm.events.test {
            vpn.state.value = ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "NL")
            vm.useAnyCountry()
            assertEquals(HomeEvent.Connect, awaitItem())
            assertEquals(LocationChoice.Any, vpn.location.value)

            vpn.state.value = ConnState.Degraded(node("X", 1), 5, FailReason.COUNTRY_UNAVAILABLE, "NL")
            vm.useAnyCountry()
            expectNoEvents()
        }
    }

    @Test
    fun pinFallbacksBecomeEventsWithCountryName() = runTest {
        val vm = vm()
        vm.events.test {
            fallbacks.emit(LocationFallback(nodeId("a"), LocationChoice.country("DE")))
            assertEquals(HomeEvent.PinFellBack("Германия"), awaitItem())
            fallbacks.emit(LocationFallback(nodeId("b"), LocationChoice.Any))
            assertEquals(HomeEvent.PinFellBack(null), awaitItem())
        }
    }

    @Test
    fun pinnedNodeIsNamedFromTheLocationList() = runTest {
        val vm = vm()
        subs.value = listOf(sub(1))
        locations.value = listData(countries = listOf(locCountry("DE", ProbeState.OK, 50, listOf(locNode("Berlin", "DE")))))
        vpn.location.value = LocationChoice.Node(nodeId("Berlin"))
        vm.state.test {
            assertEquals(Place.Named("🇩🇪", "Berlin"), assertIs<HomeUiState.Ready>(expectMostRecentItem()).location.place)
            locations.value = listData()
            assertEquals(Place.UnknownNode, assertIs<HomeUiState.Ready>(awaitItem()).location.place)
        }
    }
}
