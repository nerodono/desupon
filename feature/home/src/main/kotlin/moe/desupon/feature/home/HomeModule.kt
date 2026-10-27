package moe.desupon.feature.home

import moe.desupon.brain.port.Clock
import moe.desupon.data.di.AndroidClock
import moe.desupon.data.location.LocationListSource
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionRepository
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Needs `dataModule` plus [moe.desupon.brain.port.VpnControl] and [LocationListSource] bindings. */
val homeModule = module {
    viewModel {
        val selection = get<SelectionRepository>()
        HomeViewModel(
            vpn = get(),
            subscriptions = get<SubscriptionRepository>().subscriptions,
            sets = selection.subscriptionSets,
            locations = get<LocationListSource>().data,
            fallbacks = selection.fallbacks,
            clock = getOrNull<Clock>() ?: AndroidClock,
        )
    }
    viewModel {
        SimpleHomeViewModel(
            vpn = get(),
            subscriptions = get<SubscriptionRepository>().subscriptions,
            sets = get<SelectionRepository>().subscriptionSets,
            clock = getOrNull<Clock>() ?: AndroidClock,
        )
    }
}
