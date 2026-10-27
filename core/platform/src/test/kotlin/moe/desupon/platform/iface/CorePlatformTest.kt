package moe.desupon.platform.iface

import android.content.Context
import android.net.IpPrefix
import android.net.Network
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import moe.desupon.core.libbox.InterfaceUpdateListener
import moe.desupon.platform.net.NetworkObserver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
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

    private class RecordingVpn : VpnService() {
        val calls = mutableListOf<String>()

        inner class Recorder : Builder() {
            override fun addAddress(address: String, prefixLength: Int) = record("address $address/$prefixLength")

            override fun addAddress(address: InetAddress, prefixLength: Int) = record("address ${address.hostAddress}/$prefixLength")

            override fun addRoute(address: String, prefixLength: Int) = record("route $address/$prefixLength")

            override fun addRoute(address: InetAddress, prefixLength: Int) = record("route ${address.hostAddress}/$prefixLength")

            override fun addRoute(prefix: IpPrefix) = record("route $prefix")

            override fun excludeRoute(prefix: IpPrefix) = record("exclude $prefix")

            override fun addDnsServer(address: String) = record("dns $address")

            override fun addDnsServer(address: InetAddress) = record("dns ${address.hostAddress}")

            override fun allowFamily(family: Int) = record("allowFamily $family")

            override fun addAllowedApplication(packageName: String) = record("allow $packageName")

            override fun addDisallowedApplication(packageName: String) = record("disallow $packageName")

            private fun record(call: String): Builder = also { calls += call }
        }
    }

    /** What the core asks for under IPv4 only: an IPv4 address, its DNS next to it and IPv4 LAN excludes. */
    @Test
    fun ipv4OnlyTunHandsTheBuilderNoIpv6AndNeverAllowsAFamily() {
        val request = TunRequest(
            mtu = 1500,
            autoRoute = true,
            dnsDisabled = false,
            dnsServers = listOf("198.19.250.2"),
            inet4Address = listOf(Prefix("198.19.250.1", 30)),
            inet6Address = emptyList(),
            inet4RouteAddress = emptyList(),
            inet6RouteAddress = emptyList(),
            inet4RouteExcludeAddress = listOf(Prefix("10.0.0.0", 8), Prefix("192.168.0.0", 16)),
            inet6RouteExcludeAddress = emptyList(),
            inet4RouteRange = emptyList(),
            inet6RouteRange = emptyList(),
            includePackage = emptyList(),
            excludePackage = listOf("org.example.bank"),
        )
        val vpn = RecordingVpn()
        TunSpec.of(request).applyTo(vpn.Recorder())
        assertEquals(
            listOf(
                "address 198.19.250.1/30",
                "dns 198.19.250.2",
                "route 0.0.0.0/0",
                "exclude 10.0.0.0/8",
                "exclude 192.168.0.0/16",
                "disallow org.example.bank",
            ),
            vpn.calls,
        )
    }
}
