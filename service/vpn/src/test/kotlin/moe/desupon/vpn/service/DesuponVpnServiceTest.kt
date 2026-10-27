package moe.desupon.vpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.TunnelRecovery
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.vpn.lifecycle.VpnFailReason
import moe.desupon.vpn.lifecycle.VpnStateHolder
import moe.desupon.vpn.tile.DesuponTileService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowVpnService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DesuponVpnServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val holder = VpnStateHolder()

    @Before
    fun koin() {
        startKoin { modules(module { single { holder } }) }
    }

    @After
    fun reset() {
        stopKoin()
        ShadowVpnService.reset()
    }

    @Test
    fun `vpn service is private, bound by the system only and always-on capable`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, DesuponVpnService::class.java), PackageManager.GET_META_DATA)
        assertEquals(android.Manifest.permission.BIND_VPN_SERVICE, info.permission)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, info.foregroundServiceType)
        assertFalse(info.metaData?.containsKey(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON) == true)
        val resolved = context.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
        assertTrue(resolved.any { it.serviceInfo.name == DesuponVpnService::class.java.name })
    }

    @Test
    fun `tile is bindable by the system only`() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, DesuponTileService::class.java), 0)
        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, info.permission)
        assertTrue(info.exported)
        val resolved = context.packageManager.queryIntentServices(Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName), 0)
        assertTrue(resolved.any { it.serviceInfo.name == DesuponTileService::class.java.name })
    }

    @Test
    fun `start without consent goes foreground, fails and stops itself`() {
        ShadowVpnService.setPrepareResult(Intent("consent"))
        val controller = Robolectric.buildService(DesuponVpnService::class.java, DesuponVpnService.startIntent(context)).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertEquals(moe.desupon.vpn.notify.StatusNotification.ID, shadow.lastForegroundNotificationId)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(ConnState.Failed(VpnFailReason.NOT_PREPARED), holder.state.value)
        assertEquals(VpnStateHolder.Phase.STOPPED, holder.phase)
    }

    @Test
    fun `always-on start intent is handled like a start`() {
        ShadowVpnService.setPrepareResult(Intent("consent"))
        val controller = Robolectric.buildService(DesuponVpnService::class.java, Intent(VpnService.SERVICE_INTERFACE)).create().startCommand(0, 1)
        assertEquals(moe.desupon.vpn.notify.StatusNotification.ID, shadowOf(controller.get()).lastForegroundNotificationId)
        assertEquals(ConnState.Failed(VpnFailReason.NOT_PREPARED), holder.state.value)
    }

    @Test
    fun `stop when not running stays idle`() {
        val controller = Robolectric.buildService(DesuponVpnService::class.java, DesuponVpnService.stopIntent(context)).create().startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals(ConnState.Idle, holder.state.value)
    }

    @Test
    fun `only fresh installs count as added packages`() {
        val added = Intent(Intent.ACTION_PACKAGE_ADDED, android.net.Uri.parse("package:ru.example.maps"))
        assertEquals("ru.example.maps", DesuponVpnService.addedPackage(added))
        assertNull(DesuponVpnService.addedPackage(Intent(added).putExtra(Intent.EXTRA_REPLACING, true)))
    }

    @Test
    fun `a refused foreground start maps to a failure reason`() {
        assertNull(DesuponVpnService.enterForeground {})
        assertEquals(VpnFailReason.NOT_PREPARED, DesuponVpnService.enterForeground { throw SecurityException("systemExempted") })
        assertEquals(VpnFailReason.START_BLOCKED, DesuponVpnService.enterForeground { throw IllegalStateException("background") })
    }

    @Test
    fun `wakes reach the brain with how long the device slept, the observer's unblocks as unblocks`() = runTest {
        val wakes = MutableSharedFlow<Long>()
        val observer = NetworkObserver(context)
        val calls = mutableListOf<String>()
        val target = object : TunnelRecovery {
            override fun onWake(sleptMs: Long) {
                calls += "wake:$sleptMs"
            }

            override fun onNetworkUnblocked() {
                calls += "unblocked"
            }
        }
        val forward = backgroundScope.launch {
            with(DesuponVpnService) { forwardRecovery(wakes, observer, { calls += "log" }, target) }
        }
        observer.start()
        runCurrent()

        wakes.emit(25_000)
        runCurrent()
        assertEquals(listOf("log", "wake:25000"), calls)

        val callback = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(100)
        callback.onBlockedStatusChanged(wifi, true)
        callback.onBlockedStatusChanged(wifi, false)
        runCurrent()
        assertEquals(listOf("log", "wake:25000", "log", "unblocked"), calls)
        forward.cancel()
        observer.stop()
    }
}
