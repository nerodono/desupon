package moe.desupon.vpn.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.desupon.brain.port.ConnState
import moe.desupon.vpn.R
import moe.desupon.vpn.lifecycle.ServiceVpnControl
import moe.desupon.vpn.lifecycle.VpnStateHolder
import moe.desupon.vpn.notify.statusText
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Quick Settings toggle; without VPN consent it opens the app, since the consent dialog needs an activity result. */
class DesuponTileService : TileService(), KoinComponent {
    private val holder: VpnStateHolder by inject()
    private val control: ServiceVpnControl by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { holder.state.collect(::render) }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        if (holder.phase != VpnStateHolder.Phase.STOPPED) {
            scope.launch { control.disconnect() }
            return
        }
        if (control.prepareIntent() != null) {
            openApp()
            return
        }
        scope.launch { control.connect() }
    }

    private fun render(state: ConnState) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.svc_tile_label)
        tile.subtitle = getString(state.statusText())
        tile.state = when (state) {
            ConnState.Idle, is ConnState.Failed -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        launch.putExtra(EXTRA_REQUEST_CONNECT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    companion object {
        /** Set on the launch intent when the tile needs the app to ask for VPN consent and then connect. */
        const val EXTRA_REQUEST_CONNECT = "moe.desupon.vpn.extra.REQUEST_CONNECT"
    }
}
