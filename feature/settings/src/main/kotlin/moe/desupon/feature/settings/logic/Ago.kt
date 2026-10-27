package moe.desupon.feature.settings.logic

/** Coarse age of a timestamp, for "N ago" texts. */
sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val n: Int) : Ago

    data class Hours(val n: Int) : Ago

    data class Days(val n: Int) : Ago

    companion object {
        /** A clock running behind reads as just now. */
        fun of(atMs: Long, nowMs: Long): Ago {
            val minutes = ((nowMs - atMs) / 60_000L).coerceAtLeast(0)
            return when {
                minutes < 1 -> JustNow
                minutes < 60 -> Minutes(minutes.toInt())
                minutes < 24 * 60 -> Hours((minutes / 60).toInt())
                else -> Days((minutes / (24 * 60)).toInt())
            }
        }
    }
}
