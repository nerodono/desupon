package moe.desupon.feature.home

import androidx.compose.runtime.Immutable
import moe.desupon.data.location.ProbeState
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.SetId
import moe.desupon.ui.theme.ConnectionButtonState

@Immutable
sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** No subscriptions and nothing running. */
    data object Empty : HomeUiState

    @Immutable
    data class Ready(
        val button: ConnectionButtonState,
        /** Caption override; null keeps the button's own state caption. */
        val caption: Caption?,
        val action: ButtonAction,
        /** Wall-clock start of the session; the timer shows only while [button] is Connected. */
        val sessionStartMs: Long?,
        val location: LocationSummary,
        val traffic: TrafficCard?,
        val expiry: ExpiryCard?,
        val showRetry: Boolean,
        /** The location choice made the pool empty; offer switching to any country. */
        val offerAnyCountry: Boolean = false,
    ) : HomeUiState
}

enum class ButtonAction { CONNECT, DISCONNECT }

@Immutable
sealed interface Caption {
    data object TurnWarmup : Caption
    data object Degraded : Caption
    data object Unstable : Caption
    data object FailConfig : Caption
    data object FailCore : Caption
    data object FailNotPrepared : Caption
    data object FailStartBlocked : Caption
    data object FailUnknown : Caption

    /** [country] is the localized name; null when the brain gave no ISO code. */
    data class CountryUnavailable(val country: String?) : Caption
    data object NodeUnavailable : Caption
}

/** The bottom row: where the connection goes and over which set. */
@Immutable
data class LocationSummary(
    val place: Place,
    /** The node was picked automatically within an Any/Country choice. */
    val auto: Boolean,
    /** Null for [SetId.ALL]. */
    val setName: String?,
    /** Subscription of the current node; only when the active set mixes several. */
    val subscription: SubscriptionLabel?,
    val showHint: Boolean,
    /** The active set's priority chain; null without one. */
    val chain: ChainLine? = null,
)

/** [active] indexes the step the running session is on; [rest] marks it being on the remaining members. */
@Immutable
data class ChainLine(val steps: List<SubscriptionLabel>, val active: Int? = null, val rest: Boolean = false)

@Immutable
sealed interface Place {
    data object AnyCountry : Place

    /** A country or a node. */
    data class Named(val flag: String?, val name: String) : Place

    /** A pin to a node no longer listed. */
    data object UnknownNode : Place
}

/** [title] null falls back to "Подписка [ordinal]". */
data class SubscriptionLabel(val title: String?, val ordinal: Int)

enum class ByteUnit { B, KB, MB, GB, TB }

data class ByteAmount(val value: String, val unit: ByteUnit)

/** [total] null means unlimited. */
data class TrafficCard(val used: ByteAmount, val total: ByteAmount?)

data class ExpiryCard(val daysLeft: Int) {
    val expired: Boolean get() = daysLeft <= 0
    val endingSoon: Boolean get() = daysLeft in 1..SOON_DAYS

    private companion object {
        const val SOON_DAYS = 3
    }
}

@Immutable
data class LocationSheetState(
    val sets: List<SetChip>,
    val refreshing: Boolean,
    val whitelistBanner: Boolean,
    val anySelected: Boolean,
    /** A pinned node outside the active set, listed under "any country". */
    val pinnedOutside: NodeRow?,
    /** Russia first, then by probe outcome. */
    val countries: List<CountryRow>,
    /** Nodes without a country. */
    val other: List<NodeRow>,
    val chain: ChainLine? = null,
)

/** [title] null is [SetId.ALL]. */
data class SetChip(val id: SetId, val title: String?, val selected: Boolean)

@Immutable
data class CountryRow(
    val iso: String,
    val name: String,
    val flag: String?,
    /** Russian nodes serve as relays rather than exits. */
    val relay: Boolean,
    val state: ProbeState,
    /** While [state] is CHECKING: the last answered value, if any. */
    val bestRttMs: Int?,
    val selected: Boolean,
    val nodes: List<NodeRow>,
) {
    val containsSelected: Boolean get() = nodes.any { it.selected }
}

data class NodeRow(
    val id: NodeId,
    val name: String,
    val flag: String?,
    val state: ProbeState,
    /** While [state] is CHECKING: the last answered value, if any. */
    val rttMs: Int?,
    val selected: Boolean,
    /** Shown only when the active set mixes several subscriptions. */
    val subscription: SubscriptionLabel?,
)

enum class RttLevel { GOOD, FAIR, SLOW }

sealed interface HomeEvent {
    data class ProbeThrottled(val seconds: Long) : HomeEvent
    data object LocationRejected : HomeEvent

    /** [country] null means any country. */
    data class PinFellBack(val country: String?) : HomeEvent

    /** Connect through the VPN consent path. */
    data object Connect : HomeEvent
}
