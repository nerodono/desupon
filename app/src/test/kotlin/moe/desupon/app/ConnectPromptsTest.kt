package moe.desupon.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectPromptsTest {
    private val log = mutableListOf<String>()
    private var open = 0

    private inner class FakePrompt(val name: String, var due: Boolean = true) : ConnectPrompts.Prompt {
        var asks = 0
        var answer = CompletableDeferred<Unit>()

        override suspend fun due() = due

        override suspend fun markAsked() {
            due = false
        }

        override suspend fun ask() {
            asks++
            open++
            log += "$name open (open=$open)"
            answer.await()
            open--
            log += "$name answered"
        }
    }

    private val node = Node(
        NodeId("ab".repeat(32)), emptySet(), Protocol.VLESS, "Node", "NL", Capability.SUPPORTED, Secret("{}"),
        isEndpoint = false, udpOnly = false,
    )
    private val connected = ConnState.Connected(node, Tier.DIRECT, 0)
    private val connecting = ConnState.Connecting(NetworkMode.OPEN)

    @Test
    fun `the battery prompt waits until the notification prompt is answered`() = runTest {
        val notifications = FakePrompt("notifications")
        val battery = FakePrompt("battery")
        val states = MutableStateFlow<ConnState>(ConnState.Idle)
        backgroundScope.launch { ConnectPrompts(notifications, battery).run(states) }
        runCurrent()
        assertEquals(emptyList(), log)

        states.value = connecting
        runCurrent()
        states.value = connected
        runCurrent()
        assertEquals(listOf("notifications open (open=1)"), log)
        assertEquals(0, battery.asks)

        notifications.answer.complete(Unit)
        runCurrent()
        assertEquals(listOf("notifications open (open=1)", "notifications answered", "battery open (open=1)"), log)
        battery.answer.complete(Unit)
        runCurrent()
        assertEquals(0, open)
    }

    @Test
    fun `both due on reaching connected at once still come one after the other`() = runTest {
        val notifications = FakePrompt("notifications").apply { answer.complete(Unit) }
        val battery = FakePrompt("battery").apply { answer.complete(Unit) }
        backgroundScope.launch { ConnectPrompts(notifications, battery).run(MutableStateFlow(connected)) }
        runCurrent()
        assertEquals(listOf("notifications open (open=1)", "notifications answered", "battery open (open=1)", "battery answered"), log)
    }

    @Test
    fun `each prompt is asked once, the battery one only when connected`() = runTest {
        val notifications = FakePrompt("notifications").apply { answer.complete(Unit) }
        val battery = FakePrompt("battery").apply { answer.complete(Unit) }
        val states = MutableStateFlow<ConnState>(connecting)
        backgroundScope.launch { ConnectPrompts(notifications, battery).run(states) }
        runCurrent()
        assertEquals(1, notifications.asks)
        assertEquals(0, battery.asks)

        for (s in listOf(connected, ConnState.Failed(FailReason.CORE), connecting, connected, ConnState.Idle, connected)) {
            states.value = s
            runCurrent()
        }
        assertEquals(1, notifications.asks)
        assertEquals(1, battery.asks)
    }

    @Test
    fun `a prompt that is not due is skipped`() = runTest {
        val notifications = FakePrompt("notifications", due = false)
        val battery = FakePrompt("battery", due = false)
        backgroundScope.launch { ConnectPrompts(notifications, battery).run(MutableStateFlow(connected)) }
        runCurrent()
        assertEquals(0, notifications.asks + battery.asks)
    }
}
