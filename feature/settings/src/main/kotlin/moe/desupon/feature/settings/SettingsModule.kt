package moe.desupon.feature.settings

import moe.desupon.feature.settings.apps.InstalledAppsSource
import moe.desupon.feature.settings.apps.PackageManagerAppsSource
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Requires `dataModule`; bind a [LastConfigSource] elsewhere to show the generated config. */
val settingsModule = module {
    single<InstalledAppsSource> { PackageManagerAppsSource(androidContext()) }
    viewModel {
        SettingsViewModel(get(), get(), getOrNull<LastConfigSource>() ?: LastConfigSource.None, get(), get(), get(), androidContext().packageName)
    }
    viewModel { SplitTunnelViewModel(get(), get(), get(), get(), androidContext().packageName) }
}
