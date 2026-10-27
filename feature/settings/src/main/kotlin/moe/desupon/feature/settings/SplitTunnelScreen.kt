package moe.desupon.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.data.settings.PrefixRule
import moe.desupon.feature.settings.logic.AppRowState
import moe.desupon.feature.settings.logic.PrefixError
import moe.desupon.feature.settings.logic.PrefixValidation
import moe.desupon.feature.settings.logic.RowNote
import moe.desupon.feature.settings.ui.RuleEditDialog
import moe.desupon.feature.settings.ui.SettingsCard
import moe.desupon.feature.settings.ui.SettingsGlyphs
import moe.desupon.feature.settings.ui.SwitchRow
import moe.desupon.feature.settings.ui.actionLabel
import moe.desupon.feature.settings.ui.kindLabel
import moe.desupon.feature.settings.ui.ruleSetLabel
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import org.koin.compose.viewmodel.koinViewModel

private enum class SplitTab { APPS, RULES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SplitTunnelViewModel = koinViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(SplitTab.APPS) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.split_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.split_reset_ru)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmReset = true
                                    },
                                )
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    Tab(
                        selected = tab == SplitTab.APPS,
                        onClick = { tab = SplitTab.APPS },
                        text = { Text(stringResource(R.string.split_tab_apps)) },
                    )
                    Tab(
                        selected = tab == SplitTab.RULES,
                        onClick = { tab = SplitTab.RULES },
                        text = { Text(stringResource(R.string.split_tab_rules)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == SplitTab.RULES) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.rules_add)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + FAB_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (tab) {
                SplitTab.APPS -> appsTab(apps, viewModel)
                SplitTab.RULES -> rulesTab(rules, viewModel, onEdit = { editing = it.id })
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.split_reset_ru)) },
            text = {
                Text(stringResource(if (tab == SplitTab.APPS) R.string.split_reset_apps_confirm else R.string.split_reset_rules_confirm))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tab == SplitTab.APPS) viewModel.resetApps() else viewModel.resetRules()
                    confirmReset = false
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    val editedRule = editing?.let { id -> rules.firstOrNull { it.id == id } }
    if (creating || editedRule != null) {
        RuleEditDialog(
            initial = editedRule,
            ruleSetNames = viewModel.ruleSetNames,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { kind, value, action ->
                viewModel.saveRule(editedRule?.id, kind, value, action)
            },
        )
    }
}

private fun LazyListScope.appsTab(state: SplitAppsState, viewModel: SplitTunnelViewModel) {
    item(key = "mode") { ModeCard(state.mode, viewModel::setMode) }
    item(key = "self") {
        Text(
            stringResource(R.string.split_self_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (state.mode == SplitMode.ALL) return
    if (state.mode == SplitMode.ALL_EXCEPT_SELECTED) {
        item(key = "prefixes") {
            PrefixCard(
                rule = state.prefixRule,
                onEnabled = viewModel::setPrefixBypassEnabled,
                onAdd = viewModel::addPrefix,
                onRemove = viewModel::removePrefix,
            )
        }
    }
    item(key = "search") {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.split_clear_search))
                    }
                }
            },
            placeholder = { Text(stringResource(R.string.split_search)) },
        )
    }
    item(key = "filters") {
        SettingsCard {
            SwitchRow(
                title = stringResource(R.string.split_show_system),
                checked = state.showSystem,
                onCheckedChange = viewModel::setShowSystem,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.split_selected_count, state.checkedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.setAllVisible(true) }) { Text(stringResource(R.string.split_select_all)) }
                TextButton(onClick = { viewModel.setAllVisible(false) }) { Text(stringResource(R.string.split_deselect_all)) }
            }
        }
    }
    when {
        state.loading -> item(key = "loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.visible.isEmpty() -> item(key = "empty") {
            Text(
                stringResource(R.string.split_no_apps),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        else -> items(state.visible, key = { it.app.packageName }) { row ->
            AppRow(
                row = row,
                onToggle = { viewModel.setSelected(row.app.packageName, it) },
                onClearOverride = { viewModel.clearForceTunnel(row.app.packageName) },
                loadIcon = viewModel::icon,
            )
        }
    }
}

@Composable
private fun ModeCard(mode: SplitMode, onSelect: (SplitMode) -> Unit) {
    val options = listOf(
        Triple(SplitMode.ALL, R.string.split_mode_all, R.string.split_mode_all_summary),
        Triple(SplitMode.ONLY_SELECTED, R.string.split_mode_only, R.string.split_mode_only_summary),
        Triple(SplitMode.ALL_EXCEPT_SELECTED, R.string.split_mode_except, R.string.split_mode_except_summary),
    )
    SettingsCard {
        options.forEach { (option, title, summary) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == option, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    row: AppRowState,
    onToggle: (Boolean) -> Unit,
    onClearOverride: () -> Unit,
    loadIcon: suspend (String, Int) -> ImageBitmap?,
) {
    val app = row.app
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.packageName, sizePx) { value = loadIcon(app.packageName, sizePx) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        } else {
            Icon(
                SettingsGlyphs.App,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val note = row.note) {
                is RowNote.ByPrefix -> Text(
                    stringResource(R.string.split_app_by_prefix, note.prefix),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                RowNote.ForcedTunnel -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.split_app_forced),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    TextButton(
                        onClick = onClearOverride,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(stringResource(R.string.split_app_forced_reset), style = MaterialTheme.typography.labelSmall)
                    }
                }
                null -> Unit
            }
        }
        Checkbox(checked = row.checked, onCheckedChange = null)
    }
}

