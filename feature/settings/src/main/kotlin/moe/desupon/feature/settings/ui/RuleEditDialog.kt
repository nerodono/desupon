package moe.desupon.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.desupon.data.ruleset.RuleSetInfo
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.feature.settings.R
import moe.desupon.feature.settings.logic.RuleError
import moe.desupon.feature.settings.logic.RuleSetGroup
import moe.desupon.feature.settings.logic.RuleSetSearch
import moe.desupon.feature.settings.logic.RuleValidation
import moe.desupon.feature.settings.logic.RuleValidator
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import java.text.NumberFormat
import java.util.Locale

/** [onSave] returns the validation outcome; the dialog closes only on [RuleValidation.Valid]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditDialog(
    initial: SplitRule?,
    ruleSets: List<RuleSetInfo>,
    onDismiss: () -> Unit,
    onSave: (SplitRuleKind, String, SplitAction) -> RuleValidation,
) {
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: SplitRuleKind.DOMAIN_SUFFIX) }
    var text by rememberSaveable { mutableStateOf(initial?.value?.takeIf { initial.kind != SplitRuleKind.RULE_SET }.orEmpty()) }
    var ruleSet by rememberSaveable {
        mutableStateOf(initial?.value?.takeIf { initial.kind == SplitRuleKind.RULE_SET } ?: ruleSets.firstOrNull()?.name)
    }
    var action by rememberSaveable { mutableStateOf(initial?.action ?: SplitAction.DIRECT) }
    var touched by rememberSaveable { mutableStateOf(initial != null) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val names = remember(ruleSets) { ruleSets.mapTo(HashSet()) { it.name } }
    val value = if (kind == SplitRuleKind.RULE_SET) ruleSet.orEmpty() else text
    val validation = RuleValidator.validate(kind, value, names)
    val error = (validation as? RuleValidation.Invalid)?.error?.takeIf { touched }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.rules_add else R.string.rules_edit)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Caption(R.string.rules_kind)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SplitRuleKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) })
                    }
                }
                if (kind == SplitRuleKind.RULE_SET) {
                    Caption(R.string.rules_rule_set)
                    val info = ruleSets.firstOrNull { it.name == ruleSet }
                    RuleSetField(info, ruleSet, onClick = { picking = true })
                    when {
                        error != null -> Note(errorText(error), MaterialTheme.colorScheme.error)
                        info?.huge == true -> Note(stringResource(R.string.rule_set_huge), MaterialTheme.colorScheme.tertiary)
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            touched = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.rules_value)) },
                        isError = error != null,
                        supportingText = { Text(error?.let { errorText(it) } ?: hintText(kind)) },
                    )
                }
                Caption(R.string.rules_action)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SplitAction.entries.forEachIndexed { index, a ->
                        SegmentedButton(
                            selected = action == a,
                            onClick = { action = a },
                            shape = SegmentedButtonDefaults.itemShape(index, SplitAction.entries.size),
                            icon = {},
                            label = { Text(actionLabel(a), maxLines = 1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validation is RuleValidation.Valid,
                onClick = {
                    touched = true
                    if (onSave(kind, value, action) is RuleValidation.Valid) onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (picking) {
        RuleSetPickerDialog(
            sets = ruleSets,
            selected = ruleSet,
            onPick = {
                ruleSet = it
                touched = true
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun RuleSetField(info: RuleSetInfo?, name: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    info?.let { ruleSetTitle(it) } ?: name ?: stringResource(R.string.rule_set_pick),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info?.let { ruleSetDetail(it) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.rule_set_pick))
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** Bundled sets first, then the downloaded categories; filters on the label, code and name. */
@Composable
internal fun RuleSetPickerDialog(sets: List<RuleSetInfo>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val labels = bundledLabels()
    val groups = remember(sets, query, labels) { RuleSetSearch.groups(sets, query, labels) }
    val downloaded = remember(sets) { sets.any { it.source == RuleSetInfo.Source.DOWNLOADED } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_rule_set)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.split_clear_search))
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.rule_set_search)) },
                )
                LazyColumn(Modifier.heightIn(max = PICKER_MAX_HEIGHT)) {
                    for ((group, items) in groups) {
                        item(key = "group-$group") {
                            Text(
                                stringResource(groupTitle(group)),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(items, key = { it.name }) { info ->
                            PickerRow(info, info.name == selected, onClick = { onPick(info.name) })
                        }
                    }
                    if (groups.isEmpty()) {
                        item(key = "none") { Note(stringResource(R.string.rule_set_none_found), MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (!downloaded) {
                        item(key = "more") {
                            Text(
                                stringResource(R.string.rule_set_more_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickerRow(info: RuleSetInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(ruleSetTitle(info), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ruleSetDetail(info)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (info.huge) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.rule_set_huge),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun groupTitle(group: RuleSetGroup): Int = when (group) {
    RuleSetGroup.BUNDLED -> R.string.rule_set_group_bundled
    RuleSetGroup.GEOIP -> R.string.rule_set_group_geoip
    RuleSetGroup.GEOSITE -> R.string.rule_set_group_geosite
}

@Composable
private fun Caption(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun kindLabel(kind: SplitRuleKind): String = stringResource(
    when (kind) {
        SplitRuleKind.DOMAIN -> R.string.rule_kind_domain
        SplitRuleKind.DOMAIN_SUFFIX -> R.string.rule_kind_domain_suffix
        SplitRuleKind.DOMAIN_KEYWORD -> R.string.rule_kind_domain_keyword
        SplitRuleKind.IP_CIDR -> R.string.rule_kind_ip_cidr
        SplitRuleKind.RULE_SET -> R.string.rule_kind_rule_set
    },
)

@Composable
internal fun actionLabel(action: SplitAction): String = stringResource(
    when (action) {
        SplitAction.DIRECT -> R.string.rule_action_direct
        SplitAction.PROXY -> R.string.rule_action_proxy
        SplitAction.BLOCK -> R.string.rule_action_block
    },
)

/** Friendly names of the bundled rule-sets. */
@Composable
private fun bundledLabels(): Map<String, String> {
    val categoryRu = stringResource(R.string.rule_set_category_ru)
    val tldRu = stringResource(R.string.rule_set_tld_ru)
    val geoipRu = stringResource(R.string.rule_set_geoip_ru)
    return remember(categoryRu, tldRu, geoipRu) {
        mapOf(RuleSetStore.GEOSITE_CATEGORY_RU to categoryRu, RuleSetStore.GEOSITE_TLD_RU to tldRu, RuleSetStore.GEOIP_RU to geoipRu)
    }
}

/** A bundled set's friendly name, else the category code of a downloaded one, else the bare name. */
@Composable
internal fun ruleSetTitle(name: String, info: RuleSetInfo?): String = bundledLabels()[name] ?: info?.code ?: name

@Composable
private fun ruleSetTitle(info: RuleSetInfo): String = ruleSetTitle(info.name, info)

/** The rule-set name and its size when known. */
@Composable
private fun ruleSetDetail(info: RuleSetInfo): String? {
    val count = info.count ?: return info.name.takeIf { info.source == RuleSetInfo.Source.DOWNLOADED }
    val entries = pluralStringResource(R.plurals.rule_set_entries, count, NumberFormat.getIntegerInstance(Locale.getDefault()).format(count))
    return "${info.name} · $entries"
}

@Composable
private fun hintText(kind: SplitRuleKind): String = when (kind) {
    SplitRuleKind.DOMAIN -> stringResource(R.string.rule_hint_domain)
    SplitRuleKind.DOMAIN_SUFFIX -> stringResource(R.string.rule_hint_domain_suffix)
    SplitRuleKind.DOMAIN_KEYWORD -> stringResource(R.string.rule_hint_domain_keyword)
    SplitRuleKind.IP_CIDR -> stringResource(R.string.rule_hint_ip_cidr)
    SplitRuleKind.RULE_SET -> ""
}

@Composable
private fun errorText(error: RuleError): String = stringResource(
    when (error) {
        RuleError.EMPTY -> R.string.rule_error_empty
        RuleError.INVALID_DOMAIN -> R.string.rule_error_domain
        RuleError.INVALID_KEYWORD -> R.string.rule_error_keyword
        RuleError.INVALID_CIDR -> R.string.rule_error_cidr
        RuleError.UNKNOWN_RULE_SET -> R.string.rule_error_rule_set
    },
)

private val PICKER_MAX_HEIGHT = 420.dp
