package moe.desupon.platform.iface

import android.content.Context
import android.net.Network
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import moe.desupon.core.libbox.InterfaceUpdateListener
import moe.desupon.platform.net.NetworkObserver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class CorePlatformTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class RecordingHost : VpnHost {
        val underlying = mutableListOf<Array<Network>?>()

        override fun protect(fd: Int) = true

        override fun newTunBuilder(): VpnService.Builder = throw UnsupportedOperationException()

        override fun isVpnPrepared() = true

        override val sessionLabel = "test"

        override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
            underlying += networks
            return true
        }

        override fun onCoreStopRequested() = Unit
    }

    @Test
    fun interfaceMonitorHandsTheUnderlyingNetworkToTheVpn() {
        val host = RecordingHost()
        val observer = NetworkObserver(context)
        val platform = CorePlatform(context, host, observer)
        platform.startDefaultInterfaceMonitor(object : InterfaceUpdateListener {
            override fun updateDefaultInterface(name: String?, index: Int, expensive: Boolean, constrained: Boolean) = Unit

            override fun updateNetworkPath(path: String?) = Unit
        })
        assertEquals(1, host.underlying.size)
        assertNull(host.underlying.single())
        observer.stop()
    }

    @Test
    fun noUnderlyingNetworkLeavesTheChoiceToTheSystem() {
        assertNull(underlyingOf(null))
    }
}
