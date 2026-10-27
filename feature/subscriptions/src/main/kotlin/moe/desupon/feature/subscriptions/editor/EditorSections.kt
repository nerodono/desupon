package moe.desupon.feature.subscriptions.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.feature.subscriptions.Ago
import moe.desupon.feature.subscriptions.Expiry
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.feature.subscriptions.resolve
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.readClipboard
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.UpdateInterval
import moe.desupon.ui.theme.desuStringResource
import moe.desupon.ui.theme.tabular

private typealias Edit = ((EditorDraft) -> EditorDraft) -> Unit

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoRow(label: String, value: String, error: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ago(atMs: Long, nowMs: Long): String = when (val a = Format.ago(atMs, nowMs)) {
    Ago.JustNow -> stringResource(R.string.subs_ago_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.subs_ago_minutes, a.n, a.n)
    is Ago.Hours -> pluralStringResource(R.plurals.subs_ago_hours, a.n, a.n)
    is Ago.Days -> pluralStringResource(R.plurals.subs_ago_days, a.n, a.n)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InfoSection(info: EditorInfo, onOpenLink: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.subs_editor_info), style = MaterialTheme.typography.titleSmall)
            info.traffic?.let { t ->
                InfoRow(
                    stringResource(R.string.subs_editor_traffic),
                    if (t.limited) stringResource(R.string.subs_traffic, Format.bytes(t.used), Format.bytes(t.total))
                    else stringResource(R.string.subs_traffic_unlimited, Format.bytes(t.used)),
                )
                if (t.limited) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
            }
            info.expiry?.let { e ->
                val left = when (e) {
                    Expiry.Expired -> stringResource(R.string.subs_expired)
                    is Expiry.Left -> if (e.days == 0) stringResource(R.string.subs_less_than_day) else pluralStringResource(R.plurals.subs_days_left, e.days, e.days)
                }
                val date = info.expireEpochSec?.let { Format.date(it) }
                InfoRow(stringResource(R.string.subs_editor_expiry), if (date != null) stringResource(R.string.subs_editor_expiry_value, date, left) else left, error = info.expiringSoon)
            }
            InfoRow(
                stringResource(R.string.subs_editor_last_update),
                info.lastOkAtMs?.let { ago(it, info.nowMs) } ?: stringResource(R.string.subs_editor_never),
            )
            info.error?.let { err ->
                val at = info.lastAttemptAtMs?.let { ago(it, info.nowMs) }
                InfoRow(
                    stringResource(R.string.subs_editor_last_error),
                    if (at != null) stringResource(R.string.subs_editor_error_value, err.resolve(), at) else err.resolve(),
                    error = true,
                )
            }
            info.announce?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (info.supportUrl != null || info.webPageUrl != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.supportUrl?.let { url -> TextButton(onClick = { onOpenLink(url) }) { Text(stringResource(R.string.subs_editor_support)) } }
                    info.webPageUrl?.let { url -> TextButton(onClick = { onOpenLink(url) }) { Text(stringResource(R.string.subs_editor_web_page)) } }
                }
            }
        }
    }
}

