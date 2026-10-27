package moe.desupon.platform.net

import android.net.Network
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import moe.desupon.brain.port.HostProbe
import moe.desupon.brain.port.SocketProtector
import moe.desupon.model.network.NetworkHandle
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Connects on a socket pinned to the underlying network, never through the tunnel; on 443 a TLS handshake with SNI must complete. */
class SocketHostProbe(
    /** Fallback when binding is refused, e.g. `VpnService.protect` of the running service. */
    private val protector: () -> SocketProtector? = { null },
    io: CoroutineDispatcher = Dispatchers.IO,
) : HostProbe {
    // Blocking socket calls ignore cancellation, so they run detached and the caller stops waiting at the deadline.
    private val blocking = CoroutineScope(SupervisorJob() + io)

    override suspend fun reachable(host: String, port: Int, network: NetworkHandle, timeoutMs: Int): Boolean {
        val target = Network.fromNetworkHandle(network.netId)
        val socket = Socket()
        val attempt = blocking.async { runCatching { connect(target, socket, host, port, timeoutMs) }.isSuccess }
        return try {
            withTimeoutOrNull(timeoutMs.toLong()) { attempt.await() } ?: false
        } finally {
            // Unblocks a connect or handshake still pending at the deadline.
            runCatching { socket.close() }
            attempt.cancel()
        }
    }

    private fun connect(network: Network, raw: Socket, host: String, port: Int, timeoutMs: Int) {
        val address = network.getAllByName(host).let { all -> all.firstOrNull { it is Inet4Address } ?: all.first() }
        raw.use {
            raw.soTimeout = timeoutMs
            pin(network, raw)
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            if (port != TLS_PORT) return
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            tls.use {
                it.sslParameters = it.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                it.startHandshake()
            }
        }
    }

    private fun pin(network: Network, socket: Socket) {
        try {
            network.bindSocket(socket)
        } catch (e: Exception) {
            val p = protector() ?: throw e
            ParcelFileDescriptor.fromSocket(socket).use { pfd -> if (!p.protect(pfd.fd)) throw e }
        }
    }

    private companion object {
        const val TLS_PORT = 443
    }
}
