package moe.desupon.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import moe.desupon.data.di.dataModule
import moe.desupon.feature.home.homeModule
import moe.desupon.feature.settings.settingsModule
import moe.desupon.feature.subscriptions.subscriptionsModule
import moe.desupon.platform.di.platformModule
import moe.desupon.vpn.di.serviceModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DesuponApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@DesuponApp)
            modules(dataModule, platformModule, serviceModule, homeModule, subscriptionsModule, settingsModule, appModule)
        }.koin
        ProcessLifecycleOwner.get().lifecycle.addObserver(koin.get<SubscriptionAutoUpdate>())
    }
}
