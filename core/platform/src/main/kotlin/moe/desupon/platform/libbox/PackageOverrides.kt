package moe.desupon.platform.libbox

import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode

/** Per-app lists handed to the core as override options; at most one of them is non-empty. */
data class PackageOverrides(val include: List<String>, val exclude: List<String>) {
    companion object {
        /** The own package always stays inside the tunnel, whatever [split] says. */
        fun of(split: AppSplit, ownPackage: String): PackageOverrides {
            val selected = split.packages.filter { it.isNotBlank() }.toSortedSet()
            return when (split.mode) {
                SplitMode.ALL -> PackageOverrides(emptyList(), emptyList())
                SplitMode.ONLY_SELECTED -> PackageOverrides((selected + ownPackage).sorted(), emptyList())
                SplitMode.ALL_EXCEPT_SELECTED -> PackageOverrides(emptyList(), (selected - ownPackage).toList())
            }
        }
    }
}
