package moe.desupon.feature.subscriptions.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.feature.subscriptions.resolve
import moe.desupon.feature.subscriptions.ui.SubsIcons

internal fun FormProtocol.label(): String = when (this) {
    FormProtocol.VLESS -> "VLESS"
    FormProtocol.VMESS -> "VMess"
    FormProtocol.TROJAN -> "Trojan"
    FormProtocol.SHADOWSOCKS -> "Shadowsocks"
    FormProtocol.HYSTERIA2 -> "Hysteria2"
    FormProtocol.TUIC -> "TUIC"
    FormProtocol.AMNEZIAWG -> "AmneziaWG"
    FormProtocol.WIREGUARD -> "WireGuard"
    FormProtocol.HYSTERIA -> "Hysteria v1"
    FormProtocol.JUICITY -> "Juicity"
    FormProtocol.ANYTLS -> "AnyTLS"
    FormProtocol.SOCKS -> "SOCKS5"
    FormProtocol.HTTP -> "HTTP"
}

/** Choice of protocol for a config entered by hand; picking one starts a blank form. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProtocolPicker(selected: FormProtocol?, enabled: Boolean, onPick: (FormProtocol) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.subs_config_protocol))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormProtocol.entries.forEach { p ->
                FilterChip(selected = p == selected, onClick = { onPick(p) }, enabled = enabled, label = { Text(p.label()) })
            }
        }
    }
}

/** The structured fields of [form]; sections appear only for the choices that need them. */
@Composable
internal fun ConfigFormView(form: ConfigForm, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(form.protocol.label())
        OutlinedTextField(
            value = form.name,
            onValueChange = { onChange(form.name(it)) },
            label = { Text(stringResource(R.string.subs_form_name)) },
            supportingText = { Text(stringResource(R.string.subs_form_name_hint)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        val warnings = form.warnings()
        val fields = form.fields()
        for (field in fields) {
            if (field == Field.JC) {
                SectionTitle(stringResource(R.string.subs_form_awg_section))
                Hint(stringResource(R.string.subs_form_hint_awg))
            }
            val row = ROWS.firstOrNull { field in it && it.all(fields::contains) }
            when {
                row == null -> FieldInput(form, field, warnings[field], enabled, onChange)
                field == row.first() -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { f ->
                        Box(Modifier.weight(1f)) { FieldInput(form, f, warnings[f], enabled, onChange) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldInput(form: ConfigForm, field: Field, warning: UiText?, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    when (field.kind) {
        FieldKind.TEXT, FieldKind.NUMBER -> TextInput(form, field, warning, enabled, onChange)
        FieldKind.SWITCH -> SwitchInput(form, field, enabled, onChange)
        FieldKind.CHOICE -> ChoiceInput(form, field, enabled, onChange)
    }
}

/** Short AmneziaWG numbers share a line, so the block stays scannable on a phone. */
private val ROWS = listOf(
    listOf(Field.JC, Field.JMIN, Field.JMAX),
    listOf(Field.S1, Field.S2),
    listOf(Field.S3, Field.S4),
)

/** Credentials: hidden by default and typed with a password keyboard, so the IME does not learn them. */
private val SECRETS = setOf(Field.PASSWORD, Field.OBFS_PASSWORD, Field.UUID, Field.REALITY_KEY, Field.PRIVATE_KEY, Field.PRESHARED_KEY)

@Composable
private fun TextInput(form: ConfigForm, field: Field, warning: UiText?, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    val error = form.errors[field]
    val hint = field.hint(form)
    val secret = field in SECRETS
    var shown by rememberSaveable(field) { mutableStateOf(false) }
    OutlinedTextField(
        value = form[field],
        onValueChange = { onChange(form.set(field, it)) },
        label = { Text(field.label()) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = when {
            error != null -> { { Text(error.resolve()) } }
            warning != null -> { { Text(warning.resolve(), color = MaterialTheme.colorScheme.tertiary) } }
            hint != null -> { { Text(hint) } }
            else -> null
        },
        textStyle = if (secret) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        if (shown) SubsIcons.EyeOff else SubsIcons.Eye,
                        contentDescription = stringResource(if (shown) R.string.subs_form_hide_secret_cd else R.string.subs_form_show_secret_cd),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                field.kind == FieldKind.NUMBER -> KeyboardType.Number
                secret -> KeyboardType.Password
                else -> KeyboardType.Uri
            },
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchInput(form: ConfigForm, field: Field, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    val on = form.isOn(field)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = on, enabled = enabled, role = Role.Switch) { onChange(form.switch(field, it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(field.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null, enabled = enabled)
        }
        if (field == Field.INSECURE && on) Hint(stringResource(R.string.subs_form_hint_insecure), error = true)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceInput(form: ConfigForm, field: Field, enabled: Boolean, onChange: (ConfigForm) -> Unit) {
    val options = form.options(field)
    val current = form[field]
    val error = form.errors[field]
    if (options.size <= CHIP_LIMIT) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(field.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { value ->
                    FilterChip(
                        selected = value == current,
                        onClick = { onChange(form.set(field, value)) },
                        enabled = enabled,
                        label = { Text(choiceLabel(field, value)) },
                    )
                }
            }
            error?.let { Hint(it.resolve(), error = true) }
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = choiceLabel(field, current),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(field.label()) },
            isError = error != null,
            supportingText = error?.let { e -> { Text(e.resolve()) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { value ->
                DropdownMenuItem(
                    text = { Text(choiceLabel(field, value)) },
                    onClick = {
                        expanded = false
                        onChange(form.set(field, value))
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun choiceLabel(field: Field, value: String): String = when {
    value.isEmpty() && (field == Field.CONGESTION || field == Field.UDP_RELAY) -> stringResource(R.string.subs_form_default)
    value.isEmpty() || value == ConfigForm.NONE -> stringResource(R.string.subs_form_none)
    field == Field.TRANSPORT -> when (value) {
        ConfigForm.TCP -> stringResource(R.string.subs_form_tcp)
        "ws" -> "WebSocket"
        "grpc" -> "gRPC"
        "httpupgrade" -> "HTTPUpgrade"
        "http" -> "HTTP/2"
        "xhttp" -> "XHTTP"
        else -> value
    }
    field == Field.SECURITY -> value.uppercase()
    else -> value
}

@Composable
private fun Field.label(): String = stringResource(
    when (this) {
        Field.SERVER -> R.string.subs_form_server
        Field.PORT -> R.string.subs_form_port
        Field.UUID -> R.string.subs_form_uuid
        Field.PASSWORD -> R.string.subs_form_password
        Field.FLOW -> R.string.subs_form_flow
        Field.VMESS_SECURITY -> R.string.subs_form_vmess_security
        Field.ALTER_ID -> R.string.subs_form_alter_id
        Field.METHOD -> R.string.subs_form_method
        Field.PLUGIN -> R.string.subs_form_plugin
        Field.PLUGIN_OPTS -> R.string.subs_form_plugin_opts
        Field.SECURITY -> R.string.subs_form_security
        Field.SNI -> R.string.subs_form_sni
        Field.FINGERPRINT -> R.string.subs_form_fingerprint
        Field.ALPN -> R.string.subs_form_alpn
        Field.INSECURE -> R.string.subs_form_insecure
        Field.REALITY_KEY -> R.string.subs_form_reality_key
        Field.REALITY_SID -> R.string.subs_form_reality_sid
        Field.TRANSPORT -> R.string.subs_form_transport
        Field.PATH -> R.string.subs_form_path
        Field.HOST -> R.string.subs_form_host
        Field.SERVICE_NAME -> R.string.subs_form_service_name
        Field.XHTTP_MODE -> R.string.subs_form_xhttp_mode
        Field.HOP_PORTS -> R.string.subs_form_hop_ports
        Field.HOP_INTERVAL -> R.string.subs_form_hop_interval
        Field.UP_MBPS -> R.string.subs_form_up
        Field.DOWN_MBPS -> R.string.subs_form_down
        Field.OBFS -> R.string.subs_form_obfs
        Field.OBFS_PASSWORD -> R.string.subs_form_obfs_password
        Field.GECKO_MIN -> R.string.subs_form_gecko_min
        Field.GECKO_MAX -> R.string.subs_form_gecko_max
        Field.CONGESTION -> R.string.subs_form_congestion
        Field.UDP_RELAY -> R.string.subs_form_udp_relay
        Field.ZERO_RTT -> R.string.subs_form_zero_rtt
        Field.USERNAME -> R.string.subs_form_username
        Field.PIN_SHA256 -> R.string.subs_form_pin
        Field.PRIVATE_KEY -> R.string.subs_form_private_key
        Field.ADDRESS -> R.string.subs_form_address
        Field.PUBLIC_KEY -> R.string.subs_form_public_key
        Field.PRESHARED_KEY -> R.string.subs_form_preshared_key
        Field.ALLOWED_IPS -> R.string.subs_form_allowed_ips
        Field.MTU -> R.string.subs_form_mtu
        Field.JC -> R.string.subs_form_jc
        Field.JMIN -> R.string.subs_form_jmin
        Field.JMAX -> R.string.subs_form_jmax
        Field.S1 -> R.string.subs_form_s1
        Field.S2 -> R.string.subs_form_s2
        Field.S3 -> R.string.subs_form_s3
        Field.S4 -> R.string.subs_form_s4
        Field.H1 -> R.string.subs_form_h1
        Field.H2 -> R.string.subs_form_h2
        Field.H3 -> R.string.subs_form_h3
        Field.H4 -> R.string.subs_form_h4
        Field.I1 -> R.string.subs_form_i1
        Field.I2 -> R.string.subs_form_i2
        Field.I3 -> R.string.subs_form_i3
        Field.I4 -> R.string.subs_form_i4
        Field.I5 -> R.string.subs_form_i5
    },
)

@Composable
private fun Field.hint(form: ConfigForm): String? = when (this) {
    Field.SNI -> stringResource(R.string.subs_form_hint_sni)
    Field.ALPN -> stringResource(if (form.protocol in QUIC) R.string.subs_form_hint_alpn_quic else R.string.subs_form_hint_alpn)
    Field.HOP_PORTS -> stringResource(R.string.subs_form_hint_hop_ports)
    Field.HOP_INTERVAL -> stringResource(R.string.subs_form_hint_hop_interval)
    Field.REALITY_SID -> stringResource(R.string.subs_form_hint_short_id)
    Field.PATH -> stringResource(R.string.subs_form_hint_path)
    Field.UP_MBPS, Field.DOWN_MBPS -> stringResource(
        if (form.protocol == FormProtocol.HYSTERIA) R.string.subs_form_hint_mbps_required else R.string.subs_form_hint_mbps,
    )
    Field.USERNAME -> stringResource(R.string.subs_form_hint_username)
    Field.PIN_SHA256 -> stringResource(R.string.subs_form_hint_pin)
    Field.ADDRESS -> stringResource(R.string.subs_form_hint_address)
    Field.ALLOWED_IPS -> stringResource(R.string.subs_form_hint_allowed_ips)
    Field.PRESHARED_KEY, Field.MTU -> stringResource(R.string.subs_form_hint_optional)
    Field.OBFS_PASSWORD -> if (form.protocol == FormProtocol.HYSTERIA) stringResource(R.string.subs_form_hint_xplus) else null
    Field.I1 -> stringResource(R.string.subs_form_hint_awg_i)
    Field.PASSWORD -> if (form.protocol == FormProtocol.SHADOWSOCKS && form[Field.METHOD].startsWith("2022-")) {
        stringResource(R.string.subs_form_hint_ss_password)
    } else {
        null
    }
    else -> null
}

private const val CHIP_LIMIT = 6

private val QUIC = setOf(FormProtocol.HYSTERIA2, FormProtocol.HYSTERIA, FormProtocol.TUIC, FormProtocol.JUICITY)
