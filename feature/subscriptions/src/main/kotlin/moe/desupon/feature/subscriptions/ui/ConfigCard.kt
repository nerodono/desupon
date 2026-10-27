package moe.desupon.feature.subscriptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.ConfigItem
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.chain.ChainMenuItems
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.ChainRowUi
import moe.desupon.feature.subscriptions.chain.DragHandle
import moe.desupon.feature.subscriptions.chain.NowBadge
import moe.desupon.feature.subscriptions.chain.StepNumber
import moe.desupon.ui.theme.tabular

internal class ConfigActions(
    val onOpen: () -> Unit,
    val onCopy: () -> Unit,
    val onQr: () -> Unit,
    val onDelete: () -> Unit,
    val onChain: (ChainOp) -> Unit = {},
)

@Composable
internal fun ConfigItem.displayTitle(): String = title ?: when {
    isList -> stringResource(R.string.subs_local_title)
    endpoint != null -> endpoint
    else -> stringResource(R.string.subs_config_untitled, id.value)
}

/** A config, or a pasted list, in the configs tab; [chain] and [current] as on [SubscriptionCard]. */
@Composable
internal fun ConfigCard(
    item: ConfigItem,
    actions: ConfigActions,
    modifier: Modifier = Modifier,
    chain: ChainRowUi? = null,
    current: Boolean = false,
) {
    Card(
        onClick = actions.onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = if (chain?.dragging == true) 8.dp else 0.dp),
    ) {
        Row {
            if (chain != null) {
                Box(Modifier.padding(top = 4.dp)) { DragHandle(chain.handle) }
            }
            ConfigBody(item, actions, chain, current, Modifier.weight(1f).padding(start = if (chain != null) 0.dp else 16.dp))
        }
    }
}

@Composable
private fun ConfigBody(item: ConfigItem, actions: ConfigActions, chain: ChainRowUi?, current: Boolean, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(top = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chain?.step?.let { StepNumber(it) }
                item.flag?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(
                    text = item.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) NowBadge()
            }
            ConfigOverflow(item, actions, chain)
        }
        Column(Modifier.padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = Format.stats(statParts(item)), style = MaterialTheme.typography.bodySmall.tabular, color = muted)
            if (item.setNames.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.subs_card_sets, item.setNames.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.unsupportedReason?.let {
                Text(
                    text = stringResource(R.string.subs_node_unsupported_reason, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun statParts(item: ConfigItem): List<String> {
    val parts = mutableListOf<String>()
    item.protocol?.let { parts += it }
    if (!item.isList) item.endpoint?.takeIf { it != item.title }?.let { parts += it }
    if (item.isList || item.nodeCount != 1) parts += pluralStringResource(R.plurals.subs_nodes, item.nodeCount, item.nodeCount)
    if (item.unsupportedCount > 0 && item.unsupportedReason == null) parts += stringResource(R.string.subs_unsupported, item.unsupportedCount)
    if (item.isList) parts += stringResource(R.string.subs_config_list_badge)
    return parts
}

@Composable
private fun ConfigOverflow(item: ConfigItem, actions: ConfigActions, chain: ChainRowUi?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(SubsIcons.More, contentDescription = stringResource(R.string.subs_config_more_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            chain?.let { ChainMenuItems(it.menu, actions.onChain) { open = false } }
            DropdownMenuItem(text = { Text(stringResource(R.string.subs_edit)) }, onClick = {
                open = false
                actions.onOpen()
            })
            if (!item.isList) {
                DropdownMenuItem(text = { Text(stringResource(R.string.subs_config_copy)) }, onClick = {
                    open = false
                    actions.onCopy()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.subs_editor_show_qr)) }, onClick = {
                    open = false
                    actions.onQr()
                })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.subs_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    actions.onDelete()
                },
            )
        }
    }
}
