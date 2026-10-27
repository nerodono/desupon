package moe.desupon.vpn.lifecycle

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.VpnControl
import moe.desupon.brain.probe.PoolProbes
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.vpn.service.DesuponVpnService

/**
 * [VpnControl] for the UI and the tile: starts and stops [DesuponVpnService] in the same process.
 * Selection changes go to [selection], which a running session observes.
 */
class ServiceVpnControl(
    context: Context,
    private val holder: VpnStateHolder,
    private val selection: SelectionRepository? = null,
    private val probes: PoolProbes? = null,
) : VpnControl {
    private val app = context.applicationContext

    override val state: StateFlow<ConnState> = holder.state

    override val liveSubscriptions: StateFlow<Set<SubId>> = holder.liveSubscriptions

    override val networkMode: StateFlow<NetworkMode?> = holder.networkMode

    override val activeStep: StateFlow<ChainStep?> = holder.activeStep

    override val retrying: StateFlow<Boolean> = holder.retrying

    override val probing: StateFlow<Set<NodeId>> = probes?.probing ?: MutableStateFlow(emptySet())

    override val activeSetId: Flow<SetId> = selection?.activeSetId ?: flowOf(SetId.ALL)

    override val location: Flow<LocationChoice> = selection?.location ?: flowOf(LocationChoice.Any)

    override val activeChain: Flow<List<SubId>> = selection?.selection?.map { it.chain }?.distinctUntilChanged() ?: flowOf(emptyList())

    /** Consent dialog to launch for result before [connect]; null when consent is already granted. */
    fun prepareIntent(): Intent? = VpnService.prepare(app)

    /** Returns once the service is asked to start; progress arrives through [state]. */
    override suspend fun connect() {
        if (prepareIntent() != null) {
            holder.stopped(VpnFailReason.NOT_PREPARED)
            return
        }
        holder.starting()
        try {
            ContextCompat.startForegroundService(app, DesuponVpnService.startIntent(app))
        } catch (_: IllegalStateException) {
            holder.stopped(VpnFailReason.START_BLOCKED)
        } catch (_: SecurityException) {
            holder.stopped(VpnFailReason.START_BLOCKED)
        }
    }

    override suspend fun disconnect() {
        if (holder.requestStop()) return
        if (holder.phase == VpnStateHolder.Phase.STOPPED) {
            holder.stopped()
            return
        }
        runCatching { app.startService(DesuponVpnService.stopIntent(app)) }
    }

    override suspend fun setActiveSet(id: SetId): Boolean = selection?.setActiveSet(id) ?: false

    override suspend fun setLocation(choice: LocationChoice): Boolean = selection?.setLocation(choice) ?: false

    override suspend fun requestProbe(forced: Boolean): ProbeRequestResult = probes?.request(forced) ?: ProbeRequestResult.Unavailable
}
