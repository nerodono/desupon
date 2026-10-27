package moe.desupon.feature.home

import androidx.compose.runtime.Immutable
import moe.desupon.brain.port.ConnState
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.ConnectionButtonState

enum class SimpleStatus { TAP_TO_CONNECT, NO_SERVERS, CHECKING, CONNECTING, SWITCHING, CONNECTED, RETRYING }

@Immutable
data class SimpleUiState(
    val button: ConnectionButtonState,
    val status: SimpleStatus,
    val action: ButtonAction,
    /** Every subscription of the active set has run out. */
    val expired: Boolean,
) {
    val buttonEnabled: Boolean get() = status != SimpleStatus.NO_SERVERS
}

/** A failure nobody retries reads as plain "off", so the next tap is an obvious "turn on". */
internal fun simpleUiState(
    conn: ConnState,
    retrying: Boolean,
    subs: List<SubscriptionEntry>,
    live: Set<SubId>,
    expired: Boolean,
    poolEmpty: Boolean = false,
): SimpleUiState {
    val idle = conn is ConnState.Idle || conn is ConnState.Failed
    val (button, status) = when {
        retrying && conn is ConnState.Failed -> ConnectionButtonState.Error to SimpleStatus.RETRYING
        idle && poolEmpty -> ConnectionButtonState.Disconnected to SimpleStatus.NO_SERVERS
        idle -> ConnectionButtonState.Disconnected to SimpleStatus.TAP_TO_CONNECT
        else -> buttonState(conn, subs, live).let { b ->
            b to when (b) {
                is ConnectionButtonState.Checking -> SimpleStatus.CHECKING
                ConnectionButtonState.Connecting -> SimpleStatus.CONNECTING
                ConnectionButtonState.Switching -> SimpleStatus.SWITCHING
                else -> SimpleStatus.CONNECTED
            }
        }
    }
    return SimpleUiState(
        button = button,
        status = status,
        action = if (idle && status != SimpleStatus.RETRYING) ButtonAction.CONNECT else ButtonAction.DISCONNECT,
        expired = expired,
    )
}

/** [activeSet] null means every subscription; ones without an expiry date never count as expired. */
internal fun poolExpired(subs: List<SubscriptionEntry>, activeSet: Set<SubId>?, nowMs: Long): Boolean {
    val pool = if (activeSet == null) subs else subs.filter { it.info.id in activeSet }
    return pool.isNotEmpty() && pool.all { s ->
        s.info.userInfo?.takeIf { it.hasExpiry }?.let { daysLeft(it.expireEpochSec, nowMs) == 0 } == true
    }
}

/** Nothing automatic selection could use; before the sets load, judged by the subscriptions alone. */
internal fun poolEmpty(subs: List<SubscriptionEntry>, active: SubscriptionSetEntry?): Boolean =
    active?.let { it.enabledNodeCount == 0 } ?: subs.isEmpty()
