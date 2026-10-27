package moe.desupon.data.di

import android.os.SystemClock
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.NetworkMemoryRepo
import moe.desupon.brain.port.NodeRepo
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.brain.port.TierLearningRepo
import moe.desupon.data.db.DesuponDatabase
import moe.desupon.data.fetch.BodyCache
import moe.desupon.data.fetch.BypassNetwork
import moe.desupon.data.fetch.boundTo
import moe.desupon.data.fetch.DeviceInfo
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.repo.NetworkMemoryRepository
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.ProbeStatRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SimpleModeSelection
import moe.desupon.data.repo.SplitRuleRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.data.repo.TierLearningRepository
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.SettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

/** Wall clock plus elapsed realtime; used unless the graph already binds a [Clock]. */
object AndroidClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

private fun Scope.clock(): Clock = getOrNull() ?: AndroidClock

val dataModule = module {
    single { DesuponDatabase.create(androidContext()) }
    single { BodyCache(File(androidContext().noBackupFilesDir, "sub")) }
    single { SettingsRepository(SettingsRepository.createDataStore(androidContext())) } bind SettingsPort::class
    single {
        val client = SubscriptionFetcher.defaultClient()
        val bypass = getOrNull<BypassNetwork>()
        SubscriptionFetcher(client, get(), DeviceInfo.current(), clock(), bypass = { bypass?.current()?.let(client::boundTo) })
    }
    single {
        SubscriptionRepository(get(), get(), get(), get<SettingsRepository>().subscriptionUserAgent, clock(), get<SelectionRepository>())
    }
    single { NodeRepository(get<DesuponDatabase>().nodeDao()) } bind NodeRepo::class
    single { SelectionRepository(get(), get()) }
    single<SelectionPort> { SimpleModeSelection(get<SelectionRepository>(), get<SettingsRepository>().simpleMode) }
    single { SplitRuleRepository(get<DesuponDatabase>().splitRuleDao()) } bind SplitRuleRepo::class
    single {
        val db = get<DesuponDatabase>()
        NetworkMemoryRepository(db.networkMemoryDao(), db.probeStatDao(), clock())
    } bind NetworkMemoryRepo::class
    single { ProbeStatRepository(get<DesuponDatabase>().probeStatDao(), clock()) } bind ProbeStatRepo::class
    single { TierLearningRepository(get<DesuponDatabase>().tierLearningDao(), clock()) } bind TierLearningRepo::class
    single { RuleSetStore(androidContext().assets, File(androidContext().filesDir, RuleSetStore.ASSET_DIR)) }
}
