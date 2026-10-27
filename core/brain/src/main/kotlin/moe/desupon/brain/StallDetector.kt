package moe.desupon.brain

import moe.desupon.model.status.ConnInfo

/** Watches only connections routed through [proxyTag], so direct traffic cannot mask a dead proxy. */
internal class StallDetector(
    private val proxyTag: String,
    private val windowMs: Long,
    private val minUplinkBytes: Long,
) {
    private var previous: Map<String, LongArray> = emptyMap()
    private var stallSinceMs: Long? = null
    private var uplinkDuringStall = 0L

    /** True once per [windowMs] of proxied uplink growing while downlink stays flat. */
    fun onSample(connections: List<ConnInfo>, nowMs: Long): Boolean {
        var up = 0L
        var down = 0L
        val current = HashMap<String, LongArray>()
        for (c in connections) {
            if (c.closed || proxyTag !in c.chain) continue
            val prev = previous[c.id]
            up += (c.uplinkTotal - (prev?.get(0) ?: 0L)).coerceAtLeast(0L)
            down += (c.downlinkTotal - (prev?.get(1) ?: 0L)).coerceAtLeast(0L)
            current[c.id] = longArrayOf(c.uplinkTotal, c.downlinkTotal)
        }
        previous = current
        if (down > 0) {
            stallSinceMs = null
            uplinkDuringStall = 0
            return false
        }
        if (up == 0L) return false
        val since = stallSinceMs ?: nowMs.also { stallSinceMs = it }
        uplinkDuringStall += up
        if (nowMs - since < windowMs || uplinkDuringStall < minUplinkBytes) return false
        stallSinceMs = nowMs
        uplinkDuringStall = 0
        return true
    }
}