@Composable
private fun PrefixCard(
    rule: PrefixRule,
    onEnabled: (Boolean) -> Unit,
    onAdd: (String) -> PrefixValidation,
    onRemove: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.split_prefix_title),
            subtitle = stringResource(R.string.split_prefix_summary),
            checked = rule.enabled,
            onCheckedChange = onEnabled,
        )
        val alpha = if (rule.enabled) 1f else DISABLED_ALPHA
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).alpha(alpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rule.prefixes.sorted().forEach { prefix ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(prefix) },
                    label = { Text("$prefix*") },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.split_prefix_remove, prefix),
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
            AssistChip(
                onClick = { adding = true },
                label = { Text(stringResource(R.string.split_prefix_add)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
    if (adding) AddPrefixDialog(onDismiss = { adding = false }, onAdd = onAdd)
}

@Composable
private fun AddPrefixDialog(onDismiss: () -> Unit, onAdd: (String) -> PrefixValidation) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<PrefixError?>(null) }
    val submit = {
        when (val result = onAdd(text)) {
            is PrefixValidation.Valid -> onDismiss()
            is PrefixValidation.Invalid -> error = result.error
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_prefix_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                singleLine = true,
                isError = error != null,
                placeholder = { Text("com.example.") },
                supportingText = { Text(error?.let { prefixErrorText(it) } ?: stringResource(R.string.split_prefix_hint)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun prefixErrorText(error: PrefixError): String = stringResource(
    when (error) {
        PrefixError.EMPTY -> R.string.rule_error_empty
        PrefixError.INVALID -> R.string.split_prefix_error_invalid
        PrefixError.MISSING_DOT -> R.string.split_prefix_error_dot
        PrefixError.DUPLICATE -> R.string.split_prefix_error_duplicate
    },
)


private fun LazyListScope.rulesTab(rules: List<SplitRule>, viewModel: SplitTunnelViewModel, onEdit: (SplitRule) -> Unit) {
    item(key = "hint") {
        Text(
            stringResource(R.string.rules_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (rules.isEmpty()) {
        item(key = "empty") {
            Text(
                stringResource(R.string.rules_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        return
    }
    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
        RuleCard(
            rule = rule,
            canMoveUp = index > 0,
            canMoveDown = index < rules.lastIndex,
            onEnabled = { viewModel.setRuleEnabled(rule, it) },
            onMove = { viewModel.moveRule(rule, it) },
            onEdit = { onEdit(rule) },
            onDelete = { viewModel.deleteRule(rule) },
        )
    }
}

@Composable
private fun RuleCard(
    rule: SplitRule,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEnabled: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val contentAlpha = if (rule.enabled) 1f else DISABLED_ALPHA
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val title = if (rule.kind == SplitRuleKind.RULE_SET) ruleSetLabel(rule.value) ?: rule.value else rule.value
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${kindLabel(rule.kind)} · ${actionLabel(rule.action)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onEnabled)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.rules_move_up))
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.rules_move_down))
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.rules_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.rules_delete))
            }
        }
    }
}

private val ICON_SIZE = 40.dp
private val FAB_CLEARANCE = 88.dp
private const val DISABLED_ALPHA = 0.5f
