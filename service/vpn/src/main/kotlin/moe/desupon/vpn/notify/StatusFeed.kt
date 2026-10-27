package moe.desupon.vpn.notify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import moe.desupon.brain.port.ConnState
import moe.desupon.model.node.Node
import moe.desupon.model.status.TrafficStatus
import moe.desupon.vpn.R

/**
 * Contents of the status notification: a new one at once on a state, node, ping or subscription change, traffic at most every [intervalMs].
 * Nothing while the screen is off, and [traffic] is not collected then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusFeed(
    private val state: Flow<ConnState>,
    private val retrying: Flow<Boolean>,
    screenOn: Flow<Boolean>,
    private val traffic: Flow<TrafficStatus>,
    private val rtt: (Node) -> Flow<Int?>,
    private val subscription: (Node) -> Flow<SubscriptionLabel?>,
    private val intervalMs: Long = INTERVAL_MS,
) {
    val contents: Flow<StatusContent> = screenOn.distinctUntilChanged().flatMapLatest { on -> if (on) live() else emptyFlow() }

    private fun live(): Flow<StatusContent> = combine(state, retrying, ::Pair).distinctUntilChanged().flatMapLatest { (s, retrying) ->
        when {
            s is ConnState.Connected -> connected(s)
            s is ConnState.Failed && retrying -> flowOf(StatusContent.Plain(R.string.svc_state_retrying))
            else -> flowOf(StatusContent.Plain(s.statusText()))
        }
    }

    private fun connected(s: ConnState.Connected): Flow<StatusContent> =
        combine(rtt(s.node), subscription(s.node), ::Pair).distinctUntilChanged().flatMapLatest { (rttMs, sub) ->
            flow {
                traffic.conflate().collect { t ->
                    emit(StatusContent.Connected(s.node, s.connectedAtMs, sub, rttMs, t))
                    delay(intervalMs)
                }
            }
        }

    companion object {
        const val INTERVAL_MS = 2_000L
    }
}
