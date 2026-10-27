package moe.desupon.feature.subscriptions

import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.di.AndroidClock
import moe.desupon.feature.subscriptions.configs.ConfigEditorViewModel
import moe.desupon.feature.subscriptions.editor.SubscriptionEditorViewModel
import moe.desupon.feature.subscriptions.sets.SetsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs `dataModule`; uses `VpnControl` when bound. Binds [SubscriptionImportRequests] for the app's share-intent handling. */
val subscriptionsModule = module {
    single { SubscriptionImportRequests() }
    viewModel { SubscriptionsViewModel(get(), get(), get(), get(), getOrNull<Clock>() ?: AndroidClock, getOrNull<VpnControl>()) }
    viewModel { SetsViewModel(get(), get()) }
    viewModel { SubscriptionEditorViewModel(get(), get(), get(), getOrNull<Clock>() ?: AndroidClock) }
    viewModel { ConfigEditorViewModel(get(), get(), get()) }
}
