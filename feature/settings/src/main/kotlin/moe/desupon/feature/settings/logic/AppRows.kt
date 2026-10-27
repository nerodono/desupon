package moe.desupon.feature.settings.logic

import moe.desupon.data.split.AppBypass
import moe.desupon.data.split.AppRoute
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode

sealed interface RowNote {
    /** Checked by the prefix rule rather than by selection. */
    data class ByPrefix(val prefix: String) : RowNote

    /** A manual override keeps the app in the tunnel; resettable. */
    data object ForcedTunnel : RowNote
}

data class AppRowState(val app: InstalledApp, val checked: Boolean, val note: RowNote? = null)

object AppRows {
    /** In [SplitMode.ALL_EXCEPT_SELECTED] "checked" means bypassing per [AppBypass]; otherwise membership in [AppSplit.packages]. */
    fun row(split: AppSplit, app: InstalledApp, ownPackage: String): AppRowState {
        if (split.mode != SplitMode.ALL_EXCEPT_SELECTED) return AppRowState(app, app.packageName in split.packages)
        return when (AppBypass.route(split, app.packageName, ownPackage)) {
            AppRoute.TUNNEL -> AppRowState(app, checked = false)
            AppRoute.BYPASS_SELECTED -> AppRowState(app, checked = true)
            AppRoute.BYPASS_PREFIX -> AppRowState(app, checked = true, note = matchingPrefix(app.packageName, split.bypassPrefixes)?.let(RowNote::ByPrefix))
            AppRoute.TUNNEL_FORCED -> AppRowState(app, checked = false, note = RowNote.ForcedTunnel)
        }
    }

    /** Packages of [installed] shown checked: bypassing in [SplitMode.ALL_EXCEPT_SELECTED], selected otherwise. */
    fun checked(split: AppSplit, installed: Collection<String>, ownPackage: String): Set<String> =
        if (split.mode == SplitMode.ALL_EXCEPT_SELECTED) {
            AppBypass.bypassed(split, installed, ownPackage)
        } else {
            installed.filterTo(LinkedHashSet()) { it in split.packages }
        }

    private fun matchingPrefix(packageName: String, prefixes: Collection<String>): String? =
        prefixes.filter { it.isNotEmpty() && packageName.startsWith(it) }.maxByOrNull { it.length }
}
