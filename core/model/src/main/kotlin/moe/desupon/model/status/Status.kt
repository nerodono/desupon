package moe.desupon.model.status

data class GroupInfo(
    val tag: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val items: List<GroupItem>,
)

data class GroupItem(
    val tag: String,
    val type: String,
    /** Last urltest delay; null when untested. */
    val delayMs: Int?,
)

/** Rates in bytes per second, totals in bytes. */
data class TrafficStatus(
    val uplinkRate: Long,
    val downlinkRate: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val connectionsIn: Int,
    val connectionsOut: Int,
)

data class ConnInfo(
    val id: String,
    val network: String,
    val destination: String,
    val domain: String?,
    val outbound: String,
    /** Outbounds the connection passed through, group tags included. */
    val chain: List<String>,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val createdAtMs: Long,
    val closed: Boolean,
)
