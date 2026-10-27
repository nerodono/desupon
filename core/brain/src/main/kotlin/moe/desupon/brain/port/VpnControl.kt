package moe.desupon.brain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId

/** UI-facing facade of the connection. */
interface VpnControl {
    val state: StateFlow<ConnState>

    /** While [ConnState.Connecting]: subscriptions that already have a node passing GET204; empty otherwise. */
    val liveSubscriptions: StateFlow<Set<SubId>> get() = NoLiveSubscriptions

    /** Mode of the current network while a session runs; null otherwise. */
    val networkMode: StateFlow<NetworkMode?> get() = NoNetworkMode

    /** Nodes in a pool probe right now, for CHECKING states. */
    val probing: StateFlow<Set<NodeId>> get() = NoProbing

    /**
     * Chain step the current node belongs to, while a session has one; null when the active set has no chain or a node pin bypasses it.
     * The chain itself is the active set's [moe.desupon.model.selection.SubscriptionSet.chain].
     */
    val activeStep: StateFlow<ChainStep?> get() = NoActiveStep

    /** Simple mode waits to reconnect after a failure; [state] stays [ConnState.Failed] meanwhile. */
    val retrying: StateFlow<Boolean> get() = NotRetrying

    val activeSetId: Flow<SetId> get() = flowOf(SetId.ALL)

    /** Priority chain of the active set, connected or not. */
    val activeChain: Flow<List<SubId>> get() = flowOf(emptyList())

    val location: Flow<LocationChoice> get() = flowOf(LocationChoice.Any)

    /** Requires VPN consent (`VpnService.prepare`) to have been granted already. */
    suspend fun connect()

    suspend fun disconnect()

    /** Persisted; a running session re-plans at once. False when [id] names no set. */
    suspend fun setActiveSet(id: SetId): Boolean = false

    /** Persisted; a running session re-plans at once. False for a pin to a node that is unknown, disabled or unsupported. */
    suspend fun setLocation(choice: LocationChoice): Boolean = false

    /**
     * Probes the active set's pool, connected or not; see [moe.desupon.brain.probe.PoolProbes.request].
     * Auto requests pass every 15 s, forced ones every 10 s; the whole pool is swept at most every 2 min on Wi-Fi and 5 min on cellular,
     * the top-ranked nodes, the chosen country and the pin in between.
     */
    suspend fun requestProbe(forced: Boolean = false): ProbeRequestResult = ProbeRequestResult.Unavailable
}

private val NoLiveSubscriptions: StateFlow<Set<SubId>> = MutableStateFlow(emptySet<SubId>()).asStateFlow()
private val NoNetworkMode: StateFlow<NetworkMode?> = MutableStateFlow<NetworkMode?>(null).asStateFlow()
private val NoProbing: StateFlow<Set<NodeId>> = MutableStateFlow(emptySet<NodeId>()).asStateFlow()
private val NoActiveStep: StateFlow<ChainStep?> = MutableStateFlow<ChainStep?>(null).asStateFlow()
private val NotRetrying: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

sealed interface ConnState {
    data object Idle : ConnState

    data class Connecting(val mode: NetworkMode) : ConnState

    /** [connectedAtMs] is wall time of the session start and survives reloads. */
    data class Connected(val node: Node, val tier: Tier, val connectedAtMs: Long) : ConnState

    data class Switching(val from: Node, val to: Node) : ConnState

    data class TurnWarmup(val reason: String) : ConnState

    /**
     * Nothing in the pool answers; retried with backoff.
     * [reason] is set when the location choice narrowed the pool: COUNTRY_UNAVAILABLE or NODE_UNAVAILABLE with [detail].
     */
    data class Degraded(val node: Node, val sinceMs: Long, val reason: String? = null, val detail: String? = null) : ConnState

    /** Too many switches in the window; pinned to [best]. */
    data class Unstable(val best: Node) : ConnState

    /** [detail]: the ISO code for COUNTRY_UNAVAILABLE, the node hash for NODE_UNAVAILABLE. */
    data class Failed(val reason: String, val detail: String? = null) : ConnState
}
