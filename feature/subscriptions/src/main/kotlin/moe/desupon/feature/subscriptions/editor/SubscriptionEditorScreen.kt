package moe.desupon.feature.subscriptions.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.resolve
import moe.desupon.feature.subscriptions.ui.ConfirmDialog
import moe.desupon.feature.subscriptions.ui.DeleteDialog
import moe.desupon.feature.subscriptions.ui.QrShareDialog
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.copySensitive
import moe.desupon.feature.subscriptions.ui.subscriptionTitle
import moe.desupon.model.subscription.SubId

/** Full-screen editor of one subscription; back asks before dropping unsaved changes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionEditorScreen(
    viewModel: SubscriptionEditorViewModel,
    id: SubId,
    onDelete: (SubId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is EditorEvent.Message -> snackbar.showSnackbar(e.text.resolve(context))
                is EditorEvent.CopyLink -> {
                    copySensitive(context, e.url)
                    snackbar.showSnackbar(resources.getString(R.string.subs_editor_link_copied))
                }
                is EditorEvent.ShowQr -> qrUrl = e.url
            }
        }
    }

    val leave = { if (draft?.dirty == true) confirmDiscard = true else viewModel.close() }
    BackHandler(onBack = leave)

    val d = draft
    val i = info
    val title = when {
        d != null && d.form.name.isNotBlank() -> d.form.name.trim()
        i != null -> subscriptionTitle(i.providerTitle?.takeIf { it.isNotBlank() }, i.isLocal, id)
        else -> ""
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "info") { InfoSection(i, onOpenLink = { openLink(context, it) }) }
            item(key = "actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!i.isLocal) {
                        OutlinedButton(onClick = viewModel::refresh, enabled = !i.updating) {
                            if (i.updating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.subs_update))
                        }
                        OutlinedButton(onClick = viewModel::copyLink) { Text(stringResource(R.string.subs_editor_copy_link)) }
                        OutlinedButton(onClick = viewModel::showQr) { Text(stringResource(R.string.subs_editor_show_qr)) }
                    }
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.subs_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item(key = "name") { NameSection(d, enabled, onName = { v -> viewModel.edit { it.name(v) } }, onClear = { viewModel.edit { it.clearName() } }) }
            if (d.form.isLocal) {
                item(key = "local") { LocalTextSection(d, enabled) { v -> viewModel.edit { it.localText(v) } } }
            } else {
                item(key = "urls") { UrlsSection(d, i.providerMirrorCount, enabled, viewModel::edit) }
                item(key = "ua") { UserAgentSection(d, i.globalUserAgent, enabled, viewModel::edit) }
                item(key = "interval") { IntervalSection(d, i.providerIntervalH, enabled) { v -> viewModel.edit { it.interval(v) } } }
            }
            item(key = "sets") { SetsSection(d, i.userSets, enabled) { s -> viewModel.edit { it.toggleSet(s) } } }
            item(key = "servers") { ServersHeader(i) }
            if (i.nodes.isEmpty()) {
                item(key = "servers-empty") { EmptyServers() }
            }
            items(i.nodes, key = { "node-" + it.id.hash }) { node ->
                NodeSwitchRow(node) { on -> viewModel.setNodeEnabled(node.id, on) }
            }
        }
    }

    qrUrl?.let { url -> QrShareDialog(url, onDismiss = { qrUrl = null }) }

    if (confirmDelete) {
        DeleteDialog(
            title = title,
            onConfirm = {
                confirmDelete = false
                onDelete(id)
            },
            onDismiss = { confirmDelete = false },
            list = i?.isLocal == true,
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.subs_editor_discard_title),
            text = stringResource(R.string.subs_editor_discard_text),
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

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Unit
    }
}
