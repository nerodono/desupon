package moe.desupon.feature.subscriptions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubscriptionsTab { SUBSCRIPTIONS, SETS }

/**
 * Requests to the subscriptions screen from outside it (share intents, other screens).
 * Held until the screen consumes them, so they survive the screen not being shown yet.
 */
class SubscriptionImportRequests {
    private val _pending = MutableStateFlow<String?>(null)
    internal val pending: StateFlow<String?> = _pending.asStateFlow()

    private val _pendingTab = MutableStateFlow<SubscriptionsTab?>(null)
    internal val pendingTab: StateFlow<SubscriptionsTab?> = _pendingTab.asStateFlow()

    /** Opens the add dialog with [text] next time the subscriptions screen is shown; blank text is ignored. */
    fun prefill(text: String) {
        if (text.isNotBlank()) _pending.value = text
    }

    /** Opens the add dialog empty, unless a [prefill] is already waiting. */
    fun openAdd() {
        _pending.compareAndSet(null, "")
    }

    /** Shows [tab] next time the subscriptions screen is shown, closing an editor without unsaved changes. */
    fun openTab(tab: SubscriptionsTab) {
        _pendingTab.value = tab
    }

    fun openSets() = openTab(SubscriptionsTab.SETS)

    internal fun consume(text: String) {
        _pending.compareAndSet(text, null)
    }

    internal fun consumeTab(tab: SubscriptionsTab) {
        _pendingTab.compareAndSet(tab, null)
    }
}
