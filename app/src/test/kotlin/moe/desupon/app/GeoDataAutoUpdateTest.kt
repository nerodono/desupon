@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

class GeoDataAutoUpdateTest {
    private val log = mutableListOf<String>()

    private inner class FakeJobs(val failInstall: Boolean = false) : GeoDataAutoUpdate.Jobs {
        override suspend fun installSets() {
            log += "install"
            if (failInstall) error("no space")
        }

        override suspend fun watchRules() {
            log += "watch"
            awaitCancellation()
        }

        override fun check(routeChanged: Boolean) {
            log += if (routeChanged) "check after route change" else "check"
        }
    }

    private val node = Node(
        NodeId("ab".repeat(32)), emptySet(), Protocol.VLESS, "Node", "NL", Capability.SUPPORTED, Secret("{}"),
        isEndpoint = false, udpOnly = false,
    )
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("unused")
    }

    @Test
    fun checksOnForegroundWhenTheTunnelComesUpAndPeriodicallyWhileItStaysUp() = runTest {
        val vpn = MutableStateFlow<ConnState>(ConnState.Idle)
        val auto = GeoDataAutoUpdate(FakeJobs(), vpn, backgroundScope)
        auto.start()
        runCurrent()
        assertEquals(listOf("install", "watch"), log, "bundled sets go on disk before rules are watched")

        log.clear()
        auto.onStart(owner)
        vpn.value = ConnState.Connecting(NetworkMode.OPEN)
        runCurrent()
        assertEquals(listOf("check"), log)

        log.clear()
        vpn.value = ConnState.Connected(node, Tier.DIRECT, 0)
        runCurrent()
        assertEquals(listOf("check after route change"), log)

        log.clear()
        vpn.value = ConnState.Switching(node, node)
        advanceTimeBy(GeoDataAutoUpdate.CHECK_EVERY_MS - 1)
        runCurrent()
        assertEquals(emptyList(), log, "staying up is no new session")
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(GeoDataAutoUpdate.CHECK_EVERY_MS)
        runCurrent()
        assertEquals(listOf("check", "check"), log)

        log.clear()
        vpn.value = ConnState.Idle
        runCurrent()
        advanceTimeBy(3 * GeoDataAutoUpdate.CHECK_EVERY_MS)
        runCurrent()
        assertEquals(emptyList(), log, "no checks while down")

        vpn.value = ConnState.Unstable(node)
        runCurrent()
        assertEquals(listOf("check after route change"), log)
    }

    @Test
    fun rulesAreWatchedEvenWhenInstallingTheBundledSetsFails() = runTest {
        GeoDataAutoUpdate(FakeJobs(failInstall = true), MutableStateFlow(ConnState.Idle), backgroundScope).start()
        runCurrent()
        assertEquals(listOf("install", "watch"), log)
    }
}
