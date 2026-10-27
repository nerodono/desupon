package moe.desupon.model.network

import moe.desupon.model.node.NodeId

enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER }

enum class NetworkMode { OPEN, WHITELIST, OFFLINE, UNKNOWN }

/** Identity of an underlying network: MCC-MNC on cellular, hash of gateway, DNS and domains on Wi-Fi. */
data class NetworkKey(val transport: Transport, val id: String) {
    fun encode(): String = transport.name.lowercase() + SEPARATOR + id

    companion object {
        private const val SEPARATOR = '|'

        fun decode(value: String): NetworkKey? {
            val cut = value.indexOf(SEPARATOR)
            if (cut <= 0) return null
            val transport = Transport.entries.firstOrNull { it.name.equals(value.substring(0, cut), ignoreCase = true) }
                ?: return null
            return NetworkKey(transport, value.substring(cut + 1))
        }
    }
}

/** `Network.getNetworkHandle()` of an underlying network; the platform layer restores it with `Network.fromNetworkHandle`. */
@JvmInline
value class NetworkHandle(val netId: Long)

/** What was learned about a network: its mode and the last node that worked there. */
data class NetworkMemory(
    val key: NetworkKey,
    val mode: NetworkMode,
    val lastGood: NodeId?,
    val updatedAtMs: Long,
)

enum class DnsStrategy { PREFER_IPV4, IPV4_ONLY }

/** [remote] is resolved through `proxy`; direct domains use the platform resolver. */
data class DnsSettings(
    val remote: String = DEFAULT_REMOTE,
    val strategy: DnsStrategy = DnsStrategy.PREFER_IPV4,
) {
    companion object {
        const val DEFAULT_REMOTE = "https://1.1.1.1/dns-query"
    }
}
