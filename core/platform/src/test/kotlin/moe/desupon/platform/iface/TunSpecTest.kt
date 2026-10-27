package moe.desupon.platform.iface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TunSpecTest {
    private val v4 = Prefix("10.13.0.1", 30)
    private val v6 = Prefix("fdfe:dcba:9876::1", 126)

    private fun request(
        autoRoute: Boolean = true,
        dnsDisabled: Boolean = false,
        routes4: List<Prefix> = emptyList(),
        exclude4: List<Prefix> = emptyList(),
        range4: List<Prefix> = emptyList(),
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        mtu: Int = 1400,
    ) = TunRequest(
        mtu = mtu,
        autoRoute = autoRoute,
        dnsDisabled = dnsDisabled,
        dnsServers = listOf("10.13.0.2"),
        inet4Address = listOf(v4),
        inet6Address = listOf(v6),
        inet4RouteAddress = routes4,
        inet6RouteAddress = emptyList(),
        inet4RouteExcludeAddress = exclude4,
        inet6RouteExcludeAddress = emptyList(),
        inet4RouteRange = range4,
        inet6RouteRange = emptyList(),
        includePackage = include,
        excludePackage = exclude,
    )

    @Test
    fun withoutAutoRouteOnlyAddressesAndMtu() {
        val spec = TunSpec.of(request(autoRoute = false, include = listOf("a")), sdk = 34)
        assertEquals(1400, spec.mtu)
        assertEquals(listOf(v4, v6), spec.addresses)
        assertEquals(emptyList(), spec.routes)
        assertEquals(emptyList(), spec.dnsServers)
        assertEquals(emptyList(), spec.allowedApps)
    }

    @Test
    fun defaultRoutesOnTiramisu() {
        val spec = TunSpec.of(request(exclude4 = listOf(Prefix("192.168.0.0", 16))), sdk = 33)
        assertEquals(listOf(Prefix("0.0.0.0", 0), Prefix("::", 0)), spec.routes)
        assertEquals(listOf(Prefix("192.168.0.0", 16)), spec.excludeRoutes)
        assertEquals(listOf("10.13.0.2"), spec.dnsServers)
    }

    @Test
    fun explicitRoutesWinOverDefault() {
        val spec = TunSpec.of(request(routes4 = listOf(Prefix("198.51.100.0", 24))), sdk = 34)
        assertEquals(listOf(Prefix("198.51.100.0", 24), Prefix("::", 0)), spec.routes)
    }

    @Test
    fun rangesBelowTiramisu() {
        val ranges = listOf(Prefix("0.0.0.0", 1), Prefix("128.0.0.0", 1))
        val spec = TunSpec.of(request(range4 = ranges, exclude4 = listOf(Prefix("192.168.0.0", 16))), sdk = 30)
        assertEquals(ranges, spec.routes)
        assertEquals(emptyList(), spec.excludeRoutes)
    }

    @Test
    fun withoutAnIpv6AddressNothingOfIpv6ReachesTheBuilder() {
        val request = request(exclude4 = listOf(Prefix("192.168.0.0", 16)), range4 = listOf(Prefix("0.0.0.0", 0))).copy(
            inet6Address = emptyList(),
            dnsServers = listOf("10.13.0.2", "fdfe:dcba:9876::2"),
            inet6RouteAddress = listOf(Prefix("2001:db8::", 32)),
            inet6RouteExcludeAddress = listOf(Prefix("fc00::", 7)),
            inet6RouteRange = listOf(Prefix("::", 0)),
        )
        for (sdk in listOf(30, 34)) {
            val spec = TunSpec.of(request, sdk)
            assertEquals(listOf(v4), spec.addresses)
            assertEquals(listOf("10.13.0.2"), spec.dnsServers)
            assertEquals(listOf(Prefix("0.0.0.0", 0)), spec.routes)
            assertTrue((spec.addresses + spec.routes + spec.excludeRoutes).none { it.isIpv6 }, "sdk $sdk")
        }
    }

    @Test
    fun dnsDisabledAddsNoServer() {
        assertEquals(emptyList(), TunSpec.of(request(dnsDisabled = true), sdk = 34).dnsServers)
    }

    @Test
    fun includeWinsOverExclude() {
        val spec = TunSpec.of(request(include = listOf("a", "a", "b"), exclude = listOf("c")), sdk = 34)
        assertEquals(listOf("a", "b"), spec.allowedApps)
        assertEquals(emptyList(), spec.disallowedApps)
        assertEquals(listOf("c"), TunSpec.of(request(exclude = listOf("c")), sdk = 34).disallowedApps)
    }

    @Test
    fun mtuMustBeExplicit() {
        assertFailsWith<IllegalArgumentException> { TunSpec.of(request(mtu = 0), sdk = 34) }
    }
}
