package moe.desupon.vpn.split

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.LibboxControl
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.status.ConnInfo
import moe.desupon.model.status.GroupInfo
import moe.desupon.model.status.TrafficStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InstalledSplitTest {
    private val own = "moe.desupon.app"
    private val installed = setOf("org.example.bank", "org.example.maps")
    private val isInstalled: (String) -> Boolean = { it in installed }
    private val selected = setOf("org.example.bank", "org.example.gone", "org.example.maps", own, " ")

    @Test
    fun `only-selected keeps installed packages and adds own package`() {
        val o = InstalledSplit.overrides(AppSplit(SplitMode.ONLY_SELECTED, selected), own, isInstalled)
        assertEquals(listOf(own, "org.example.bank", "org.example.maps"), o.include)
        assertEquals(emptyList(), o.exclude)
    }

    @Test
    fun `only-selected with nothing installed still tunnels own package`() {
        val o = InstalledSplit.overrides(AppSplit(SplitMode.ONLY_SELECTED, setOf("org.example.gone")), own, isInstalled)
        assertEquals(listOf(own), o.include)
        assertEquals(emptyList(), o.exclude)
    }

    @Test
    fun `all-except drops uninstalled packages and never excludes own package`() {
        val o = InstalledSplit.overrides(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, selected), own, isInstalled)
        assertEquals(emptyList(), o.include)
        assertEquals(listOf("org.example.bank", "org.example.maps"), o.exclude)
    }

    @Test
    fun `all-except with only unknown packages excludes nothing`() {
        val o = InstalledSplit.overrides(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.gone", own)), own, isInstalled)
        assertEquals(emptyList(), o.include)
        assertEquals(emptyList(), o.exclude)
    }

    @Test
    fun `all ignores the package list`() {
        val split = InstalledSplit.filter(AppSplit(SplitMode.ALL, selected), own, isInstalled)
        assertEquals(AppSplit(SplitMode.ALL, emptySet()), split)
        val o = InstalledSplit.overrides(split, own, isInstalled)
        assertEquals(emptyList(), o.include)
        assertEquals(emptyList(), o.exclude)
    }

    @Test
    fun `all-except adds installed prefix matches unless overridden, and lists packages only for a rule`() {
        val device = listOf("ru.example.maps", "su.example.mail", "ru.example.kept", "org.example.bank", "com.example.ru", own)
        var listed = 0
        val list = { listed++; device }
        val split = AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank", "org.example.gone"), setOf("ru.", "su."), setOf("ru.example.kept"))
        val resolved = InstalledSplit.filter(split, own, { it in device }, list)
        assertEquals(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank", "ru.example.maps", "su.example.mail")), resolved)
        assertEquals(1, listed)

        InstalledSplit.filter(split.copy(bypassPrefixes = emptySet()), own, { it in device }, list)
        InstalledSplit.filter(split.copy(mode = SplitMode.ONLY_SELECTED), own, { it in device }, list)
        assertEquals(1, listed)
        val ownPrefix = InstalledSplit.overrides(split.copy(bypassPrefixes = setOf("moe.")), own, { true }, { listOf(own) })
        assertTrue(own !in ownPrefix.exclude)
    }

    @Test
    fun `a fresh install matters only when the split would route it differently`() {
        val split = AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank"), setOf("ru."), setOf("ru.example.kept"))
        assertTrue(InstalledSplit.affects(split, "ru.example.maps", own))
        assertTrue(InstalledSplit.affects(split, "org.example.bank", own))
        assertFalse(InstalledSplit.affects(split, "ru.example.kept", own))
        assertFalse(InstalledSplit.affects(split, "org.example.other", own))
        assertTrue(InstalledSplit.affects(AppSplit(SplitMode.ONLY_SELECTED, setOf("org.example.bank")), "org.example.bank", own))
        assertFalse(InstalledSplit.affects(AppSplit(SplitMode.ALL, setOf("ru.x")), "ru.x", own))
    }

    @Test
    fun `installed apps follow the package manager`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "org.example.bank"
                applicationInfo = ApplicationInfo().apply { packageName = "org.example.bank" }
            },
        )
        val apps = InstalledApps(context)
        assertTrue(apps.isInstalled("org.example.bank"))
        assertTrue(apps.isInstalled(context.packageName))
        assertFalse(apps.isInstalled("org.example.gone"))
    }

    @Test
    fun `core receives only installed packages`() = runTest {
        val core = RecordingLibbox()
        InstalledOnlyLibbox(core, own, isInstalled).reload("{}", AppSplit(SplitMode.ALL_EXCEPT_SELECTED, selected))
        assertEquals(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf("org.example.bank", "org.example.maps")), core.apps)
    }

    private class RecordingLibbox : LibboxControl {
        var apps: AppSplit? = null
        override val groups: Flow<List<GroupInfo>> = emptyFlow()
        override val status: Flow<TrafficStatus> = emptyFlow()
        override val connections: Flow<List<ConnInfo>> = emptyFlow()

        override suspend fun reload(configJson: String, apps: AppSplit) {
            this.apps = apps
        }

        override suspend fun select(group: String, tag: String) {}

        override suspend fun closeConnections() {}

        override suspend fun stop() {}
    }
}
