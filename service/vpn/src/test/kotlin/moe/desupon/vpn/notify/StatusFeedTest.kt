package moe.desupon.vpn.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Tier
import moe.desupon.vpn.R
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StatusFeedTest {
    private val node = testNode()
    private val connected = ConnState.Connected(node, Tier.DIRECT, 1_000)
    private val state = MutableStateFlow<ConnState>(connected)
    private val retrying = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val traffic = MutableStateFlow(traffic())
    private val rtt = MutableStateFlow<Int?>(45)
    private val posted = mutableListOf<Pair<Long, StatusContent>>()

    private fun TestScope.collect() {
        val feed = StatusFeed(state, retrying, screenOn, traffic, rtt = { rtt }, subscription = { MutableStateFlow(SubscriptionLabel("A", 1)) })
        backgroundScope.launch { feed.contents.collect { posted += currentTime to it } }
        runCurrent()
    }

    private val last get() = posted.last().second

    @Test
    fun `screen off posts nothing and stops collecting traffic`() = runTest {
        screenOn.value = false
        collect()
        traffic.value = traffic(down = 10)
        state.value = ConnState.Switching(node, node)
        state.value = connected
        advanceTimeBy(60_000)
        assertTrue(posted.isEmpty())
        assertEquals(0, traffic.subscriptionCount.value)

        screenOn.value = true
        runCurrent()
        assertEquals(1, posted.size, "the current content is posted as soon as the screen turns on")
        assertEquals(1, traffic.subscriptionCount.value)

        screenOn.value = false
        runCurrent()
        assertEquals(0, traffic.subscriptionCount.value)
        traffic.value = traffic(down = 20)
        advanceTimeBy(60_000)
        assertEquals(1, posted.size)
    }

    @Test
    fun `traffic is reposted at most every 2 s`() = runTest {
        collect()
        repeat(100) {
            traffic.value = traffic(down = it.toLong() + 1)
            advanceTimeBy(100)
        }
        val times = posted.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= StatusFeed.INTERVAL_MS }, "$times")
        assertEquals(5, posted.size)
        assertEquals(traffic(down = 81), (last as StatusContent.Connected).traffic, "each post carries the latest traffic")
    }

    @Test
    fun `a ping, node or state change is posted at once`() = runTest {
        collect()
        advanceTimeBy(500)
        rtt.value = 60
        runCurrent()
        assertEquals(500L, posted.last().first)
        assertEquals(60, (last as StatusContent.Connected).rttMs)

        advanceTimeBy(100)
        val other = testNode("Helsinki 1", "FI")
        state.value = ConnState.Connected(other, Tier.DIRECT, 1_000)
        runCurrent()
        assertEquals(600L, posted.last().first)
        assertEquals(other, (last as StatusContent.Connected).node)

        state.value = ConnState.Connecting(NetworkMode.OPEN)
        runCurrent()
        assertEquals(StatusContent.Plain(R.string.svc_state_connecting), last)
        assertEquals(1, posted.count { it.second is StatusContent.Plain })
    }

    @Test
    fun `non-connected states carry only the state word, a pending retry its own`() = runTest {
        state.value = ConnState.Failed(FailReason.CORE)
        collect()
        assertEquals(StatusContent.Plain(R.string.svc_state_failed), last)
        retrying.value = true
        runCurrent()
        assertEquals(StatusContent.Plain(R.string.svc_state_retrying), last)
        assertEquals(0, traffic.subscriptionCount.value)
        assertIs<StatusContent.Plain>(last)
    }
}
