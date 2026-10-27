package moe.desupon.data.split

import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode

/** Route of one package; outside [SplitMode.ALL_EXCEPT_SELECTED] everything but the split itself is [TUNNEL]. */
enum class AppRoute {
    TUNNEL,

    /** In the selected package set. */
    BYPASS_SELECTED,

    /** Matched by [AppSplit.bypassPrefixes]. */
    BYPASS_PREFIX,

    /** Selected or prefix-matched, but kept in by [AppSplit.forceTunnel]. */
    TUNNEL_FORCED,
    ;

    val bypasses: Boolean get() = this == BYPASS_SELECTED || this == BYPASS_PREFIX
}

/** Resolves [SplitMode.ALL_EXCEPT_SELECTED]: an explicit override beats the prefix rule, which beats the selected set; the own package never bypasses. */
object AppBypass {
    fun matchesPrefix(packageName: String, prefixes: Collection<String>): Boolean =
        prefixes.any { it.isNotEmpty() && packageName.startsWith(it) }

    fun route(split: AppSplit, packageName: String, ownPackage: String): AppRoute {
        if (split.mode != SplitMode.ALL_EXCEPT_SELECTED || packageName == ownPackage || packageName.isBlank()) return AppRoute.TUNNEL
        val prefixed = matchesPrefix(packageName, split.bypassPrefixes)
        return when {
            !prefixed && packageName !in split.packages -> AppRoute.TUNNEL
            packageName in split.forceTunnel -> AppRoute.TUNNEL_FORCED
            prefixed -> AppRoute.BYPASS_PREFIX
            else -> AppRoute.BYPASS_SELECTED
        }
    }

    /** Packages of [installed] that bypass the tunnel under [split]; empty outside [SplitMode.ALL_EXCEPT_SELECTED]. */
    fun bypassed(split: AppSplit, installed: Collection<String>, ownPackage: String): Set<String> =
        installed.filterTo(LinkedHashSet()) { route(split, it, ownPackage).bypasses }
}
