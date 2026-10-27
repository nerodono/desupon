package moe.desupon.feature.settings.logic

import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRowsTest {
    private val own = "org.example.self"
    private fun app(pkg: String) = InstalledApp(pkg, pkg, system = false)

    private val except = AppSplit(
        mode = SplitMode.ALL_EXCEPT_SELECTED,
        packages = setOf("com.example.selected", "ru.example.selected", "com.example.stale"),
        bypassPrefixes = setOf("ru.", "ru.example.", "su."),
        forceTunnel = setOf("su.example.forced", "com.example.stale", "com.example.ignored"),
    )

    private fun row(split: AppSplit, pkg: String) = AppRows.row(split, app(pkg), own)

    @Test
    fun exceptModeMapsRoutes() {
        assertEquals(AppRowState(app("com.example.plain"), checked = false), row(except, "com.example.plain"))
        assertEquals(AppRowState(app("com.example.selected"), checked = true), row(except, "com.example.selected"))
        assertEquals(
            AppRowState(app("ru.example.bank"), checked = true, note = RowNote.ByPrefix("ru.example.")),
            row(except, "ru.example.bank"),
        )
        assertEquals(AppRowState(app("ru.other"), checked = true, note = RowNote.ByPrefix("ru.")), row(except, "ru.other"))
        assertEquals(
            AppRowState(app("su.example.forced"), checked = false, note = RowNote.ForcedTunnel),
            row(except, "su.example.forced"),
        )
        assertEquals(
            AppRowState(app("com.example.stale"), checked = false, note = RowNote.ForcedTunnel),
            row(except, "com.example.stale"),
        )
        assertEquals(AppRowState(app("com.example.ignored"), checked = false), row(except, "com.example.ignored"))
    }

    @Test
    fun selectedAndPrefixedShowsPrefixNote() {
        assertEquals(RowNote.ByPrefix("ru.example."), row(except, "ru.example.selected").note)
    }

    @Test
    fun prefixRuleOffMeansNoPrefixRows() {
        val off = except.copy(bypassPrefixes = emptySet())
        assertEquals(AppRowState(app("ru.other"), checked = false), row(off, "ru.other"))
        assertEquals(AppRowState(app("ru.example.selected"), checked = true), row(off, "ru.example.selected"))
    }

    @Test
    fun ownPackageNeverChecked() {
        assertEquals(AppRowState(app(own), checked = false), row(except.copy(packages = setOf(own)), own))
    }

    @Test
    fun otherModesUseSelectionOnly() {
        val only = except.copy(mode = SplitMode.ONLY_SELECTED)
        assertEquals(AppRowState(app("ru.other"), checked = false), row(only, "ru.other"))
        assertEquals(AppRowState(app("su.example.forced"), checked = false), row(only, "su.example.forced"))
        assertEquals(AppRowState(app("com.example.selected"), checked = true), row(only, "com.example.selected"))
    }

    @Test
    fun checkedCountsInstalledOnly() {
        val installed = listOf("com.example.plain", "com.example.selected", "ru.other", "su.example.forced", own)
        assertEquals(setOf("com.example.selected", "ru.other"), AppRows.checked(except, installed, own))
        assertEquals(setOf("com.example.selected"), AppRows.checked(except.copy(mode = SplitMode.ONLY_SELECTED), installed, own))
    }

    @Test
    fun prefixBypassedAppsSortAsSelected() {
        val apps = listOf(app("com.example.a"), app("ru.example.z"), app("com.example.selected"))
        val pinned = AppRows.checked(except, apps.map { it.packageName }, own)
        assertEquals(
            listOf("com.example.selected", "ru.example.z", "com.example.a"),
            AppFilter.apply(apps, "", showSystem = false, pinned = pinned).map { it.packageName },
        )
    }
}
