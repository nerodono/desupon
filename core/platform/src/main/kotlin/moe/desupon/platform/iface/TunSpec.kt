package moe.desupon.platform.iface

import android.os.Build

data class Prefix(val address: String, val length: Int) {
    val isIpv6: Boolean get() = ':' in address
}

/** What the core asks for in `openTun`, copied out of the native options. */
data class TunRequest(
    val mtu: Int,
    val autoRoute: Boolean,
    val dnsDisabled: Boolean,
    val dnsServers: List<String>,
    val inet4Address: List<Prefix>,
    val inet6Address: List<Prefix>,
    val inet4RouteAddress: List<Prefix>,
    val inet6RouteAddress: List<Prefix>,
    val inet4RouteExcludeAddress: List<Prefix>,
    val inet6RouteExcludeAddress: List<Prefix>,
    /** Route addresses with excludes already subtracted, for builders without `excludeRoute`. */
    val inet4RouteRange: List<Prefix>,
    val inet6RouteRange: List<Prefix>,
    val includePackage: List<String>,
    val excludePackage: List<String>,
)

/** What gets applied to `VpnService.Builder`; never an HTTP proxy, never bypassable. */
data class TunSpec(
    val mtu: Int,
    val addresses: List<Prefix>,
    val dnsServers: List<String>,
    val routes: List<Prefix>,
    val excludeRoutes: List<Prefix>,
    val allowedApps: List<String>,
    val disallowedApps: List<String>,
) {
    companion object {
        /** Routes, DNS and per-app lists only apply with auto_route, as the core expects. */
        fun of(request: TunRequest, sdk: Int = Build.VERSION.SDK_INT): TunSpec {
            require(request.mtu > 0) { "tun: mtu must be set explicitly" }
            val addresses = request.inet4Address + request.inet6Address
            if (!request.autoRoute) {
                return TunSpec(request.mtu, addresses, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            }
            val dns = if (request.dnsDisabled) emptyList() else request.dnsServers
            val routes: List<Prefix>
            val excludes: List<Prefix>
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                routes = request.inet4RouteAddress.ifEmpty {
                    if (request.inet4Address.isNotEmpty()) listOf(Prefix("0.0.0.0", 0)) else emptyList()
                } + request.inet6RouteAddress.ifEmpty {
                    if (request.inet6Address.isNotEmpty()) listOf(Prefix("::", 0)) else emptyList()
                }
                excludes = request.inet4RouteExcludeAddress + request.inet6RouteExcludeAddress
            } else {
                routes = request.inet4RouteRange + request.inet6RouteRange
                excludes = emptyList()
            }
            // The builder rejects mixing allowed and disallowed apps; the include list wins.
            val allowed = request.includePackage.distinct()
            val disallowed = if (allowed.isEmpty()) request.excludePackage.distinct() else emptyList()
            return TunSpec(request.mtu, addresses, dns, routes, excludes, allowed, disallowed)
        }
    }
}
