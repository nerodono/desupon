package moe.desupon.vpn.lifecycle

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.subscription.SubId
import moe.desupon.vpn.service.DesuponVpnService
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVpnService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VpnStateHolderTest {
    private val holder = VpnStateHolder()
    private val connecting = ConnState.Connecting(NetworkMode.OPEN)

    @After
    fun reset() = ShadowVpnService.reset()

    @Test
    fun `idle until started, connecting while starting`() {
        assertEquals(ConnState.Idle, holder.state.value)
        assertTrue(holder.starting())
        assertFalse(holder.starting())
        assertEquals(VpnStateHolder.Phase.STARTING, holder.phase)
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
    }

    @Test
    fun `brain state shows only while running`() {
        holder.publish(connecting)
        assertEquals(ConnState.Idle, holder.state.value)
        holder.starting()
        holder.publish(connecting)
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
        holder.running()
        holder.publish(ConnState.Idle)
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
        holder.publish(connecting)
        assertEquals(connecting, holder.state.value)
    }

    @Test
    fun `stopping hides late brain states and ends idle`() {
        holder.starting()
        holder.running()
        holder.publish(connecting)
        holder.stopping()
        assertEquals(VpnStateHolder.Phase.STOPPING, holder.phase)
        assertEquals(ConnState.Idle, holder.state.value)
        holder.publish(connecting)
        assertEquals(ConnState.Idle, holder.state.value)
        holder.stopped()
        assertEquals(VpnStateHolder.Phase.STOPPED, holder.phase)
        assertEquals(ConnState.Idle, holder.state.value)
    }

    @Test
    fun `failure stays visible through stop until the next start`() {
        holder.starting()
        holder.running()
        holder.publish(ConnState.Failed("no_nodes"))
        holder.stopping()
        assertEquals(ConnState.Failed("no_nodes"), holder.state.value)
        holder.stopped("no_nodes")
        assertEquals(ConnState.Failed("no_nodes"), holder.state.value)
        assertTrue(holder.starting())
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
    }

    @Test
    fun `network mode shows while running and a failure keeps its detail`() {
        holder.publishMode(NetworkMode.WHITELIST)
        assertNull(holder.networkMode.value)
        holder.starting()
        holder.running()
        holder.publishMode(NetworkMode.WHITELIST)
        assertEquals(NetworkMode.WHITELIST, holder.networkMode.value)
        holder.stopping()
        assertNull(holder.networkMode.value)
        holder.stoppedWith(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "NL"))
        assertEquals(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "NL"), holder.state.value)
    }

    @Test
    fun `stop requests reach the bound service until it stops`() {
        var calls = 0
        assertFalse(holder.requestStop())
        holder.starting()
        holder.bindStop { calls++ }
        assertTrue(holder.requestStop())
        assertEquals(1, calls)
        holder.stopped()
        assertFalse(holder.requestStop())
        assertEquals(1, calls)
    }

    @Test
    fun `connect without consent fails without starting the service`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowVpnService.setPrepareResult(Intent("consent"))
        val control = ServiceVpnControl(context, holder)
        control.connect()
        assertEquals(ConnState.Failed(VpnFailReason.NOT_PREPARED), holder.state.value)
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `connect with consent starts the service and disconnect stops it in-process`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowVpnService.setPrepareResult(null)
        val control = ServiceVpnControl(context, holder)
        control.connect()
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
        assertEquals(VpnStateHolder.Phase.STARTING, holder.phase)
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(DesuponVpnService.ACTION_START, started.action)
        assertEquals(DesuponVpnService::class.java.name, started.component?.className)

        var stopped = false
        holder.bindStop { stopped = true }
        control.disconnect()
        assertTrue(stopped)
    }

    @Test
    fun `disconnect while stopped clears a shown failure`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        holder.stopped("core")
        ServiceVpnControl(context, holder).disconnect()
        assertEquals(ConnState.Idle, holder.state.value)
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `chain step shows only while running and clears on stop`() {
        val step = ChainStep.Sub(0, SubId(3))
        holder.publishStep(step)
        assertNull(holder.activeStep.value)
        holder.starting()
        holder.running()
        holder.publishStep(step)
        assertEquals(step, holder.activeStep.value)
        holder.stopping()
        assertNull(holder.activeStep.value)
        holder.publishStep(ChainStep.Rest)
        assertNull(holder.activeStep.value)
    }

    @Test
    fun `a retry wait shows the failure as retrying and a disconnect reaches the waiting service`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failure = ConnState.Failed(FailReason.CORE)
        var stops = 0
        holder.starting()
        holder.bindStop { stops++ }
        holder.running()
        holder.publish(failure)
        holder.stopping(retry = true)
        assertTrue(holder.retrying.value)
        holder.awaitingRetry(failure)
        assertEquals(VpnStateHolder.Phase.STOPPED, holder.phase)
        assertEquals(failure, holder.state.value)
        assertTrue(holder.retrying.value)

        val control = ServiceVpnControl(context, holder)
        assertTrue(control.retrying.value)
        control.disconnect()
        assertEquals(1, stops)
        assertNull(shadowOf(context as android.app.Application).nextStartedService)

        holder.stoppedWith(null)
        assertFalse(holder.retrying.value)
        assertEquals(ConnState.Idle, holder.state.value)
    }

    @Test
    fun `a start during the retry wait ends retrying`() {
        holder.starting()
        holder.running()
        holder.stopping(retry = true)
        holder.awaitingRetry(ConnState.Failed(FailReason.NO_NODES))
        assertTrue(holder.starting())
        assertFalse(holder.retrying.value)
        assertEquals(ConnState.Connecting(NetworkMode.UNKNOWN), holder.state.value)
    }

    @Test
    fun `a plain stop never shows retrying`() {
        holder.starting()
        holder.running()
        holder.publish(ConnState.Failed(FailReason.CORE))
        holder.stopping()
        assertFalse(holder.retrying.value)
    }
}
