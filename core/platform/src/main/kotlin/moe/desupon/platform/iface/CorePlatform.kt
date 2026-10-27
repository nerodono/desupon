/*
 * Portions Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package moe.desupon.platform.iface

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import moe.desupon.core.libbox.BridgeOptions
import moe.desupon.core.libbox.BridgeSession
import moe.desupon.core.libbox.ConnectionOwner
import moe.desupon.core.libbox.InterfaceUpdateListener
import moe.desupon.core.libbox.Libbox
import moe.desupon.core.libbox.LocalDNSTransport
import moe.desupon.core.libbox.NeighborUpdateListener
import moe.desupon.core.libbox.NetworkInterfaceIterator
import moe.desupon.core.libbox.Notification
import moe.desupon.core.libbox.PlatformInterface
import moe.desupon.core.libbox.PlatformUser
import moe.desupon.core.libbox.RoutePrefixIterator
import moe.desupon.core.libbox.ShellSession
import moe.desupon.core.libbox.StringIterator
import moe.desupon.core.libbox.TunOptions
import moe.desupon.core.libbox.WIFIState
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.platform.net.UnderlyingNetwork
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import moe.desupon.core.libbox.NetworkInterface as CoreInterface

/**
 * The core's view of Android, limited to what the extended core's platform interface needs:
 * no root, shell, bridge, auto-redirect, notifications or Wi-Fi state.
 */
class CorePlatform(
    context: Context,
    private val host: VpnHost,
    private val observer: NetworkObserver,
) : PlatformInterface {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val packages = app.packageManager
    private val resolver = LocalResolver(observer)

    private val tunLock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var interfaceListener: ((UnderlyingNetwork?) -> Unit)? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!host.protect(fd)) throw IOException("protect socket failed")
    }

    override fun bindInterfaceControl(fd: Int, interfaceName: String?) {
        throw UnsupportedOperationException("bind_interface is not supported")
    }

    override fun useProcFS(): Boolean = false

    override fun openTun(options: TunOptions): Int {
        if (!host.isVpnPrepared()) error("android: missing vpn permission")
        val spec = TunSpec.of(options.toRequest())
        val builder = host.newTunBuilder()
            .setSession(host.sessionLabel)
            .setMtu(spec.mtu)
            // Unmetered by itself, the VPN is metered exactly when its underlying network is.
            .setMetered(false)
            .setUnderlyingNetworks(underlyingOf(observer.current.value))
        spec.applyTo(builder)
        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        // The old interface stays open until its replacement exists, so the system can hand over in place.
        val previous = synchronized(tunLock) {
            val old = tun
            tun = pfd
            old
        }
        runCatching { previous?.close() }
        return pfd.fd
    }

    /** Closes the TUN interface; the VPN network goes away with it. */
    fun closeTun() {
        val pfd = synchronized(tunLock) {
            val old = tun
            tun = null
            old
        }
        runCatching { pfd?.close() }
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val names = packages.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().also {
            it.userId = uid
            it.userName = names.firstOrNull().orEmpty()
            it.setAndroidPackageNames(StringArray(names))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val forward: (UnderlyingNetwork?) -> Unit = { network ->
            host.setUnderlyingNetworks(underlyingOf(network))
            if (network != null && network.interfaceIndex >= 0) {
                listener.updateDefaultInterface(network.interfaceName, network.interfaceIndex, false, false)
            } else {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        synchronized(tunLock) {
            interfaceListener?.let(observer::removeListener)
            interfaceListener = forward
        }
        observer.start()
        observer.addListener(forward)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val forward = synchronized(tunLock) {
            val old = interfaceListener
            interfaceListener = null
            old
        }
        forward?.let(observer::removeListener)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val kernel = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<CoreInterface>()
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val link = connectivity.getLinkProperties(network) ?: continue
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val name = link.interfaceName ?: continue
            val nif = kernel.find { it.name == name } ?: continue
            val item = CoreInterface()
            item.name = name
            item.index = nif.index
            runCatching { item.mtu = nif.mtu }
            item.dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
            item.gateway = StringArray(
                link.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress },
            )
            item.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.addresses = StringArray(nif.interfaceAddresses.map { it.toPrefix() })
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (nif.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (nif.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (nif.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            item.flags = flags
            item.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result += item
        }
        val iterator = result.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): CoreInterface = iterator.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}

    override fun localDNSTransport(): LocalDNSTransport = resolver

    override fun sendNotification(notification: Notification?) {}

    override fun cancelNotification(identifier: String?, typeID: Int) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = unsupported("shell")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = unsupported("shell")

    override fun lookupUser(username: String?): PlatformUser = unsupported("user lookup")

    override fun lookupSFTPServer(): String = unsupported("sftp")

    override fun readSystemSSHHostKey(): String = unsupported("ssh")

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("bridge")

    private fun unsupported(what: String): Nothing = throw UnsupportedOperationException("$what is not supported")
}

internal fun underlyingOf(network: UnderlyingNetwork?): Array<Network>? = network?.let { arrayOf(it.network) }

/** Never `allowFamily`: a family the spec leaves out must stay blocked for tunnelled apps. */
internal fun TunSpec.applyTo(builder: VpnService.Builder) {
    for (address in addresses) builder.addAddress(address.address, address.length)
    for (dns in dnsServers) builder.addDnsServer(dns)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        for (route in routes) builder.addRoute(route.toIpPrefix())
        for (route in excludeRoutes) builder.excludeRoute(route.toIpPrefix())
    } else {
        for (route in routes) builder.addRoute(route.address, route.length)
    }
    for (pkg in allowedApps) {
        try {
            builder.addAllowedApplication(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
        }
    }
    for (pkg in disallowedApps) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
        }
    }
}

internal class StringArray(values: List<String>) : StringIterator {
    private val size = values.size
    private val iterator = values.iterator()

    override fun len(): Int = size

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): String = iterator.next()
}

private fun TunOptions.toRequest() = TunRequest(
    mtu = mtu,
    autoRoute = autoRoute,
    dnsDisabled = dnsMode?.value == Libbox.DNSModeDisabled,
    dnsServers = runCatching { dnsServerAddress.toList() }.getOrDefault(emptyList()),
    inet4Address = inet4Address.toPrefixes(),
    inet6Address = inet6Address.toPrefixes(),
    inet4RouteAddress = inet4RouteAddress.toPrefixes(),
    inet6RouteAddress = inet6RouteAddress.toPrefixes(),
    inet4RouteExcludeAddress = inet4RouteExcludeAddress.toPrefixes(),
    inet6RouteExcludeAddress = inet6RouteExcludeAddress.toPrefixes(),
    inet4RouteRange = inet4RouteRange.toPrefixes(),
    inet6RouteRange = inet6RouteRange.toPrefixes(),
    includePackage = includePackage.toList(),
    excludePackage = excludePackage.toList(),
)

internal fun StringIterator?.toList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    while (hasNext()) out += next()
    return out
}

private fun RoutePrefixIterator?.toPrefixes(): List<Prefix> {
    if (this == null) return emptyList()
    val out = mutableListOf<Prefix>()
    while (hasNext()) {
        val p = next()
        out += Prefix(p.address(), p.prefix())
    }
    return out
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Prefix.toIpPrefix(): IpPrefix = IpPrefix(InetAddress.getByName(address), length)

private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
    "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
} else {
    "${address.hostAddress}/$networkPrefixLength"
}
