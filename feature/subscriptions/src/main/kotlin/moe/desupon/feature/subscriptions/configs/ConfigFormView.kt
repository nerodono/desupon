package moe.desupon.feature.subscriptions.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.R

internal fun FormProtocol.label(): String = when (this) {
    FormProtocol.VLESS -> "VLESS"
    FormProtocol.VMESS -> "VMess"
    FormProtocol.TROJAN -> "Trojan"
    FormProtocol.SHADOWSOCKS -> "Shadowsocks"
    FormProtocol.HYSTERIA2 -> "Hysteria2"
    FormProtocol.TUIC -> "TUIC"
}

/** Choice of protocol for a config entered by hand; picking one starts a blank form. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProtocolPicker(selected: FormProtocol?, enabled: Boolean, onPick: (FormProtocol) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.subs_config_protocol), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormProtocol.entries.forEach { p ->
                FilterChip(selected = p == selected, onClick = { onPick(p) }, enabled = enabled, label = { Text(p.label()) })
            }
        }
    }
}

/** The structured fields of [form]. */
@Composable
internal fun ConfigFormView(form: ConfigForm, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    Text(
        text = stringResource(R.string.subs_config_form_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
