package moe.desupon.platform.libbox

import moe.desupon.model.status.ConnInfo

/** Selector tag whose members carry proxied traffic. */
const val PROXY_GROUP = "proxy"

/** Plain-data copy of a core connection event, so the table logic runs without the native library. */
sealed interface ConnEvent {
    val id: String

    data class New(val connection: ConnInfo) : ConnEvent {
        override val id: String get() = connection.id
    }

    data class Update(override val id: String, val uplinkDelta: Long, val downlinkDelta: Long) : ConnEvent

    /** [connection] is the final snapshot when the core sends one. */
    data class Closed(override val id: String, val closedAtMs: Long, val connection: ConnInfo?) : ConnEvent
}

/** Mirrors the core's connection list from its event stream; closed entries linger for [closedRetentionMs]. */
class ConnectionTable(private val closedRetentionMs: Long = 10_000) {
    private val byId = LinkedHashMap<String, ConnInfo>()
    private val closedAt = HashMap<String, Long>()

    fun apply(reset: Boolean, events: List<ConnEvent>, nowMs: Long): List<ConnInfo> {
        if (reset) {
            byId.clear()
            closedAt.clear()
        }
        for (event in events) {
            when (event) {
                is ConnEvent.New -> {
                    byId[event.id] = event.connection
                    closedAt.remove(event.id)
                }

                is ConnEvent.Update -> byId[event.id]?.let {
                    byId[event.id] = it.copy(
                        uplinkTotal = it.uplinkTotal + event.uplinkDelta,
                        downlinkTotal = it.downlinkTotal + event.downlinkDelta,
                    )
                }

                is ConnEvent.Closed -> {
                    val base = event.connection ?: byId[event.id] ?: continue
                    byId[event.id] = base.copy(closed = true)
                    closedAt[event.id] = event.closedAtMs.takeIf { it > 0 } ?: nowMs
                }
            }
        }
        val expired = closedAt.filterValues { nowMs - it > closedRetentionMs }.keys
        for (id in expired) {
            byId.remove(id)
            closedAt.remove(id)
        }
        return byId.values.toList()
    }
}

/** Traffic of the connections that ended at one outbound. */
data class OutboundTraffic(
    val outbound: String,
    val active: Int,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
)

fun List<ConnInfo>.proxied(group: String = PROXY_GROUP): List<ConnInfo> = filter { group in it.chain }

/** Aggregates proxied connections per final outbound; direct traffic is left out so it cannot mask a dead proxy. */
fun List<ConnInfo>.trafficByOutbound(group: String = PROXY_GROUP): Map<String, OutboundTraffic> =
    proxied(group).groupBy { it.outbound }.mapValues { (outbound, conns) ->
        OutboundTraffic(
            outbound = outbound,
            active = conns.count { !it.closed },
            uplinkTotal = conns.sumOf { it.uplinkTotal },
            downlinkTotal = conns.sumOf { it.downlinkTotal },
        )
    }
