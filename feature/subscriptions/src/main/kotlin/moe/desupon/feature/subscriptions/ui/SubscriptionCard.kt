package moe.desupon.feature.subscriptions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.Expiry
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.SubscriptionItem
import moe.desupon.feature.subscriptions.chain.ChainMenuItems
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.ChainRowUi
import moe.desupon.feature.subscriptions.chain.DragHandle
import moe.desupon.feature.subscriptions.chain.NowBadge
import moe.desupon.feature.subscriptions.chain.StepNumber
import moe.desupon.feature.subscriptions.resolve
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.tabular

internal class CardActions(
    val onOpen: () -> Unit,
    val onUpdate: () -> Unit,
    val onDelete: () -> Unit,
    val onChain: (ChainOp) -> Unit = {},
)

@Composable
internal fun SubscriptionItem.displayTitle(): String = subscriptionTitle(title, isLocal, id)

@Composable
internal fun subscriptionTitle(title: String?, isLocal: Boolean, id: SubId): String =
    title ?: if (isLocal) stringResource(R.string.subs_local_title) else stringResource(R.string.subs_untitled, id.value)

/** [chain] places the card in a priority box: drag handle, step number and chain menu entries; [current] marks the step in use. */
@Composable
internal fun SubscriptionCard(
    item: SubscriptionItem,
    actions: CardActions,
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
            CardBody(item, actions, chain, current, Modifier.weight(1f).padding(start = if (chain != null) 0.dp else 16.dp))
        }
    }
}

@Composable
private fun CardBody(item: SubscriptionItem, actions: CardActions, chain: ChainRowUi?, current: Boolean, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chain?.step?.let { StepNumber(it) }
                Text(
                    text = item.displayTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) NowBadge()
            }
            Overflow(actions, chain)
        }
        Column(Modifier.padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = Format.stats(statParts(item)), style = MaterialTheme.typography.bodySmall.tabular, color = muted)
            UsageRow(item)
            if (item.setNames.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.subs_card_sets, item.setNames.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.error?.let {
                Text(text = it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (item.cleartext) {
                Text(
                    text = stringResource(R.string.subs_cleartext_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!item.isLocal) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = actions.onUpdate, enabled = !item.updating) {
                    if (item.updating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.subs_update))
                }
            }
        }
    }
}

/** Usage text and the expiry badge share a row; the progress bar for a limited plan sits above it. */
@Composable
private fun UsageRow(item: SubscriptionItem) {
    val traffic = item.traffic
    val expiry = item.expiry
    if (traffic == null && expiry == null) return
    if (traffic?.limited == true) {
        LinearProgressIndicator(progress = { traffic.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when {
                traffic == null -> ""
                traffic.limited -> stringResource(R.string.subs_traffic, Format.bytes(traffic.used), Format.bytes(traffic.total))
                else -> stringResource(R.string.subs_traffic_unlimited, Format.bytes(traffic.used))
            },
            style = MaterialTheme.typography.bodySmall.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        expiry?.let { ExpiryBadge(it, item.expiringSoon) }
    }
}

@Composable
private fun statParts(item: SubscriptionItem): List<String> {
    val parts = mutableListOf(pluralStringResource(R.plurals.subs_nodes, item.nodeCount, item.nodeCount))
    if (item.unsupportedCount > 0) parts += stringResource(R.string.subs_unsupported, item.unsupportedCount)
    item.unreadableCount?.takeIf { it > 0 }?.let { parts += stringResource(R.string.subs_unreadable, it) }
    if (item.isLocal) parts += stringResource(R.string.subs_local_badge)
    if (item.mirrorCount > 0) parts += pluralStringResource(R.plurals.subs_card_mirrors, item.mirrorCount, item.mirrorCount)
    if (item.userAgent != null) parts += stringResource(R.string.subs_user_agent_custom)
    return parts
}

@Composable
private fun ExpiryBadge(expiry: Expiry, warn: Boolean) {
    val text = when (expiry) {
        Expiry.Expired -> stringResource(R.string.subs_expired)
        is Expiry.Left ->
            if (expiry.days == 0) stringResource(R.string.subs_less_than_day)
            else pluralStringResource(R.plurals.subs_days_left, expiry.days, expiry.days)
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (warn) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (warn) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.tabular,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Overflow(actions: CardActions, chain: ChainRowUi?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(SubsIcons.More, contentDescription = stringResource(R.string.subs_more_cd))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            chain?.let { ChainMenuItems(it.menu, actions.onChain) { open = false } }
            DropdownMenuItem(text = { Text(stringResource(R.string.subs_edit)) }, onClick = {
                open = false
                actions.onOpen()
            })
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
