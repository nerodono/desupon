package moe.desupon.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Tier
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UserInfo
import moe.desupon.ui.theme.ConnectionButtonState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleHomeStateTest {
    private val subs = listOf(sub(1), sub(2))
    private val connected = ConnState.Connected(node("SG 1", 1), Tier.DIRECT, NOW_MS)

    private fun map(conn: ConnState, retrying: Boolean = false, live: Set<SubId> = emptySet()) =
        simpleUiState(conn, retrying, subs, live, expired = false)

    @Test
    fun `idle and an unretried failure both read as tap to turn on`() {
        for (conn in listOf(ConnState.Idle, ConnState.Failed(FailReason.NOT_PREPARED), ConnState.Failed(FailReason.CORE))) {
            val s = map(conn)
            assertEquals(ConnectionButtonState.Disconnected, s.button, "$conn")
            assertEquals(SimpleStatus.TAP_TO_CONNECT, s.status, "$conn")
            assertEquals(ButtonAction.CONNECT, s.action, "$conn")
        }
    }

    @Test
    fun `a failure waiting for its retry says so and the button stops retrying`() {
        val s = map(ConnState.Failed(FailReason.NO_NODES), retrying = true)
        assertEquals(ConnectionButtonState.Error, s.button)
        assertEquals(SimpleStatus.RETRYING, s.status)
        assertEquals(ButtonAction.DISCONNECT, s.action)
    }

    @Test
    fun `progress states keep their own wording`() {
        val checking = map(ConnState.Connecting(NetworkMode.OPEN), live = setOf(SubId(2)))
        assertEquals(ConnectionButtonState.Checking(listOf(false, true)), checking.button)
        assertEquals(SimpleStatus.CHECKING, checking.status)
        assertEquals(SimpleStatus.CONNECTING, map(ConnState.TurnWarmup("whitelist")).status)
        assertEquals(SimpleStatus.SWITCHING, map(ConnState.Switching(node("A", 1), node("B", 2))).status)
        assertEquals(ButtonAction.DISCONNECT, checking.action)
        assertEquals(SimpleStatus.CONNECTING, simpleUiState(ConnState.Connecting(NetworkMode.OPEN), false, emptyList(), emptySet(), false).status)
    }

    @Test
    fun `every running state is just connected`() {
        val states = listOf(
            connected,
            ConnState.Degraded(node("SG 1", 1), NOW_MS),
            ConnState.Unstable(node("SG 1", 1)),
        )
        for (conn in states) {
            val s = map(conn)
            assertEquals(ConnectionButtonState.Connected, s.button, "$conn")
            assertEquals(SimpleStatus.CONNECTED, s.status, "$conn")
            assertEquals(ButtonAction.DISCONNECT, s.action, "$conn")
        }
    }

    @Test
    fun `an empty pool says there is nothing and disables the button while idle`() {
        for (conn in listOf(ConnState.Idle, ConnState.Failed(FailReason.NO_NODES))) {
            val s = simpleUiState(conn, false, emptyList(), emptySet(), expired = false, poolEmpty = true)
            assertEquals(SimpleStatus.NO_SERVERS, s.status, "$conn")
            assertFalse(s.buttonEnabled)
        }
        assertTrue(map(ConnState.Idle).buttonEnabled)
        val running = simpleUiState(connected, false, subs, emptySet(), expired = false, poolEmpty = true)
        assertEquals(SimpleStatus.CONNECTED, running.status, "a session that still runs is not interrupted by the label")
        assertTrue(running.buttonEnabled)
        val retrying = simpleUiState(ConnState.Failed(FailReason.CORE), true, subs, emptySet(), expired = false, poolEmpty = true)
        assertEquals(SimpleStatus.RETRYING, retrying.status)
    }

    @Test
    fun `pool emptiness follows the active set, or the subscriptions before sets load`() {
        assertTrue(poolEmpty(emptyList(), null))
        assertFalse(poolEmpty(subs, null))
        assertTrue(poolEmpty(subs, setEntry(0, "", 1, 2, active = true)))
        assertFalse(poolEmpty(emptyList(), setEntry(0, "", active = true).copy(enabledNodeCount = 1)))
    }

    @Test
    fun `retrying is ignored once the state moved on`() {
        assertEquals(SimpleStatus.CONNECTED, map(connected, retrying = true).status)
    }

    private fun expiring(id: Long, daysFromNow: Long) =
        sub(id, userInfo = UserInfo(0, 0, 0, (NOW_MS + daysFromNow * DAY_MILLIS) / 1000))

    @Test
    fun `expired only when every subscription of the active set ran out`() {
        val old = expiring(1, -2)
        val fresh = expiring(2, 10)
        val noDate = sub(3)
        val all = listOf(old, fresh, noDate)
        assertTrue(poolExpired(listOf(old, expiring(4, -1)), null, NOW_MS))
        assertFalse(poolExpired(all, null, NOW_MS))
        assertTrue(poolExpired(all, setOf(SubId(1)), NOW_MS))
        assertFalse(poolExpired(all, setOf(SubId(1), SubId(2)), NOW_MS))
        assertFalse(poolExpired(all, setOf(SubId(1), SubId(3)), NOW_MS), "a subscription without a date may still work")
        assertFalse(poolExpired(emptyList(), null, NOW_MS))
        assertFalse(poolExpired(all, emptySet(), NOW_MS))
    }

    private class FakeVpn : VpnControl {
        override val state = MutableStateFlow<ConnState>(ConnState.Idle)
        override val liveSubscriptions = MutableStateFlow<Set<SubId>>(emptySet())
        override val retrying = MutableStateFlow(false)
        var connects = 0
        var disconnects = 0

        override suspend fun connect() {
            connects++
        }

        override suspend fun disconnect() {
            disconnects++
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterTest
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun usable(e: SubscriptionSetEntry) = e.copy(supportedNodeCount = 3, enabledNodeCount = 3)

    @Test
    fun `view model follows the connection, the retry flag and the active set`() = runTest {
        val vpn = FakeVpn()
        val entries = MutableStateFlow<List<SubscriptionEntry>>(listOf(expiring(1, -1), expiring(2, 5)))
        val sets = MutableStateFlow(listOf(usable(setEntry(0, "", 1, 2, active = true)), usable(setEntry(7, "Old", 1))))
        val vm = SimpleHomeViewModel(vpn, entries, sets, object : Clock {
            override fun nowMs() = NOW_MS
            override fun elapsedMs() = 0L
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertEquals(SimpleStatus.TAP_TO_CONNECT, vm.state.filterNotNull().first().status)
        assertFalse(vm.state.value!!.expired)

        sets.value = listOf(usable(setEntry(0, "", 1, 2)), usable(setEntry(7, "Old", 1, active = true)))
        assertTrue(vm.state.value!!.expired)

        vpn.state.value = ConnState.Failed(FailReason.CORE)
        vpn.retrying.value = true
        assertEquals(SimpleStatus.RETRYING, vm.state.value!!.status)
        vm.disconnect()
        assertEquals(1, vpn.disconnects)

        vpn.retrying.value = false
        vpn.state.value = ConnState.Idle
        vm.connect()
        assertEquals(1, vpn.connects)

        sets.value = listOf(setEntry(0, "", 1, 2), usable(setEntry(7, "Old", 1, active = true)))
        assertEquals(SimpleStatus.TAP_TO_CONNECT, vm.state.value!!.status, "only the active set counts")
        sets.value = listOf(usable(setEntry(0, "", 1, 2)), setEntry(7, "Old", 1, active = true))
        assertEquals(SimpleStatus.NO_SERVERS, vm.state.value!!.status)
        assertFalse(vm.state.value!!.buttonEnabled)
    }
}
