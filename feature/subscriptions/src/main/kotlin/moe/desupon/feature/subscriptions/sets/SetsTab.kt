package moe.desupon.feature.subscriptions.sets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.subscriptionTitle
import moe.desupon.model.selection.SetId
import moe.desupon.ui.theme.tabular

internal class SetActions(
    val onActivate: (SetId) -> Unit,
    val onEdit: (SetId) -> Unit,
    val onDelete: (SetCard) -> Unit,
    val onMove: (SetId, Int) -> Unit,
)

@Composable
internal fun SetsTab(state: SetsUiState, actions: SetActions, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    if (state.loading) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.subs_sets_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.sets, key = { it.id.value }) { set -> SetCardView(set, actions) }
    }
}

@Composable
internal fun setName(set: SetCard): String = if (set.isAll) stringResource(R.string.subs_sets_all) else set.name

@Composable
private fun SetCardView(set: SetCard, actions: SetActions) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = { actions.onActivate(set.id) },
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.RadioButton
            selected = set.active
        },
        colors = CardDefaults.cardColors(containerColor = if (set.active) colors.secondaryContainer else colors.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = set.active, onClick = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = setName(set),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (set.active) ActiveBadge()
                }
                if (set.isAll) {
                    Text(
                        text = stringResource(R.string.subs_sets_all_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    text = summary(set),
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = colors.onSurfaceVariant,
                )
                if (set.chainMembers.isNotEmpty()) {
                    Text(
                        text = chainSummary(set),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SetMenu(set, actions)
        }
    }
}

@Composable
private fun summary(set: SetCard): String {
    val subs = pluralStringResource(R.plurals.subs_sets_subscriptions, set.subscriptionCount, set.subscriptionCount)
    val servers = if (set.enabledNodeCount == set.supportedNodeCount) {
        pluralStringResource(R.plurals.subs_nodes, set.supportedNodeCount, set.supportedNodeCount)
    } else {
        pluralStringResource(R.plurals.subs_sets_servers_partial, set.supportedNodeCount, set.enabledNodeCount, set.supportedNodeCount)
    }
    return "$subs · $servers"
}

@Composable
private fun chainSummary(set: SetCard): String {
    val steps = set.chainMembers.map { subscriptionTitle(it.title, it.isLocal, it.id) } + stringResource(R.string.subs_chain_rest_short)
    return stringResource(R.string.subs_chain_summary, steps.joinToString(" → "))
}

@Composable
private fun ActiveBadge() {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Text(
            text = stringResource(R.string.subs_sets_active),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SetMenu(set: SetCard, actions: SetActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(SubsIcons.More, contentDescription = stringResource(R.string.subs_sets_more_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.subs_edit)) }, onClick = {
                open = false
                actions.onEdit(set.id)
            })
            if (set.canMoveUp) {
                DropdownMenuItem(text = { Text(stringResource(R.string.subs_move_up)) }, onClick = {
                    open = false
                    actions.onMove(set.id, -1)
                })
            }
            if (set.canMoveDown) {
                DropdownMenuItem(text = { Text(stringResource(R.string.subs_move_down)) }, onClick = {
                    open = false
                    actions.onMove(set.id, 1)
                })
            }
            if (!set.isAll) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.subs_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        open = false
                        actions.onDelete(set)
                    },
                )
            }
        }
    }
}
