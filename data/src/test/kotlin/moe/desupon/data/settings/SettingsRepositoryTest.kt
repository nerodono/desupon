package moe.desupon.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.desupon.data.split.RuDefaults
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, SettingsRepository.FILE_NAME)

    private fun repo() = SettingsRepository(SettingsRepository.createDataStore(file))

    @Test
    fun defaultsAreRuSplitAndNeutralUi() = runTest {
        val r = repo()
        val s = r.settings.first()
        assertEquals(RuDefaults.appSplit, s.apps)
        assertTrue(s.lanBypass)
        assertEquals(DnsSettings(), s.dns)
        assertEquals(UiSettings(), r.ui.first())
        assertFalse(r.showSystemApps.first())
        assertNull(r.subscriptionUserAgent.first())
        assertFalse(r.notificationPromptShown.first())
        assertFalse(r.batteryPromptShown.first())
    }

    @Test
    fun settersAreReflected() = runTest {
        val r = repo()
        r.setTheme(ThemePreference.DARK)
        r.setAmoled(true)
        r.setDesuMode(true)
        r.setDynamicColor(true)
        r.setLanBypass(false)
        r.setIpv4Only(true)
        r.setDnsRemote(" https://dns.example/dns-query ")
        r.setShowSystemApps(true)
        r.setSubscriptionUserAgent("UA/1")

        assertEquals(UiSettings(ThemePreference.DARK, amoled = true, desuMode = true, dynamicColor = true), r.ui.first())
        val s = r.settings.first()
        assertFalse(s.lanBypass)
        assertEquals(DnsSettings("https://dns.example/dns-query", DnsStrategy.IPV4_ONLY), s.dns)
        assertTrue(r.showSystemApps.first())
        assertEquals("UA/1", r.subscriptionUserAgent.first())

        r.setDnsRemote("")
        r.setSubscriptionUserAgent(null)
        assertEquals(DnsSettings.DEFAULT_REMOTE, r.settings.first().dns.remote)
        assertNull(r.subscriptionUserAgent.first())
    }

    @Test
    fun eachSplitModeKeepsItsOwnPackages() = runTest {
        val r = repo()
        r.setPackageSelected(SplitMode.ALL_EXCEPT_SELECTED, "ru.sberbankmobile", selected = false)
        r.setPackageSelected(SplitMode.ONLY_SELECTED, "org.example.app", selected = true)

        assertEquals(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, RuDefaults.bypassApps - "ru.sberbankmobile", RuDefaults.bypassPrefixes), r.settings.first().apps)
        r.setSplitMode(SplitMode.ONLY_SELECTED)
        assertEquals(AppSplit(SplitMode.ONLY_SELECTED, setOf("org.example.app")), r.settings.first().apps)
        r.setSplitMode(SplitMode.ALL)
        assertEquals(AppSplit(SplitMode.ALL, emptySet()), r.settings.first().apps)
        r.setSplitMode(SplitMode.ALL_EXCEPT_SELECTED)
        assertEquals(RuDefaults.bypassApps - "ru.sberbankmobile", r.settings.first().apps.packages)

        r.setSplitPackages(SplitMode.ALL_EXCEPT_SELECTED, emptySet())
        assertTrue(r.settings.first().apps.packages.isEmpty())
        r.setSplitPackages(SplitMode.ALL, setOf("ignored"))
        r.setSplitMode(SplitMode.ONLY_SELECTED)
        assertEquals(setOf("org.example.app"), r.settings.first().apps.packages)

        r.resetAppSplitToDefaults()
        assertEquals(RuDefaults.appSplit, r.settings.first().apps)
        r.setSplitMode(SplitMode.ONLY_SELECTED)
        assertTrue(r.settings.first().apps.packages.isEmpty())
    }

    @Test
    fun sharedPackageSetMigratesToTheExceptMode() = runTest {
        val legacy = PreferenceDataStoreFactory.create(produceFile = { file })
        legacy.edit {
            it[SettingsRepository.LEGACY_PACKAGES] = setOf("org.example.bank")
            it[stringPreferencesKey("split_mode")] = SplitMode.ONLY_SELECTED.name
        }
        val migrated = PreferenceDataStoreFactory.create(migrations = listOf(SettingsRepository.SharedPackagesMigration), produceFile = {
            File(tmp.root, "copy.preferences_pb").also { file.copyTo(it) }
        })
        val r = SettingsRepository(migrated)

        assertEquals(AppSplit(SplitMode.ONLY_SELECTED, emptySet()), r.settings.first().apps)
        r.setSplitMode(SplitMode.ALL_EXCEPT_SELECTED)
        assertEquals(setOf("org.example.bank"), r.settings.first().apps.packages)
        assertFalse(SettingsRepository.LEGACY_PACKAGES in migrated.data.first())
    }

    @Test
    fun resetAllRestoresDefaultsButKeepsThePromptFlag() = runTest {
        val r = repo()
        r.setTheme(ThemePreference.LIGHT)
        r.setAmoled(true)
        r.setDesuMode(true)
        r.setDynamicColor(true)
        r.setLanBypass(true)
        r.setIpv4Only(true)
        r.setDnsRemote("tls://dns.example")
        r.setShowSystemApps(true)
        r.setSubscriptionUserAgent("UA/2")
        r.setSimpleMode(true)
        r.setSplitMode(SplitMode.ONLY_SELECTED)
        r.setPackageSelected(SplitMode.ONLY_SELECTED, "org.example.app", selected = true)
        r.setNotificationPromptShown()
        r.setBatteryPromptShown()

        r.resetAll()

        assertEquals(UiSettings(), r.ui.first())
        assertEquals(ConnectionSettings(apps = RuDefaults.appSplit), r.settings.first())
        assertFalse(r.showSystemApps.first())
        assertNull(r.subscriptionUserAgent.first())
        assertTrue(r.notificationPromptShown.first())
        assertTrue(r.batteryPromptShown.first())
    }

    @Test
    fun prefixRuleDefaultsOnAndTravelsWithTheExceptModeOnly() = runTest {
        val r = repo()
        assertEquals(PrefixRule(true, setOf("ru.", "su.")), r.bypassPrefixRule.first())
        assertEquals(setOf("ru.", "su."), r.settings.first().apps.bypassPrefixes)

        r.setBypassPrefixes(listOf(" ru. ", "", "by."))
        r.setPrefixBypassEnabled(false)
        assertEquals(PrefixRule(false, setOf("ru.", "by.")), r.bypassPrefixRule.first())
        assertTrue(r.settings.first().apps.bypassPrefixes.isEmpty(), "off carries no prefixes")
        r.setPrefixBypassEnabled(true)
        assertEquals(setOf("ru.", "by."), r.settings.first().apps.bypassPrefixes)

        r.setForceTunnel("ru.example.bank", forced = true)
        assertEquals(setOf("ru.example.bank"), r.settings.first().apps.forceTunnel)
        r.setSplitMode(SplitMode.ONLY_SELECTED)
        assertEquals(AppSplit(SplitMode.ONLY_SELECTED, emptySet()), r.settings.first().apps)

        r.resetAppSplitToDefaults()
        assertEquals(RuDefaults.appSplit, r.settings.first().apps)
        assertEquals(PrefixRule(true, RuDefaults.bypassPrefixes), r.bypassPrefixRule.first())
        assertTrue(r.forceTunnel.first().isEmpty())
    }

    @Test
    fun pickerCheckboxOverridesThePrefixRuleOnlyWhenItMatches() = runTest {
        val r = repo()
        r.setSplitPackages(SplitMode.ALL_EXCEPT_SELECTED, setOf("ru.example.both"))

        r.setAppBypassed("ru.example.maps", bypassed = false)
        r.setAppBypassed("ru.example.both", bypassed = false)
        r.setAppBypassed("org.example.bank", bypassed = true)
        var apps = r.settings.first().apps
        assertEquals(setOf("ru.example.maps", "ru.example.both"), apps.forceTunnel)
        assertEquals(setOf("org.example.bank"), apps.packages)

        r.setAppBypassed("ru.example.maps", bypassed = true)
        r.setAppBypassed("org.example.bank", bypassed = false)
        apps = r.settings.first().apps
        assertEquals(setOf("ru.example.both"), apps.forceTunnel)
        assertTrue(apps.packages.isEmpty(), "the rule covers ru.example.maps, so it is not selected")

        r.setPrefixBypassEnabled(false)
        r.setAppBypassed("ru.example.other", bypassed = true)
        assertEquals(setOf("ru.example.other"), r.settings.first().apps.packages)
    }

    @Test
    fun simpleModeIsStoredAndReadBackFromDisk() = runTest {
        val r = repo()
        assertFalse(r.simpleMode.first())
        r.setSimpleMode(true)
        assertTrue(r.simpleMode.first())
        assertTrue(r.ui.first().simpleMode)

        val reopened = SettingsRepository(SettingsRepository.createDataStore(File(tmp.root, "copy.preferences_pb").also { file.copyTo(it) }))
        assertTrue(reopened.simpleMode.first())
        assertEquals(UiSettings(simpleMode = true), reopened.ui.first())

        r.setSimpleMode(false)
        assertFalse(r.ui.first().simpleMode)
    }
}
