/*
 * Portions Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>
 * Portions Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>
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

package moe.desupon.platform.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/** The non-VPN network the system would use by default. */
data class UnderlyingNetwork(
    val network: Network,
    val handle: NetworkHandle,
    val key: NetworkKey,
    val transport: Transport,
    val interfaceName: String,
    /** -1 when the kernel interface is not visible yet. */
    val interfaceIndex: Int,
    val dnsServers: List<String>,
    val metered: Boolean,
    /** A global IPv6 address and an IPv6 default route; without them AAAA answers are useless. */
    val ipv6: Boolean = true,
)

/** Follows the best non-VPN network; registering it while the VPN is up never reports the tunnel itself. */
class NetworkObserver(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)

    private val _current = MutableStateFlow<UnderlyingNetwork?>(null)
    val current: StateFlow<UnderlyingNetwork?> = _current.asStateFlow()

    private val _unblocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The system lifted a block on this app's traffic over the current network, e.g. after Doze or app standby. */
    val unblocked: SharedFlow<Unit> = _unblocked.asSharedFlow()

    /** Touched only by [callback]. */
    private var blockedOn: Network? = null

    private val listeners = CopyOnWriteArrayList<(UnderlyingNetwork?) -> Unit>()
    private val lock = Any()
    private var registered = false
    private var started = false
    private var holders = 0

    private val handler by lazy { Handler(HandlerThread("net-observer").apply { start() }.looper) }

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network, null, null)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh(network, caps, null)

        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = refresh(network, null, link)

        override fun onLost(network: Network) {
            if (_current.value?.network == network) publish(null)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (blocked) {
                blockedOn = network
            } else if (blockedOn == network) {
                blockedOn = null
                _unblocked.tryEmit(Unit)
            }
        }
    }

    /** Held by the core runtime while the service runs. */
    fun start() = synchronized(lock) {
        started = true
        sync()
    }

    fun stop() = synchronized(lock) {
        started = false
        sync()
    }

    /** The network while collected, the service running or not; null until known or without one. */
    fun watch(): Flow<UnderlyingNetwork?> = flow {
        acquire()
        try {
            emitAll(current)
        } finally {
            release()
        }
    }

    /** The current network, waiting up to [timeoutMs] for one when nothing is known yet. */
    suspend fun awaitCurrent(timeoutMs: Long): UnderlyingNetwork? {
        acquire()
        try {
            return withTimeoutOrNull(timeoutMs) { current.filterNotNull().first() }
        } finally {
            release()
        }
    }

    private fun acquire() = synchronized(lock) {
        holders++
        sync()
    }

    private fun release() = synchronized(lock) {
        holders = (holders - 1).coerceAtLeast(0)
        sync()
    }

    private fun sync() {
        val wanted = started || holders > 0
        if (wanted && !registered) {
            registered = true
            // Below API 31 only a REQUEST (not a LISTEN) yields the single default network.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            } else {
                connectivity.requestNetwork(request, callback, handler)
            }
        } else if (!wanted && registered) {
            registered = false
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            _current.value = null
        }
    }

    /** [listener] gets the current value at once, then every change, on the observer thread. */
    fun addListener(listener: (UnderlyingNetwork?) -> Unit) {
        listeners += listener
        listener(_current.value)
    }

    fun removeListener(listener: (UnderlyingNetwork?) -> Unit) {
        listeners -= listener
    }

    private fun refresh(network: Network, knownCaps: NetworkCapabilities?, knownLink: LinkProperties?) {
        val caps = knownCaps ?: connectivity.getNetworkCapabilities(network) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        val snapshot = snapshot(network, caps, knownLink) ?: return
        if (snapshot != _current.value) publish(snapshot)
    }

    private fun snapshot(network: Network, caps: NetworkCapabilities, knownLink: LinkProperties?): UnderlyingNetwork? {
        // Link properties and the kernel interface may trail onAvailable by a few hundred milliseconds.
        repeat(LINK_RETRIES) {
            val link = knownLink ?: connectivity.getLinkProperties(network)
            val name = link?.interfaceName
            if (link != null && name != null) {
                val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull()
                if (index != null) return build(network, caps, link, name, index)
            }
            Thread.sleep(LINK_RETRY_DELAY_MS)
        }
        val link = knownLink ?: connectivity.getLinkProperties(network) ?: return null
        return build(network, caps, link, link.interfaceName ?: return null, -1)
    }

    private fun build(network: Network, caps: NetworkCapabilities, link: LinkProperties, name: String, index: Int): UnderlyingNetwork {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        val dns = NetworkKeys.dnsServers(link)
        val mccMnc = if (transport == Transport.CELLULAR) runCatching { telephony?.networkOperator }.getOrNull() else null
        return UnderlyingNetwork(
            network = network,
            handle = NetworkHandle(network.networkHandle),
            key = NetworkKeys.of(transport, mccMnc, link),
            transport = transport,
            interfaceName = name,
            interfaceIndex = index,
            dnsServers = dns,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            ipv6 = NetworkKeys.hasIpv6(link),
        )
    }

    private fun publish(value: UnderlyingNetwork?) {
        _current.value = value
        for (listener in listeners) listener(value)
    }

    private companion object {
        const val LINK_RETRIES = 10
        const val LINK_RETRY_DELAY_MS = 100L
    }
}
