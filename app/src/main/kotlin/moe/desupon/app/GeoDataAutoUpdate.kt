package moe.desupon.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.desupon.brain.port.ConnState
import moe.desupon.data.geo.GeoDataUpdater
import moe.desupon.data.ruleset.RuleSetStore

/**
 * Asks [GeoDataUpdater] for a due check each time the app comes to the foreground, when a VPN session comes up and every
 * [CHECK_EVERY_MS] while it stays up, since an always-on session may never show the UI; also keeps enabled rules' categories converted.
 */
class GeoDataAutoUpdate(
    private val updater: GeoDataUpdater,
    private val store: RuleSetStore,
    private val vpnState: Flow<ConnState>,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    fun start() {
        scope.launch {
            try {
                store.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            updater.watchRules()
        }
        scope.launch {
            vpnState.map(::isUp).distinctUntilChanged().collectLatest { up ->
                while (up) {
                    updater.updateInBackground(force = false)
                    delay(CHECK_EVERY_MS)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) = updater.updateInBackground(force = false)

    private fun isUp(state: ConnState) =
        state is ConnState.Connected || state is ConnState.Switching || state is ConnState.Degraded || state is ConnState.Unstable

    private companion object {
        const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000
    }
}