@Composable
internal fun NameSection(draft: EditorDraft, enabled: Boolean, onName: (String) -> Unit, onClear: () -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.subs_editor_name))
        OutlinedTextField(
            value = draft.form.name,
            onValueChange = onName,
            singleLine = true,
            enabled = enabled,
            placeholder = { Text(draft.providerTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.subs_editor_name_none)) },
            trailingIcon = if (draft.form.name.isNotEmpty()) {
                { IconButton(onClick = onClear, enabled = enabled) { Icon(SubsIcons.Close, contentDescription = stringResource(R.string.subs_editor_name_clear)) } }
            } else {
                null
            },
            supportingText = { Text(stringResource(R.string.subs_rename_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun LocalTextSection(draft: EditorDraft, enabled: Boolean, onText: (String) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.subs_editor_local))
        OutlinedTextField(
            value = draft.form.localText.orEmpty(),
            onValueChange = onText,
            enabled = enabled,
            minLines = 6,
            maxLines = 16,
            isError = draft.localTextError != null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            supportingText = { Text(draft.localTextError?.resolve() ?: stringResource(R.string.subs_editor_local_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun UrlsSection(draft: EditorDraft, providerMirrors: Int, enabled: Boolean, edit: Edit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.subs_editor_urls))
        Hint(stringResource(R.string.subs_editor_urls_hint))
        draft.form.urls.forEachIndexed { index, row ->
            UrlRowView(
                row = row,
                index = index,
                last = index == draft.form.urls.lastIndex,
                revealed = row.key in draft.revealed,
                error = draft.urlErrors[row.key],
                enabled = enabled,
                edit = edit,
            )
        }
        draft.urlsError?.let { Hint(it.resolve(), error = true) }
        if (providerMirrors > 0) Hint(pluralStringResource(R.plurals.subs_editor_provider_mirrors, providerMirrors, providerMirrors))
        OutlinedTextField(
            value = draft.mirrorInput,
            onValueChange = { v -> edit { it.mirrorInput(v) } },
            label = { Text(stringResource(if (draft.form.urls.isEmpty()) R.string.subs_editor_url_new else R.string.subs_editor_mirror_new)) },
            placeholder = { Text("https://…") },
            singleLine = true,
            enabled = enabled,
            isError = draft.mirrorError != null,
            supportingText = draft.mirrorError?.let { e -> { Text(e.resolve()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { edit { it.addMirror() } }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = enabled, onClick = {
                val text = readClipboard(context)?.trim()
                if (text.isNullOrEmpty()) {
                    edit { it.copy(mirrorError = UiText.Res(R.string.subs_add_clipboard_empty)) }
                } else {
                    edit { it.mirrorInput(text) }
                }
            }) { Text(stringResource(R.string.subs_add_paste)) }
            TextButton(enabled = enabled && draft.mirrorInput.isNotBlank(), onClick = { edit { it.addMirror() } }) {
                Text(stringResource(R.string.subs_editor_add_url))
            }
        }
    }
}

@Composable
private fun UrlRowView(row: UrlRow, index: Int, last: Boolean, revealed: Boolean, error: UiText?, enabled: Boolean, edit: Edit) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (index == 0) stringResource(R.string.subs_editor_url_primary) else stringResource(R.string.subs_editor_url_mirror, index),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (revealed) row.url.raw() else row.masked,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = if (revealed) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.cleartext) Hint(stringResource(R.string.subs_editor_url_cleartext), error = true)
                error?.let { Hint(it.resolve(), error = true) }
            }
            IconButton(onClick = { edit { it.toggleReveal(row.key) } }) {
                Icon(
                    if (revealed) SubsIcons.EyeOff else SubsIcons.Eye,
                    contentDescription = stringResource(if (revealed) R.string.subs_editor_url_hide else R.string.subs_editor_url_reveal),
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(SubsIcons.More, contentDescription = stringResource(R.string.subs_editor_url_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (index > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.subs_editor_url_make_primary)) }, onClick = {
                            menu = false
                            edit { it.move(row.key, -index) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.subs_move_up)) }, onClick = {
                            menu = false
                            edit { it.move(row.key, -1) }
                        })
                    }
                    if (!last) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.subs_move_down)) }, onClick = {
                            menu = false
                            edit { it.move(row.key, 1) }
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            edit { it.remove(row.key) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UaPreset.title(): String = when (this) {
    UaPreset.DEFAULT -> stringResource(R.string.subs_editor_ua_default)
    UaPreset.CUSTOM -> stringResource(R.string.subs_editor_ua_custom)
    else -> label.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserAgentSection(draft: EditorDraft, globalUserAgent: String, enabled: Boolean, edit: Edit) {
    var expanded by remember { mutableStateOf(false) }
    val selection = draft.form.userAgent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.subs_user_agent))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = selection.preset.title(),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                UaPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.title()) },
                        onClick = {
                            expanded = false
                            edit { it.userAgent(preset) }
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        if (selection.preset == UaPreset.CUSTOM) {
            OutlinedTextField(
                value = selection.custom,
                onValueChange = { v -> edit { it.customUserAgent(v) } },
                label = { Text(stringResource(R.string.subs_editor_ua_custom_field)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Hint(stringResource(R.string.subs_editor_ua_sent, selection.value ?: globalUserAgent))
    }
}

@Composable
private fun intervalLabel(value: UpdateInterval?, providerH: Int?): String = when (value) {
    null -> if (providerH != null && providerH > 0) {
        stringResource(R.string.subs_editor_interval_provider, providerH)
    } else {
        stringResource(R.string.subs_editor_interval_provider_default, SubscriptionRepository.DEFAULT_INTERVAL_H)
    }
    UpdateInterval.Manual -> stringResource(R.string.subs_editor_interval_manual)
    is UpdateInterval.Hours -> stringResource(R.string.subs_editor_interval_hours, value.hours)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntervalSection(draft: EditorDraft, providerH: Int?, enabled: Boolean, onPick: (UpdateInterval?) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.subs_editor_interval))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Intervals.options(draft.initial.interval).forEach { option ->
                FilterChip(
                    selected = draft.form.interval == option,
                    onClick = { onPick(option) },
                    enabled = enabled,
                    label = { Text(intervalLabel(option, providerH)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetsSection(draft: EditorDraft, sets: List<SetOption>, enabled: Boolean, onToggle: (SetId) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.subs_editor_sets))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(stringResource(R.string.subs_sets_all)) })
            sets.forEach { set ->
                FilterChip(
                    selected = set.id in draft.form.sets,
                    onClick = { onToggle(set.id) },
                    enabled = enabled,
                    label = { Text(set.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (sets.isEmpty()) Hint(stringResource(R.string.subs_editor_sets_none))
    }
}

@Composable
internal fun ServersHeader(info: EditorInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(stringResource(R.string.subs_nodes_open))
        if (info.nodes.isNotEmpty()) {
            Text(
                stringResource(R.string.subs_editor_servers_enabled, info.enabledCount, info.nodes.size),
                style = MaterialTheme.typography.bodyMedium.tabular,
            )
            Hint(stringResource(R.string.subs_editor_servers_hint))
        }
    }
}

@Composable
internal fun EmptyServers() {
    Text(
        text = desuStringResource(R.string.subs_nodes_empty, R.string.subs_nodes_empty_desu),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
internal fun NodeSwitchRow(node: EditorNode, onToggle: (Boolean) -> Unit) {
    val status = when {
        node.supported -> null
        node.reason != null -> stringResource(R.string.subs_node_unsupported_reason, node.reason)
        else -> stringResource(R.string.subs_node_unsupported)
    }
    val checked = node.supported && node.enabled
    ListItem(
        modifier = Modifier.toggleable(value = checked, enabled = node.supported, role = Role.Switch, onValueChange = onToggle),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Text(
                text = node.flag ?: "",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
        },
        headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = if (status != null) "${node.protocol} · $status" else node.protocol,
                color = if (node.supported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = node.supported) },
    )
}
