package moe.desupon.platform.net

import android.net.LinkProperties
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import java.net.Inet6Address
import moe.desupon.model.network.NetworkKeys as ModelNetworkKeys

/** [LinkProperties] adapter over [ModelNetworkKeys]. */
object NetworkKeys {
    fun of(
        transport: Transport,
        mccMnc: String?,
        gateways: List<String>,
        dnsServers: List<String>,
        domains: String?,
    ): NetworkKey = ModelNetworkKeys.of(transport, mccMnc, gateways, dnsServers, domains)

    fun of(transport: Transport, mccMnc: String?, link: LinkProperties): NetworkKey =
        ModelNetworkKeys.of(transport, mccMnc, defaultGateways(link), dnsServers(link), link.domains)

    /** On-link default routes carry a wildcard gateway and are skipped. */
    fun defaultGateways(link: LinkProperties): List<String> = link.routes
        .filter { it.destination.prefixLength == 0 }
        .mapNotNull { it.gateway }
        .filterNot { it.isAnyLocalAddress }
        .mapNotNull { it.hostAddress }

    fun dnsServers(link: LinkProperties): List<String> = link.dnsServers.mapNotNull { it.hostAddress }

    /** A global unicast IPv6 address (not link-local, loopback or ULA) and an IPv6 default route. */
    fun hasIpv6(link: LinkProperties): Boolean {
        val address = link.linkAddresses.any { a ->
            val ip = a.address
            ip is Inet6Address && !ip.isLinkLocalAddress && !ip.isLoopbackAddress && !ip.isAnyLocalAddress && (ip.address[0].toInt() and 0xfe) != 0xfc
        }
        return address && link.routes.any { it.destination.prefixLength == 0 && it.destination.address is Inet6Address }
    }
}
