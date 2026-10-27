package moe.desupon.model.turn

import moe.desupon.model.Secret

/**
 * FreeTurn profile carrying every `freeturn://` v=1 field plus local settings.
 * [mode], [listen], [dnsMode], [dnsServers] and [manualCaptcha] survive link round-trips only:
 * the core forces UDP relay, plain DNS and no listener.
 */
data class TurnProfile(
    val id: String,
    val name: String = "",
    val provider: String = PROVIDER_VK,
    /** FreeTurn server, host:port. */
    val peer: String,
    /** TURN transport: tcp (TURN/TLS) or udp; empty means core default. */
    val transport: String = "",
    val mode: String = "",
    val bond: Boolean = false,
    val obfProfile: String = "",
    val obfKey: Secret = Secret.EMPTY,
    val obfTimingMs: Int = 0,
    /** Link field `n`; 0 means core default. */
    val streams: Int = 0,
    val streamsPerCred: Int = 0,
    val clientId: Secret = Secret.EMPTY,
    val listen: String = "",
    val dnsMode: String = "",
    val dnsServers: String = "",
    val manualCaptcha: Boolean = false,
    val kcp: KcpParams? = null,
    /** VK call join link (link field `vk`). */
    val callLink: Secret = Secret.EMPTY,
    /** WireGuard config of the server side reached through the relay. */
    val wgConf: Secret = Secret.EMPTY,
    val turnHost: String = "",
    val mtu: Int = DEFAULT_MTU,
) {
    companion object {
        const val PROVIDER_VK = "vk"
        const val DEFAULT_MTU = 1280
    }
}

/** ARQ parameters of FreeTurn tcp mode, kept for link round-trips. */
data class KcpParams(
    val noDelay: Int,
    val interval: Int,
    val resend: Int,
    val nc: Int,
    val sndWnd: Int,
    val rcvWnd: Int,
    val mtu: Int,
    val ackNoDelay: Boolean,
)

sealed interface TurnState {
    data object Dormant : TurnState

    data class Connecting(val streams: Int, val total: Int) : TurnState

    data class Captcha(val manual: Boolean) : TurnState

    data class Connected(val streams: Int, val total: Int) : TurnState

    /** [code] as reported by the core, e.g. INVALID_JOIN_LINK, CALL_FULL, ANON_BLOCKED, FATAL_CAPTCHA_FAILED_NO_STREAMS. */
    data class Failed(val code: String, val message: String? = null) : TurnState
}
