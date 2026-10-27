package moe.desupon.vpn.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.vpn.lifecycle.VpnStateHolder
import moe.desupon.vpn.notify.StatusNotification
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowVpnService
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The graph has no core runtime, so every session fails at attach, standing in for a core failure. */
@RunWith(RobolectricTestRunner::class)
class ServiceRetryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val holder = VpnStateHolder()
    private lateinit var settings: SettingsRepository
    private lateinit var service: ServiceController<DesuponVpnService>

    private fun start(simpleMode: Boolean) {
        settings = SettingsRepository(SettingsRepository.createDataStore(File(tmp.root, SettingsRepository.FILE_NAME)))
        runBlocking { settings.setSimpleMode(simpleMode) }
        startKoin { modules(module { single { holder }; single { settings }; single { NetworkObserver(context) } }) }
        ShadowVpnService.setPrepareResult(null)
        service = Robolectric.buildService(DesuponVpnService::class.java, DesuponVpnService.startIntent(context)).create().startCommand(0, 1)
    }

    @After
    fun reset() {
        stopKoin()
        ShadowVpnService.reset()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    private fun awaitRetrying() = awaitUntil("retry wait") { holder.retrying.value && holder.phase == VpnStateHolder.Phase.STOPPED }

    private fun notificationText(): CharSequence? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == StatusNotification.ID }?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)

    @Test
    fun `simple mode keeps the service up and retries a failed session after the backoff`() {
        start(simpleMode = true)
        awaitRetrying()
        val shadow = shadowOf(service.get())
        assertEquals(ConnState.Failed(FailReason.CORE), holder.state.value)
        assertFalse(shadow.isStoppedBySelf)
        assertFalse(shadow.isForegroundStopped)
        awaitUntil("retry notification") { notificationText()?.toString() == "Не получилось. Пробую ещё раз…" }

        // Every start goes through startForeground with a fresh notification, so its identity marks an attempt.
        val first = shadow.lastForegroundNotification
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertTrue(shadow.lastForegroundNotification === first, "retried before 30 s")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        awaitUntil("second attempt") { shadow.lastForegroundNotification !== first }
        val second = shadow.lastForegroundNotification
        awaitRetrying()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertTrue(shadow.lastForegroundNotification === second, "second retry before 60 s")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        awaitUntil("third attempt") { shadow.lastForegroundNotification !== second }
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun `a disconnect during the wait stops the service and ends retrying`() {
        start(simpleMode = true)
        awaitRetrying()

        assertTrue(holder.requestStop())
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(service.get())
        assertTrue(shadow.isStoppedBySelf)
        assertFalse(holder.retrying.value)
        assertEquals(ConnState.Idle, holder.state.value)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        assertEquals(VpnStateHolder.Phase.STOPPED, holder.phase)
        assertEquals(ConnState.Idle, holder.state.value)
    }

    @Test
    fun `turning simple mode off during the wait stops with the failure shown`() {
        start(simpleMode = true)
        awaitRetrying()

        runBlocking { settings.setSimpleMode(false) }
        awaitUntil("give-up") { !holder.retrying.value }
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertEquals(ConnState.Failed(FailReason.CORE), holder.state.value)
    }

    @Test
    fun `normal mode stops on failure without retrying`() {
        start(simpleMode = false)
        awaitUntil("stop") { shadowOf(service.get()).isStoppedBySelf }
        assertFalse(holder.retrying.value)
        assertEquals(ConnState.Failed(FailReason.CORE), holder.state.value)
        assertEquals(VpnStateHolder.Phase.STOPPED, holder.phase)
    }

    @Test
    fun `a stop intent during the wait ends it too`() {
        start(simpleMode = true)
        awaitRetrying()
        service.withIntent(Intent(DesuponVpnService.stopIntent(context))).startCommand(0, 2)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertFalse(holder.retrying.value)
        assertEquals(ConnState.Idle, holder.state.value)
    }
}
