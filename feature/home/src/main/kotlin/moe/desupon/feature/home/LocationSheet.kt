package moe.desupon.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import moe.desupon.data.location.ProbeState
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.ui.theme.DesuponGlyphs
import moe.desupon.ui.theme.DesuponTheme
import moe.desupon.ui.theme.desuStringResource
import moe.desupon.ui.theme.tabular

private const val OTHER_KEY = "\u0000other"
private const val DEAD_ALPHA = 0.45f
private const val RECHECK_ALPHA = 0.5f

/** Choosing a location closes the sheet; set chips, refresh and accordions keep it open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationSheet(
    state: LocationSheetState?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelectSet: (SetId) -> Unit,
    onManageSets: (() -> Unit)?,
    onSelect: (LocationChoice) -> Unit,
) {
    val sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LocationSheetContent(
            state = state,
            onRefresh = onRefresh,
            onSelectSet = onSelectSet,
            onManageSets = onManageSets?.let { manage -> { close(manage) } },
            onSelect = { choice -> close { onSelect(choice) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocationSheetContent(
    state: LocationSheetState?,
    onRefresh: () -> Unit,
    onSelectSet: (SetId) -> Unit,
    onManageSets: (() -> Unit)?,
    onSelect: (LocationChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<Set<String>?>(null) }
    if (expanded == null && state != null) {
        expanded = state.countries.filter { it.containsSelected }.mapTo(HashSet()) { it.iso } +
            (if (state.other.any { it.selected }) setOf(OTHER_KEY) else emptySet())
    }
    val open = expanded.orEmpty()
    fun toggle(key: String) {
        expanded = if (key in open) open - key else open + key
    }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = desuStringResource(R.string.home_sheet_title, R.string.home_sheet_title_desu),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state?.refreshing == true) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(HomeIcons.Refresh, stringResource(R.string.home_sheet_refresh), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (state == null) return@LazyColumn

        item(key = "sets") {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sets.forEach { chip ->
                    FilterChip(
                        selected = chip.selected,
                        onClick = { if (!chip.selected) onSelectSet(chip.id) },
                        label = { Text(chip.title ?: stringResource(R.string.home_set_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                if (onManageSets != null) {
                    AssistChip(
                        onClick = onManageSets,
                        label = { Icon(HomeIcons.Plus, stringResource(R.string.home_sheet_add_set), Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }

        if (state.whitelistBanner) {
            item(key = "whitelist") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = desuStringResource(R.string.home_sheet_whitelist, R.string.home_sheet_whitelist_desu),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onSelect(LocationChoice.Any) }) { Text(stringResource(R.string.home_location_any)) }
                    }
                }
            }
        }

        item(key = "any") { AnyRow(state.anySelected, state.chain) { onSelect(LocationChoice.Any) } }

        state.pinnedOutside?.let { pin ->
            item(key = "pin") {
                NodeItem(pin, outside = true, modifier = Modifier.padding(horizontal = 16.dp)) { onSelect(LocationChoice.Node(pin.id)) }
            }
        }

        state.countries.forEach { country ->
            val isOpen = country.iso in open
            item(key = "c:" + country.iso) {
                CountryItem(country, isOpen, onToggle = { toggle(country.iso) }) { onSelect(LocationChoice.country(country.iso)) }
            }
            if (isOpen) {
                item(key = "n:" + country.iso) { NodeGroup(country.nodes, onSelect) }
            }
        }

        if (state.other.isNotEmpty()) {
            val isOpen = OTHER_KEY in open
            item(key = "other") { OtherHeader(isOpen) { toggle(OTHER_KEY) } }
            if (isOpen) item(key = "n:other") { NodeGroup(state.other, onSelect) }
        }
    }
}

@Composable
private fun AnyRow(selected: Boolean, chain: ChainLine?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)) else null,
        onClick = onClick,
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.width(8.dp))
            Icon(HomeIcons.Bolt, null, Modifier.size(24.dp), tint = colors.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(stringResource(R.string.home_location_any), style = MaterialTheme.typography.titleMedium)
                if (chain != null) {
                    Text(
                        text = chainText(chain),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryItem(country: CountryRow, expanded: Boolean, onToggle: () -> Unit, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 64.dp)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = country.selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        FlagBox(country.flag)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(country.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CountrySubtitle(country)
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = HomeIcons.Chevron,
                contentDescription = stringResource(if (expanded) R.string.home_sheet_collapse else R.string.home_sheet_expand),
                modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountrySubtitle(country: CountryRow) {
    val status = statusText(country.state, country.bestRttMs)
    val relay = if (country.relay) stringResource(R.string.home_sheet_relays) else null
    val text = listOfNotNull(relay, status?.first).joinToString(PART_SEPARATOR).ifEmpty { return }
    val rechecking = country.state == ProbeState.CHECKING && country.bestRttMs != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = if (relay != null) MaterialTheme.colorScheme.onSurfaceVariant else status?.second ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (rechecking) {
            Spacer(Modifier.width(6.dp))
            RecheckSpinner()
        }
    }
}

/** Text and color for a probe outcome; null when there is nothing worth showing. A re-probed value is dimmed, not replaced. */
@Composable
private fun statusText(state: ProbeState, rttMs: Int?): Pair<String, Color>? {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        ProbeState.OK -> rttMs?.let { stringResource(R.string.home_rtt, it) to rttColor(it) }
        ProbeState.CHECKING ->
            rttMs?.let { stringResource(R.string.home_rtt, it) to rttColor(it).copy(alpha = RECHECK_ALPHA) }
                ?: (stringResource(R.string.home_sheet_checking) to colors.onSurfaceVariant)
        ProbeState.DEAD -> stringResource(R.string.home_sheet_dead) to colors.error
        ProbeState.UNKNOWN -> stringResource(R.string.home_sheet_unknown) to colors.onSurfaceVariant
    }
}

