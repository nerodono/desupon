package moe.desupon.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.desupon.feature.settings.LastConfigSource
import moe.desupon.vpn.lifecycle.LastConfig
import org.koin.dsl.module

val appModule = module {
    single<LastConfigSource> {
        val last = get<LastConfig>()
        LastConfigSource { last.masked }
    }
    single { SubscriptionAutoUpdate(get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}
