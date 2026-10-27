package moe.desupon.brain.port

interface Clock {
    /** Wall time, for persisted timestamps. */
    fun nowMs(): Long

    /** Monotonic time including deep sleep, for timers and rate windows. */
    fun elapsedMs(): Long
}
