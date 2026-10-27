package moe.desupon.platform.net

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.RouteInfo
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import kotlin.test.assertEquals
import moe.desupon.model.network.NetworkKeys as ModelNetworkKeys

@RunWith(RobolectricTestRunner::class)
class NetworkKeysTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    private fun route(dest: String, prefix: Int, gateway: String?) = ReflectionHelpers.callConstructor(
        RouteInfo::class.java,
        ClassParameter.from(IpPrefix::class.java, IpPrefix(ip(dest), prefix)),
        ClassParameter.from(InetAddress::class.java, gateway?.let(::ip)),
        ClassParameter.from(String::class.java, "wlan0"),
    )

    private fun link(dns: List<String>, domains: String?) = LinkProperties().apply {
        interfaceName = "wlan0"
        addRoute(route("0.0.0.0", 0, "192.168.1.1"))
        addRoute(route("::", 0, "fe80::1"))
        addRoute(route("192.168.1.0", 24, null))
        addRoute(route("::", 0, "::"))
        setDnsServers(dns.map(::ip))
        this.domains = domains
    }

    @Test
    fun linkPropertiesPathEqualsThePureModelPath() {
        val lp = link(listOf("192.168.1.1", "fd00::53"), "lan home")
        val v6gw = ip("fe80::1").hostAddress!!
        val v6dns = ip("fd00::53").hostAddress!!
        assertEquals(listOf("192.168.1.1", v6gw).sorted(), NetworkKeys.defaultGateways(lp).sorted())
        val expected = ModelNetworkKeys.hashed(Transport.WIFI, listOf(v6gw, "192.168.1.1"), listOf(v6dns, "192.168.1.1"), "home lan")
        assertEquals(expected, NetworkKeys.of(Transport.WIFI, null, lp))
        assertEquals(expected, NetworkKeys.of(Transport.WIFI, null, link(listOf("fd00::53", "192.168.1.1"), "home lan")))
    }

    @Test
    fun ipv6NeedsAGlobalAddressAndAV6DefaultRoute() {
        fun lp(addresses: List<String>, v6Route: Boolean) = LinkProperties().apply {
            interfaceName = "wlan0"
            addRoute(route("0.0.0.0", 0, "192.168.1.1"))
            if (v6Route) addRoute(route("::", 0, "fe80::1"))
            setLinkAddresses(addresses.map { ReflectionHelpers.callConstructor(
                    android.net.LinkAddress::class.java,
                    ClassParameter.from(InetAddress::class.java, ip(it)),
                    ClassParameter.from(Int::class.javaPrimitiveType, if (":" in it) 64 else 24),
                ) })
        }
        assertEquals(true, NetworkKeys.hasIpv6(lp(listOf("192.168.1.5", "fe80::5", "2001:db8::5"), v6Route = true)))
        assertEquals(false, NetworkKeys.hasIpv6(lp(listOf("192.168.1.5", "2001:db8::5"), v6Route = false)))
        assertEquals(false, NetworkKeys.hasIpv6(lp(listOf("192.168.1.5", "fe80::5", "fd00::5"), v6Route = true)))
        assertEquals(false, NetworkKeys.hasIpv6(lp(listOf("192.168.1.5"), v6Route = false)))
    }

    @Test
    fun listPathEqualsThePureModelPath() {
        val gw = listOf("192.168.1.1")
        val dns = listOf("192.168.1.1", "fe80::1")
        assertEquals(ModelNetworkKeys.of(Transport.WIFI, null, gw, dns, "lan"), NetworkKeys.of(Transport.WIFI, null, gw, dns, "lan"))
        assertEquals(NetworkKey(Transport.CELLULAR, "25001"), NetworkKeys.of(Transport.CELLULAR, "25001", gw, emptyList(), null))
        assertEquals(16, NetworkKeys.of(Transport.CELLULAR, "", gw, dns, null).id.length)
    }

    @Test
    fun cellularLinkUsesOperator() {
        assertEquals(NetworkKey(Transport.CELLULAR, "25001"), NetworkKeys.of(Transport.CELLULAR, "25001", link(listOf("10.0.0.2"), null)))
    }
}
