package moe.desupon.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.feature.settings.R
import moe.desupon.feature.settings.logic.RuleError
import moe.desupon.feature.settings.logic.RuleValidation
import moe.desupon.feature.settings.logic.RuleValidator
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind

/** [onSave] returns the validation outcome; the dialog closes only on [RuleValidation.Valid]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleEditDialog(
    initial: SplitRule?,
    ruleSetNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (SplitRuleKind, String, SplitAction) -> RuleValidation,
) {
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: SplitRuleKind.DOMAIN_SUFFIX) }
    var text by rememberSaveable { mutableStateOf(initial?.value?.takeIf { initial.kind != SplitRuleKind.RULE_SET }.orEmpty()) }
    var ruleSet by rememberSaveable {
        mutableStateOf(initial?.value?.takeIf { initial.kind == SplitRuleKind.RULE_SET } ?: ruleSetNames.firstOrNull())
    }
    var action by rememberSaveable { mutableStateOf(initial?.action ?: SplitAction.DIRECT) }
    var touched by rememberSaveable { mutableStateOf(initial != null) }

    val value = if (kind == SplitRuleKind.RULE_SET) ruleSet.orEmpty() else text
    val validation = RuleValidator.validate(kind, value, ruleSetNames)
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ruleSetNames.forEach { name ->
                            FilterChip(
                                selected = ruleSet == name,
                                onClick = { ruleSet = name },
                                label = { Text(ruleSetLabel(name) ?: name) },
                            )
                        }
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

/** Friendly name of a bundled rule-set, null for unknown names. */
@Composable
internal fun ruleSetLabel(name: String): String? = when (name) {
    RuleSetStore.GEOSITE_CATEGORY_RU -> stringResource(R.string.rule_set_category_ru)
    RuleSetStore.GEOSITE_TLD_RU -> stringResource(R.string.rule_set_tld_ru)
    RuleSetStore.GEOIP_RU -> stringResource(R.string.rule_set_geoip_ru)
    else -> null
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
