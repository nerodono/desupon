package moe.desupon.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.feature.settings.LastConfigSource
import moe.desupon.feature.settings.logs.coreLevel
import moe.desupon.platform.log.CoreLogThreshold
import moe.desupon.vpn.lifecycle.LastConfig
import moe.desupon.vpn.lifecycle.VpnStateHolder
import org.koin.dsl.module

val appModule = module {
    single<LastConfigSource> {
        val last = get<LastConfig>()
        LastConfigSource { last.masked }
    }
    single<CoreLogThreshold> {
        val settings = get<SettingsRepository>()
        CoreLogThreshold { settings.logVerbosity.map { it.coreLevel() } }
    }
    single { SubscriptionAutoUpdate(get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    single {
        GeoDataAutoUpdate(GeoDataAutoUpdate.jobs(get(), get()), get<VpnStateHolder>().state, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
