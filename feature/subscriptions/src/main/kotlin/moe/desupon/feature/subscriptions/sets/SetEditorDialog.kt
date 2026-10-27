package moe.desupon.feature.subscriptions.sets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.chain.ChainMenuItems
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.ChainRowUi
import moe.desupon.feature.subscriptions.chain.DragHandle
import moe.desupon.feature.subscriptions.chain.StepNumber
import moe.desupon.feature.subscriptions.chain.chainBoxBackground
import moe.desupon.feature.subscriptions.chain.chainBoxItems
import moe.desupon.feature.subscriptions.chain.rememberChainDragState
import moe.desupon.feature.subscriptions.chain.rememberChainReorderState
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.subscriptionTitle
import moe.desupon.model.subscription.SubId

/** Name, members and priority chain of a set; for [moe.desupon.model.selection.SetId.ALL] only the chain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetEditorDialog(
    draft: SetDraft,
    subscriptions: List<MemberOption>,
    onName: (String) -> Unit,
    onToggle: (SubId) -> Unit,
    onChain: (ChainOp) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when {
        draft.priorityOnly -> R.string.subs_sets_priority_title
        draft.id == null -> R.string.subs_sets_new_title
        else -> R.string.subs_sets_edit_title
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !draft.saving) {
                            Icon(SubsIcons.Close, contentDescription = stringResource(R.string.subs_cancel))
                        }
                    },
                    actions = {
                        TextButton(onClick = onSave, enabled = !draft.saving) {
                            if (draft.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.subs_save))
                        }
                    },
                )
            },
        ) { padding ->
            val members = subscriptions.filter { it.id in draft.members }
            val byId = subscriptions.associateBy { it.id }
            val drag = rememberChainDragState(draft.chain, members.map { it.id })
            val listState = rememberLazyListState()
            val reorder = rememberChainReorderState(listState, drag)
            val haptics = LocalHapticFeedback.current
            val colors = MaterialTheme.colorScheme
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .chainBoxBackground(listState, drag, fill = colors.secondaryContainer.copy(alpha = 0.35f), border = colors.outlineVariant),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!draft.priorityOnly) {
                    item {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = onName,
                            label = { Text(stringResource(R.string.subs_sets_name)) },
                            singleLine = true,
                            enabled = !draft.saving,
                            isError = draft.error != null,
                            supportingText = draft.error?.let { e -> { Text(stringResource(e.message())) } },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    }
                    item { SectionTitle(R.string.subs_sets_members) }
                }
                if (subscriptions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.subs_sets_no_subscriptions),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (!draft.priorityOnly) {
                    items(subscriptions, key = { it.id.value }) { sub ->
                        val checked = sub.id in draft.members
                        ListItem(
                            modifier = Modifier.toggleable(
                                value = checked,
                                enabled = !draft.saving,
                                role = Role.Checkbox,
                                onValueChange = { onToggle(sub.id) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = !draft.saving) },
                            supportingContent = { Text(pluralStringResource(R.plurals.subs_nodes, sub.nodeCount, sub.nodeCount)) },
                            headlineContent = { Text(subscriptionTitle(sub.title, sub.isLocal, sub.id), maxLines = 2) },
                        )
                    }
                    if (members.isNotEmpty()) item { SectionTitle(R.string.subs_sets_priority) }
                }
                if (members.isNotEmpty()) {
                    chainBoxItems(drag, reorder, haptics, restCurrent = false, onDrop = onChain, horizontalPadding = 16.dp) { row ->
                        val sub = byId[row.id] ?: return@chainBoxItems
                        ChainMemberRow(sub, row, onChain)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChainMemberRow(sub: MemberOption, row: ChainRowUi, onChain: (ChainOp) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = if (row.dragging) 6.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DragHandle(row.handle)
            row.step?.let {
                StepNumber(it)
                Box(Modifier.size(8.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = subscriptionTitle(sub.title, sub.isLocal, sub.id),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.subs_nodes, sub.nodeCount, sub.nodeCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) {
                    Icon(SubsIcons.More, contentDescription = stringResource(R.string.subs_more_cd))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ChainMenuItems(row.menu, onChain) { open = false }
                }
            }
        }
    }
}

private fun SetDraftError.message(): Int = when (this) {
    SetDraftError.NAME_BLANK -> R.string.subs_sets_name_blank
    SetDraftError.NAME_TAKEN -> R.string.subs_sets_name_taken
}
