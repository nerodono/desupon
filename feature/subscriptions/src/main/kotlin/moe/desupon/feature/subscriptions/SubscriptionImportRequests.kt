package moe.desupon.feature.subscriptions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.desupon.data.fetch.SubscriptionFetcher

enum class SubscriptionsTab { SUBSCRIPTIONS, CONFIGS, SETS }

/** Which add dialog a request opens. */
enum class AddTarget { SUBSCRIPTION, CONFIG }

/** Text for an add dialog; empty opens it blank. */
data class AddRequest(val text: String, val target: AddTarget)

/**
 * Requests to the subscriptions screen from outside it (share intents, other screens).
 * Held until the screen consumes them, so they survive the screen not being shown yet.
 */
class SubscriptionImportRequests {
    private val _pending = MutableStateFlow<AddRequest?>(null)
    internal val pending: StateFlow<AddRequest?> = _pending.asStateFlow()

    private val _pendingTab = MutableStateFlow<SubscriptionsTab?>(null)
    internal val pendingTab: StateFlow<SubscriptionsTab?> = _pendingTab.asStateFlow()

    /** Opens the matching add dialog with [text] next time the subscriptions screen is shown; blank text is ignored. */
    fun prefill(text: String) {
        if (text.isNotBlank()) _pending.value = AddRequest(text, targetOf(text))
    }

    /** Opens the subscription dialog empty, unless a [prefill] is already waiting. */
    fun openAdd() {
        _pending.compareAndSet(null, AddRequest("", AddTarget.SUBSCRIPTION))
    }

    /** Shows [tab] next time the subscriptions screen is shown, closing an editor without unsaved changes. */
    fun openTab(tab: SubscriptionsTab) {
        _pendingTab.value = tab
    }

    fun openSets() = openTab(SubscriptionsTab.SETS)

    internal fun consume(request: AddRequest) {
        _pending.compareAndSet(request, null)
    }

    internal fun consumeTab(tab: SubscriptionsTab) {
        _pendingTab.compareAndSet(tab, null)
    }

    companion object {
        /** Lines that are all http(s) addresses are subscriptions; anything else is taken for configs. */
        fun targetOf(text: String): AddTarget {
            val lines = text.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() }
            return if (lines.isNotEmpty() && lines.all(SubscriptionFetcher::isSubscriptionUrl)) AddTarget.SUBSCRIPTION else AddTarget.CONFIG
        }
    }
}
