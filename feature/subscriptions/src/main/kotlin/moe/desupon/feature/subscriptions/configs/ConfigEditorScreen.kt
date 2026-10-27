package moe.desupon.feature.subscriptions.configs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.editor.NodeSwitchRow
import moe.desupon.feature.subscriptions.editor.SetOption
import moe.desupon.feature.subscriptions.resolve
import moe.desupon.feature.subscriptions.ui.ConfirmDialog
import moe.desupon.feature.subscriptions.ui.QrShareDialog
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.copySensitive
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.tabular

/** Full-screen editor of one config, as a form or as text; back asks before dropping unsaved changes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ConfigEditorScreen(
    viewModel: ConfigEditorViewModel,
    onDelete: (SubId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var qrText by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is ConfigEvent.Message -> snackbar.showSnackbar(e.text.resolve(context))
                is ConfigEvent.Copy -> {
                    copySensitive(context, e.text)
                    snackbar.showSnackbar(resources.getString(R.string.subs_config_copied))
                }
                is ConfigEvent.ShowQr -> qrText = e.text
            }
        }
    }

    val leave = { if (draft?.dirty == true) confirmDiscard = true else viewModel.close() }
    BackHandler(onBack = leave)

    val d = draft
    val i = info
    val title = when {
        d == null -> ""
        d.isNew -> stringResource(R.string.subs_config_new_title)
        else -> i?.title ?: stringResource(R.string.subs_config_title)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(SubsIcons.Back, contentDescription = stringResource(R.string.subs_back)) }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = d != null && d.dirty && !d.saving) {
                        if (d?.saving == true) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.subs_save))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (d == null || i == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val enabled = !d.saving
        val id = d.id
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "mode") {
                ModeSwitch(d.mode, enabled) { mode ->
                    viewModel.edit { if (mode == ConfigMode.FORM) it.toForm() else it.toText() }
                }
            }
            d.formNote?.let { note -> item(key = "note") { Hint(note.resolve()) } }
            when (d.mode) {
                ConfigMode.FORM -> {
                    val form = d.form
                    if (d.isNew || form == null) {
                        item(key = "protocol") {
                            ProtocolPicker(form?.protocol, enabled) { p -> viewModel.edit { it.newForm(p) } }
                        }
                    }
                    if (form != null) {
                        item(key = "form") { ConfigFormView(form, enabled) { f -> viewModel.edit { it.form { f } } } }
                    }
                    d.error?.let { e -> item(key = "error") { Hint(e.resolve(), error = true) } }
                }
                ConfigMode.TEXT -> {
                    item(key = "text") {
                        OutlinedTextField(
                            value = d.text,
                            onValueChange = { v -> viewModel.edit { it.text(v) } },
                            label = { Text(stringResource(R.string.subs_config_text)) },
                            enabled = enabled,
                            minLines = 6,
                            maxLines = 18,
                            isError = d.error != null,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            supportingText = { Text(d.error?.resolve() ?: stringResource(R.string.subs_config_text_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            preview?.let { p -> item(key = "preview") { PreviewCard(p) } }
            if (id != null) {
                item(key = "actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = viewModel::copy) { Text(stringResource(R.string.subs_config_copy)) }
                        OutlinedButton(onClick = viewModel::showQr) { Text(stringResource(R.string.subs_editor_show_qr)) }
                        OutlinedButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.subs_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item(key = "sets") { SetChips(d.sets, i.userSets, enabled) { s -> viewModel.edit { it.toggleSet(s) } } }
            if (i.nodes.isNotEmpty()) {
                item(key = "servers") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionTitle(stringResource(R.string.subs_nodes_open))
                        Hint(stringResource(R.string.subs_editor_servers_hint))
                    }
                }
                items(i.nodes, key = { "node-" + it.id.hash }) { node ->
                    NodeSwitchRow(node) { on -> viewModel.setNodeEnabled(node.id, on) }
                }
            }
        }
    }

    qrText?.let { text ->
        QrShareDialog(
            url = text,
            onDismiss = { qrText = null },
            title = stringResource(R.string.subs_config_qr_title),
            warning = stringResource(R.string.subs_config_share_warning),
        )
    }

    if (confirmDelete && d?.id != null) {
        ConfirmDialog(
            title = stringResource(R.string.subs_config_delete_title),
            text = stringResource(R.string.subs_config_delete_text, title),
            confirm = stringResource(R.string.subs_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete(d.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.subs_editor_discard_title),
            text = stringResource(R.string.subs_config_discard_text),
            confirm = stringResource(R.string.subs_editor_discard),
            dismiss = stringResource(R.string.subs_editor_keep_editing),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                viewModel.close()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ConfigMode, enabled: Boolean, onMode: (ConfigMode) -> Unit) {
    val modes = listOf(ConfigMode.FORM to R.string.subs_config_mode_form, ConfigMode.TEXT to R.string.subs_config_mode_text)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (m, label) ->
            SegmentedButton(
                selected = m == mode,
                onClick = { if (m != mode) onMode(m) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun PreviewCard(preview: ConfigPreview) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.subs_config_preview), style = MaterialTheme.typography.titleSmall)
            preview.error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            preview.nodes.forEach { n ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = listOfNotNull(n.flag, n.name).joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(n.protocol, n.endpoint).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!n.supported) {
                        Text(
                            text = n.reason?.let { stringResource(R.string.subs_node_unsupported_reason, it) } ?: stringResource(R.string.subs_node_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetChips(selected: Set<SetId>, sets: List<SetOption>, enabled: Boolean, onToggle: (SetId) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.subs_editor_sets))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(stringResource(R.string.subs_sets_all)) })
            sets.forEach { set ->
                FilterChip(
                    selected = set.id in selected,
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
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Hint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
