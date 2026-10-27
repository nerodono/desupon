package moe.desupon.data.fetch

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress

/** The underlying network while the tunnel is up, so a fetch can get past a dead proxy; null otherwise. */
fun interface BypassNetwork {
    fun current(): Network?
}

/** This client with sockets and name lookups pinned to [network], outside the tunnel. */
fun OkHttpClient.boundTo(network: Network): OkHttpClient = newBuilder()
    .socketFactory(network.socketFactory)
    .dns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        },
    )
    .build()
