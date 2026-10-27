package moe.desupon.brain.plan

import moe.desupon.model.probe.ProbeStat

/** Ranking classes; [SUSPECT] is a node with recent success that failed once, still ahead of untested ones. */
enum class Standing { VERIFIED, SUSPECT, UNKNOWN, FAILING }

/** Composite node quality shared by the planner, sweeps and the unstable pin; lower is better. */
object NodeScore {
    const val MAD_WEIGHT = 1.3
    const val CONNECT_DIVISOR = 4.0
    const val FAILURE_RATIO_WEIGHT = 150.0
    const val FAIL_STREAK_WEIGHT = 60.0
    const val STALENESS_MAX = 100.0
    const val THROTTLED_PENALTY = 200.0

    /** A success without a timestamp counts as fresh. */
    fun standing(stat: ProbeStat?, nowMs: Long, verifiedTtlMs: Long): Standing {
        if (stat == null) return Standing.UNKNOWN
        val fresh = stat.ok > 0 && stat.lastOkAtMs.let { it == null || nowMs - it <= verifiedTtlMs }
        return when {
            stat.failStreak >= 2 || (stat.failStreak > 0 && stat.ok == 0) -> Standing.FAILING
            !fresh -> Standing.UNKNOWN
            stat.failStreak == 1 -> Standing.SUSPECT
            else -> Standing.VERIFIED
        }
    }

    /** ewmaRtt + 1.3·mad + ewmaConnect/4 + 150·failureRatio + 60·failStreak + staleness (0..100 over the TTL), throttled +200. */
    fun score(stat: ProbeStat, nowMs: Long, verifiedTtlMs: Long): Double {
        val age = stat.lastOkAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L
        val staleness = STALENESS_MAX * (age.toDouble() / verifiedTtlMs).coerceAtMost(1.0)
        return stat.ewmaRttMs +
            MAD_WEIGHT * stat.madMs +
            stat.ewmaConnectMs / CONNECT_DIVISOR +
            FAILURE_RATIO_WEIGHT * stat.failureRatio +
            FAIL_STREAK_WEIGHT * stat.failStreak +
            staleness +
            (if (stat.throttled) THROTTLED_PENALTY else 0.0)
    }

    /** 0 for VERIFIED and SUSPECT (ordered by [score]), 1 for UNKNOWN, 2 for FAILING. */
    fun group(standing: Standing): Int = when (standing) {
        Standing.VERIFIED, Standing.SUSPECT -> 0
        Standing.UNKNOWN -> 1
        Standing.FAILING -> 2
    }

    /** Usable nodes by score, then unknown, then failing by streak and score; ties keep input order. */
    fun <T> comparator(statOf: (T) -> ProbeStat?, nowMs: Long, verifiedTtlMs: Long): Comparator<T> {
        fun group(t: T) = group(standing(statOf(t), nowMs, verifiedTtlMs))
        return compareBy<T> { group(it) }
            .thenBy { if (group(it) == 2) statOf(it)!!.failStreak else 0 }
            .thenBy { t -> statOf(t)?.takeIf { group(t) != 1 }?.let { score(it, nowMs, verifiedTtlMs) } ?: 0.0 }
    }
}
