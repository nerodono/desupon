package moe.desupon.feature.settings.logic

/** [system] means a system app without a launcher entry. */
data class InstalledApp(val packageName: String, val label: String, val system: Boolean)

object AppFilter {
    private val byLabel = compareBy<InstalledApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName }

    /**
     * Apps matching [query] by label or package, case-insensitive. System apps show only with [showSystem]
     * or when [pinned]; [pinned] apps sort first, so pass a selection snapshot to keep rows from jumping on toggle.
     */
    fun apply(apps: List<InstalledApp>, query: String, showSystem: Boolean, pinned: Set<String>): List<InstalledApp> {
        val q = query.trim()
        return apps.asSequence()
            .filter { !it.system || showSystem || it.packageName in pinned }
            .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<InstalledApp> { it.packageName !in pinned }.then(byLabel))
            .toList()
    }
}
