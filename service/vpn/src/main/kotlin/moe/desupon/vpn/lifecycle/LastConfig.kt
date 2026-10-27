package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.desupon.brain.port.LibboxControl
import moe.desupon.config.ConfigRedaction
import moe.desupon.model.routing.AppSplit
import moe.desupon.platform.log.Redactor

/** The config the core last accepted, masked when recorded so no raw credential is kept; in memory for this process only. */
class LastConfig {
    private val _masked = MutableStateFlow<String?>(null)
    val masked: StateFlow<String?> = _masked.asStateFlow()

    fun record(rawJson: String) {
        _masked.value = mask(rawJson)
    }

    companion object {
        fun mask(rawJson: String): String = Redactor.mask(ConfigRedaction.mask(rawJson))
    }
}

/** Records every config the core accepts into [last]. */
class RecordingLibbox(private val delegate: LibboxControl, private val last: LastConfig) : LibboxControl by delegate {
    override suspend fun reload(configJson: String, apps: AppSplit) {
        delegate.reload(configJson, apps)
        last.record(configJson)
    }
}
