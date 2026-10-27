package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.subscription.SubId

/** [ConnState.Failed] reasons raised by the service itself. */
object VpnFailReason {
    const val NOT_PREPARED = FailReason.NOT_PREPARED

    const val START_BLOCKED = FailReason.START_BLOCKED
}

/**
 * Process-wide connection state for the UI and the tile: the running brain's state, otherwise Idle.
 * A failure stays visible after the service stopped, until the next start.
 */
class VpnStateHolder {
    enum class Phase { STOPPED, STARTING, RUNNING, STOPPING }

    private val lock = Any()
    private var _phase = Phase.STOPPED
    private var stopHandler: (() -> Unit)? = null

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _live = MutableStateFlow<Set<SubId>>(emptySet())
    val liveSubscriptions: StateFlow<Set<SubId>> = _live.asStateFlow()

    private val _mode = MutableStateFlow<NetworkMode?>(null)
    val networkMode: StateFlow<NetworkMode?> = _mode.asStateFlow()

    private val _step = MutableStateFlow<ChainStep?>(null)
    val activeStep: StateFlow<ChainStep?> = _step.asStateFlow()

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    val phase: Phase get() = synchronized(lock) { _phase }

    /** From STOPPED only; false when a session already exists. */
    fun starting(): Boolean = synchronized(lock) {
        if (_phase != Phase.STOPPED) return false
        _phase = Phase.STARTING
        _state.value = ConnState.Connecting(NetworkMode.UNKNOWN)
        _live.value = emptySet()
        _retrying.value = false
        true
    }

    fun running() = synchronized(lock) {
        if (_phase == Phase.STARTING) _phase = Phase.RUNNING
    }

    /** Brain state while RUNNING; the brain's initial Idle before it connects is not shown. */
    fun publish(brainState: ConnState) = synchronized(lock) {
        if (_phase == Phase.RUNNING && brainState != ConnState.Idle) _state.value = brainState
    }

    fun publishLive(live: Set<SubId>) = synchronized(lock) {
        if (_phase == Phase.RUNNING) _live.value = live
    }

    fun publishMode(mode: NetworkMode?) = synchronized(lock) {
        if (_phase == Phase.RUNNING) _mode.value = mode
    }

    fun publishStep(step: ChainStep?) = synchronized(lock) {
        if (_phase == Phase.RUNNING) _step.value = step
    }

    /** [retry]: the failure is going to be retried, see [awaitingRetry]. */
    fun stopping(retry: Boolean = false) = synchronized(lock) {
        if (_phase == Phase.STARTING || _phase == Phase.RUNNING) {
            _phase = Phase.STOPPING
            _retrying.value = retry
            _live.value = emptySet()
            _mode.value = null
            _step.value = null
            if (_state.value !is ConnState.Failed) _state.value = ConnState.Idle
        }
    }

    fun stopped(failure: String? = null) = stoppedWith(failure?.let { ConnState.Failed(it) })

    /** Keeps the failure detail, e.g. the unavailable country. */
    fun stoppedWith(failure: ConnState.Failed?) = synchronized(lock) {
        _phase = Phase.STOPPED
        stopHandler = null
        _retrying.value = false
        _live.value = emptySet()
        _mode.value = null
        _step.value = null
        _state.value = failure ?: ConnState.Idle
    }

    /** The session ended with [failure] and the service waits to start again; the bound stop handler still cancels the wait. */
    fun awaitingRetry(failure: ConnState.Failed) = synchronized(lock) {
        _phase = Phase.STOPPED
        _live.value = emptySet()
        _mode.value = null
        _step.value = null
        _state.value = failure
        _retrying.value = true
    }

    /** The running service registers how to stop it in-process, so no intent is needed. */
    fun bindStop(handler: () -> Unit) = synchronized(lock) { stopHandler = handler }

    /** True when a running service took the request. */
    fun requestStop(): Boolean {
        val handler = synchronized(lock) { stopHandler } ?: return false
        handler()
        return true
    }
}
