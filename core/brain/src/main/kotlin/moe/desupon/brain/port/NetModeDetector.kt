package moe.desupon.brain.port

import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode

fun interface NetModeDetector {
    /** Classifies [network] with sockets bound to it, never through the tunnel. */
    suspend fun detect(key: NetworkKey, network: NetworkHandle): NetworkMode
}
