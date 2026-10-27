package moe.desupon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry

class SimpleHomeViewModel(
    private val vpn: VpnControl,
    subscriptions: Flow<List<SubscriptionEntry>>,
    sets: Flow<List<SubscriptionSetEntry>>,
    clock: Clock,
) : ViewModel() {
    private val minutes: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(TICK_MS)
        }
    }

    private class Pool(val subs: List<SubscriptionEntry>, val expired: Boolean, val empty: Boolean)

    private val activeSet = sets.map { list -> list.firstOrNull { it.isActive } }.distinctUntilChanged()

    private val pool = combine(subscriptions, activeSet, minutes) { subs, set, now ->
        Pool(subs, poolExpired(subs, set?.set?.members, now), poolEmpty(subs, set))
    }

    /** Null until the inputs arrive. */
    val state: StateFlow<SimpleUiState?> =
        combine(vpn.state, vpn.retrying, vpn.liveSubscriptions, pool) { conn, retrying, live, p ->
            simpleUiState(conn, retrying, p.subs, live, p.expired, p.empty)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Caller must have obtained VPN consent first. */
    fun connect() {
        viewModelScope.launch { vpn.connect() }
    }

    /** Also ends a pending automatic retry. */
    fun disconnect() {
        viewModelScope.launch { vpn.disconnect() }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
