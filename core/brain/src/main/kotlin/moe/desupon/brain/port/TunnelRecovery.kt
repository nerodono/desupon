package moe.desupon.brain.port

/** Events after which tunnel state may be dead without anything having reported it. */
interface TunnelRecovery {
    /** The device slept [sleptMs] at a stretch, long enough for server-side session state to die meanwhile. */
    fun onWake(sleptMs: Long)

    /** The system stopped blocking the app's network, which any connection may have lost packets to. */
    fun onNetworkUnblocked()
}
