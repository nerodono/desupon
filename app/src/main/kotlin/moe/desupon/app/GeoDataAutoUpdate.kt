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

/** Asks for a due database check on every foreground, when a VPN session comes up and periodically while it stays up, as an always-on session may never show the UI. */
class GeoDataAutoUpdate(
    private val jobs: Jobs,
    private val vpnState: Flow<ConnState>,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    /** The work being scheduled, [GeoDataUpdater] and [RuleSetStore] in the app. */
    interface Jobs {
        /** Puts the bundled sets on disk, so rules using them read as usable before the first session. */
        suspend fun installSets()

        /** Runs until cancelled. */
        suspend fun watchRules()

        /** [routeChanged] as in [GeoDataUpdater.isDue]. */
        fun check(routeChanged: Boolean)
    }

    fun start() {
        scope.launch {
            try {
                jobs.installSets()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            jobs.watchRules()
        }
        scope.launch {
            vpnState.map(::isUp).distinctUntilChanged().collectLatest { up ->
                if (!up) return@collectLatest
                jobs.check(routeChanged = true)
                while (true) {
                    delay(CHECK_EVERY_MS)
                    jobs.check(routeChanged = false)
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) = jobs.check(routeChanged = false)

    private fun isUp(state: ConnState) =
        state is ConnState.Connected || state is ConnState.Switching || state is ConnState.Degraded || state is ConnState.Unstable

    companion object {
        const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000

        fun jobs(updater: GeoDataUpdater, store: RuleSetStore) = object : Jobs {
            override suspend fun installSets() {
                store.install()
            }

            override suspend fun watchRules() = updater.watchRules()

            override fun check(routeChanged: Boolean) = updater.updateInBackground(force = false, routeChanged = routeChanged)
        }
    }
}
