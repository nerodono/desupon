package moe.desupon.model.node

import moe.desupon.model.Secret
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** SHA-256 hex of a node's connection identity; the display name is not part of it. */
@JvmInline
value class NodeId(val hash: String) {
    /** Outbound/endpoint tag of this node in generated configs. */
    val tag: String get() = TAG_PREFIX + hash.take(TAG_HASH_CHARS)

    companion object {
        const val TAG_PREFIX = "n-"
        private const val TAG_HASH_CHARS = 16

        fun of(key: NodeKey): NodeId {
            val md = MessageDigest.getInstance("SHA-256")
            for (part in key.canonicalParts()) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                md.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                md.update(bytes)
            }
            return NodeId(md.digest().toHex())
        }

        private fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}

/** Fields that make two subscription entries the same server; absent and empty values are equivalent. */
data class NodeKey(
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val credential: Secret? = null,
    val transport: String? = null,
    val path: String? = null,
    val sni: String? = null,
    val obfs: String? = null,
    /** Identity of what the node dials through: chain hop ids and dial-level fragmentation; empty keeps unchained ids unchanged. */
    val via: List<String> = emptyList(),
) {
    val id: NodeId get() = NodeId.of(this)

    // Length-prefixed by NodeId.of, so adjacent fields cannot run into each other.
    internal fun canonicalParts(): List<String> = listOf(
        protocol.name,
        host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT),
        port.toString(),
        credential?.raw().orEmpty(),
        transport?.trim()?.lowercase(Locale.ROOT).orEmpty(),
        path.orEmpty(),
        sni?.trim()?.lowercase(Locale.ROOT).orEmpty(),
        obfs.orEmpty(),
    ) + (if (via.isEmpty()) emptyList() else listOf(VIA) + via)

    private companion object {
        const val VIA = "via"
    }
}