@Composable
private fun rttColor(ms: Int): Color = when (rttLevel(ms)) {
    RttLevel.GOOD -> MaterialTheme.colorScheme.primary
    RttLevel.FAIR -> MaterialTheme.colorScheme.tertiary
    RttLevel.SLOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun OtherHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(40.dp))
        FlagBox("🏳")
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.home_sheet_other), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = HomeIcons.Chevron,
                contentDescription = stringResource(if (expanded) R.string.home_sheet_collapse else R.string.home_sheet_expand),
                modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeGroup(nodes: List<NodeRow>, onSelect: (LocationChoice) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, bottom = 8.dp).animateContentSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            nodes.forEach { n -> NodeItem(n, outside = false) { onSelect(LocationChoice.Node(n.id)) } }
        }
    }
}

@Composable
private fun NodeItem(node: NodeRow, outside: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val dead = node.state == ProbeState.DEAD
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp)
            .alpha(if (dead) DEAD_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = node.selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        if (outside) {
            FlagBox(node.flag)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val from = node.subscription?.let { stringResource(R.string.home_server_from, subscriptionTitle(it)) }
            val note = listOfNotNull(if (outside) stringResource(R.string.home_sheet_outside_set) else null, from).joinToString(" · ")
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            dead -> Icon(HomeIcons.Cross, stringResource(R.string.home_sheet_node_dead), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            node.state == ProbeState.OK && node.rttMs != null -> RttText(node.rttMs, rechecking = false)
            node.state == ProbeState.CHECKING && node.rttMs != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                RecheckSpinner()
                Spacer(Modifier.width(6.dp))
                RttText(node.rttMs, rechecking = true)
            }
            node.state == ProbeState.CHECKING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun RttText(ms: Int, rechecking: Boolean) {
    val color = rttColor(ms)
    Text(
        stringResource(R.string.home_rtt, ms),
        style = MaterialTheme.typography.labelLarge.tabular,
        color = if (rechecking) color.copy(alpha = RECHECK_ALPHA) else color,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun RecheckSpinner() {
    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun FlagBox(flag: String?, icon: ImageVector = DesuponGlyphs.Sprout) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (flag != null) {
            Text(flag, fontSize = 24.sp)
        } else {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun subscriptionTitle(label: SubscriptionLabel): String =
    label.title ?: stringResource(R.string.home_subscription_untitled, label.ordinal)

@Preview(heightDp = 900)
@Composable
private fun LocationSheetPreview() {
    fun n(name: String, state: ProbeState, rtt: Int? = null, selected: Boolean = false) =
        NodeRow(NodeId(name), name, null, state, rtt, selected, SubscriptionLabel("Сад", 1))
    DesuponTheme(desuMode = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            LocationSheetContent(
                state = LocationSheetState(
                    sets = listOf(SetChip(SetId.ALL, null, true), SetChip(SetId(1), "Работа", false)),
                    refreshing = true,
                    whitelistBanner = true,
                    anySelected = false,
                    pinnedOutside = null,
                    countries = listOf(
                        CountryRow("RU", "Россия", flagEmoji("RU"), true, ProbeState.OK, 40, false, emptyList()),
                        CountryRow(
                            "NL", "Нидерланды", flagEmoji("NL"), false, ProbeState.OK, 131, false,
                            listOf(n("Amsterdam 1", ProbeState.OK, 131, selected = true), n("Amsterdam 2", ProbeState.DEAD)),
                        ),
                        CountryRow("DE", "Германия", flagEmoji("DE"), false, ProbeState.OK, 520, true, emptyList()),
                        CountryRow("FI", "Финляндия", flagEmoji("FI"), false, ProbeState.CHECKING, 210, false, emptyList()),
                        CountryRow("JP", "Япония", flagEmoji("JP"), false, ProbeState.DEAD, null, false, emptyList()),
                    ),
                    other = listOf(n("Mystery", ProbeState.UNKNOWN)),
                    chain = ChainLine(listOf(SubscriptionLabel("Финка", 1), SubscriptionLabel(null, 2))),
                ),
                onRefresh = {},
                onSelectSet = {},
                onManageSets = {},
                onSelect = {},
            )
        }
    }
}
