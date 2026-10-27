package moe.desupon.platform.iface

import android.net.Network
import android.net.VpnService

/** Implemented by the running VpnService; keeps this module free of the service itself. */
interface VpnHost {
    /** `VpnService.protect`. */
    fun protect(fd: Int): Boolean

    /** A new `VpnService.Builder` of the running service. */
    fun newTunBuilder(): VpnService.Builder

    /** `VpnService.prepare(context) == null`. */
    fun isVpnPrepared(): Boolean

    /** Neutral label for `Builder.setSession`; the system shows it in VPN settings. */
    val sessionLabel: String

    /** `VpnService.setUnderlyingNetworks`; null lets the system assume the default network. */
    fun setUnderlyingNetworks(networks: Array<Network>?): Boolean

    /** A core client asked the service to stop; called on a core thread. */
    fun onCoreStopRequested()
}
