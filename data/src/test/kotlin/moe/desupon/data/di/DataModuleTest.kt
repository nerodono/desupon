package moe.desupon.data.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.NetworkMemoryRepo
import moe.desupon.brain.port.NodeRepo
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.brain.port.TierLearningRepo
import moe.desupon.data.db.DesuponDatabase
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.data.split.RuDefaults
import moe.desupon.model.selection.LocationChoice
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DataModuleTest {
    @After
    fun tearDown() {
        GlobalContext.getOrNull()?.get<DesuponDatabase>()?.close()
        stopKoin()
    }

    @Test
    fun graphResolvesPortsAndKeepsStateInNoBackupDir() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            androidContext(context)
            modules(dataModule)
        }.koin

        assertEquals(RuDefaults.appSplit, koin.get<SettingsPort>().settings.first().apps)
        assertEquals(RuDefaults.rules, koin.get<SplitRuleRepo>().rules.first().map { it.copy(id = 0) })
        assertTrue(koin.get<NodeRepo>().nodes.first().isEmpty())
        koin.get<NetworkMemoryRepo>()
        koin.get<ProbeStatRepo>()
        koin.get<TierLearningRepo>()
        assertTrue(koin.get<SubscriptionRepository>().subscriptions.first().isEmpty())

        assertTrue(File(context.noBackupFilesDir, "desupon.db").isFile)
        val paths = koin.get<RuleSetStore>().install().values.map { File(it.path) }
        assertTrue(paths.all { it.parentFile == File(context.filesDir, RuleSetStore.ASSET_DIR) })
    }

    @Test
    fun simpleModeForcesAnyCountryWithoutTouchingTheStoredChoice() = runTest {
        val koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext<Context>())
            modules(dataModule)
        }.koin
        val stored = koin.get<SelectionRepository>()
        val port = koin.get<SelectionPort>()
        val settings = koin.get<SettingsRepository>()
        assertTrue(stored.setLocation(LocationChoice.country("nl")))
        assertEquals(LocationChoice.country("NL"), port.selection.first().choice)

        settings.setSimpleMode(true)
        assertEquals(LocationChoice.Any, port.selection.first().choice)
        assertEquals(LocationChoice.country("NL"), stored.location.first())

        settings.setSimpleMode(false)
        assertEquals(LocationChoice.country("NL"), port.selection.first().choice)
    }
}
