package moe.desupon.brain.port

import kotlinx.coroutines.flow.Flow
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.status.ConnInfo
import moe.desupon.model.status.GroupInfo
import moe.desupon.model.status.TrafficStatus

/** The only way the brain drives the core. */
interface LibboxControl {
    val groups: Flow<List<GroupInfo>>
    val status: Flow<TrafficStatus>
    val connections: Flow<List<ConnInfo>>

    /**
     * Starts the service or replaces its config, restarting TUN; throws on core errors.
     * Implementations keep the own package tunnelled whatever [apps] says.
     */
    suspend fun reload(configJson: String, apps: AppSplit)

    /** Switches a selector member without restarting; after [reload] the selector restores its cached choice, so select again. */
    suspend fun select(group: String, tag: String)

    suspend fun closeConnections()

    /** Drops every connection and resets outbound clients, DNS transports and the TUN NAT, as a network change would. */
    suspend fun resetNetwork()

    /** Resets the clients of the outbound [tag] and of the outbounds it dials through, as a network change would; only connections over them break. */
    suspend fun resetOutbound(tag: String)

    suspend fun stop()
}
