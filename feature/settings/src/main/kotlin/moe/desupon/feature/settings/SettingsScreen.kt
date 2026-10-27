package moe.desupon.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.settings.ThemePreference
import moe.desupon.feature.settings.logic.DnsAddress
import moe.desupon.feature.settings.ui.ChevronRow
import moe.desupon.feature.settings.ui.SectionHeader
import moe.desupon.feature.settings.ui.SettingsCard
import moe.desupon.feature.settings.ui.SettingsGlyphs
import moe.desupon.feature.settings.ui.SettingsRow
import moe.desupon.feature.settings.ui.SwitchRow
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.routing.SplitMode
import moe.desupon.platform.power.BackgroundWork
import moe.desupon.ui.theme.DesuponGlyphs
import org.koin.compose.viewmodel.koinViewModel

private enum class SettingsDialog { DNS, USER_AGENT, RESET }

/** Settings root; [contentPadding] carries the host's insets and bars. */
@Composable
fun SettingsScreen(
    onOpenSplitTunnel: () -> Unit,
    onOpenConfig: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        item { AppearanceSection(state, viewModel) }
        item {
            ConnectionSection(
                state = state,
                viewModel = viewModel,
                onOpenSplitTunnel = onOpenSplitTunnel,
                onEditDns = { dialog = SettingsDialog.DNS },
                onEditUserAgent = { dialog = SettingsDialog.USER_AGENT },
            )
        }
        item {
            Section(R.string.settings_section_config) {
                ChevronRow(
                    title = stringResource(R.string.settings_config),
                    subtitle = stringResource(R.string.settings_config_summary),
                    icon = SettingsGlyphs.Code,
                    onClick = onOpenConfig,
                )
            }
        }
        item {
            Section(R.string.settings_section_data) {
                SettingsRow(
                    title = stringResource(R.string.settings_reset),
                    subtitle = stringResource(R.string.settings_reset_summary),
                    icon = Icons.Filled.Refresh,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { dialog = SettingsDialog.RESET },
                )
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }

    when (dialog) {
        SettingsDialog.DNS -> DnsDialog(
            current = state.connection.dns.remote,
            onDismiss = { dialog = null },
            onSave = { if (viewModel.setDnsRemote(it)) dialog = null },
        )
        SettingsDialog.USER_AGENT -> UserAgentDialog(
            current = state.userAgent.orEmpty(),
            onDismiss = { dialog = null },
            onSave = {
                viewModel.setUserAgent(it)
                dialog = null
            },
        )
        SettingsDialog.RESET -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    dialog = null
                }) { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionHeader(stringResource(title))
        SettingsCard { content() }
    }
}

@Composable
private fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Section(R.string.settings_section_appearance) {
        SwitchRow(
            title = stringResource(R.string.settings_simple),
            subtitle = stringResource(R.string.settings_simple_summary),
            icon = Icons.Filled.Home,
            checked = state.ui.simpleMode,
            onCheckedChange = viewModel::setSimpleMode,
        )
        SettingsRow(title = stringResource(R.string.settings_theme), icon = SettingsGlyphs.Moon) {
            ThemeSelector(state.ui.theme, viewModel::setTheme)
        }
        SwitchRow(
            title = stringResource(R.string.settings_amoled),
            subtitle = stringResource(R.string.settings_amoled_summary),
            icon = SettingsGlyphs.Contrast,
            checked = state.ui.amoled,
            onCheckedChange = viewModel::setAmoled,
        )
        SwitchRow(
            title = stringResource(R.string.settings_desu),
            subtitle = stringResource(R.string.settings_desu_summary),
            icon = DesuponGlyphs.Sprout,
            checked = state.ui.desuMode,
            onCheckedChange = viewModel::setDesuMode,
        )
        val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(
                if (dynamicSupported) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unsupported,
            ),
            icon = SettingsGlyphs.Palette,
            checked = state.ui.dynamicColor && dynamicSupported,
            enabled = dynamicSupported,
            onCheckedChange = viewModel::setDynamicColor,
        )
    }
}

@Composable
private fun ThemeSelector(selected: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    val options: List<Triple<ThemePreference, ImageVector, Int>> = listOf(
        Triple(ThemePreference.DARK, SettingsGlyphs.Moon, R.string.settings_theme_dark),
        Triple(ThemePreference.LIGHT, SettingsGlyphs.Sun, R.string.settings_theme_light),
        Triple(ThemePreference.SYSTEM, SettingsGlyphs.Monitor, R.string.settings_theme_system),
    )
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, (pref, glyph, label) ->
            SegmentedButton(
                selected = selected == pref,
                onClick = { onSelect(pref) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { Icon(glyph, contentDescription = stringResource(label), modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

@Composable
private fun ConnectionSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSplitTunnel: () -> Unit,
    onEditDns: () -> Unit,
    onEditUserAgent: () -> Unit,
) {
    Section(R.string.settings_section_connection) {
        val apps = when (state.splitMode) {
            SplitMode.ALL -> stringResource(R.string.settings_split_mode_all)
            SplitMode.ONLY_SELECTED -> state.splitApps.let { n ->
                if (n == null) stringResource(R.string.split_mode_only) else pluralStringResource(R.plurals.settings_split_mode_only, n, n)
            }
            SplitMode.ALL_EXCEPT_SELECTED -> state.splitApps.let { n ->
                if (n == null) stringResource(R.string.split_mode_except) else pluralStringResource(R.plurals.settings_split_mode_except, n, n)
            }
        }
        val rules = pluralStringResource(R.plurals.settings_rules_count, state.enabledRules, state.enabledRules)
        ChevronRow(
            title = stringResource(R.string.settings_split),
            subtitle = stringResource(R.string.settings_split_summary, apps, rules),
            icon = SettingsGlyphs.Split,
            onClick = onOpenSplitTunnel,
        )
        SwitchRow(
            title = stringResource(R.string.settings_lan_bypass),
            subtitle = stringResource(R.string.settings_lan_bypass_summary),
            icon = SettingsGlyphs.Lan,
            checked = state.connection.lanBypass,
            onCheckedChange = viewModel::setLanBypass,
        )
        SwitchRow(
            title = stringResource(R.string.settings_ipv4_only),
            subtitle = stringResource(R.string.settings_ipv4_only_summary),
            icon = SettingsGlyphs.Ipv4,
            checked = state.ipv4Only,
            onCheckedChange = viewModel::setIpv4Only,
        )
        val dns = state.connection.dns.remote
        ChevronRow(
            title = stringResource(R.string.settings_dns),
            subtitle = if (dns == DnsSettings.DEFAULT_REMOTE) stringResource(R.string.settings_dns_default, dns) else dns,
            icon = SettingsGlyphs.Globe,
            onClick = onEditDns,
        )
        ChevronRow(
            title = stringResource(R.string.settings_user_agent),
            subtitle = state.userAgent
                ?: stringResource(R.string.settings_user_agent_default, SubscriptionFetcher.DEFAULT_USER_AGENT),
            icon = SettingsGlyphs.Tag,
            onClick = onEditUserAgent,
        )
        BackgroundRows()
        SettingsRow(
            title = stringResource(R.string.settings_stealth),
            subtitle = stringResource(R.string.settings_stealth_summary),
            icon = SettingsGlyphs.Shield,
        )
    }
}

@Composable
private fun DnsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val valid = DnsAddress.isValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dns_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = !valid,
                placeholder = { Text(DnsSettings.DEFAULT_REMOTE) },
                supportingText = {
                    Text(stringResource(if (valid) R.string.settings_dns_dialog_hint else R.string.settings_dns_invalid))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.action_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun UserAgentDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_user_agent)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(SubscriptionFetcher.DEFAULT_USER_AGENT) },
                supportingText = { Text(stringResource(R.string.settings_user_agent_dialog_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.action_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** The exemption can change in system settings at any time, so it is read again on every resume. */
@Composable
private fun BackgroundRows() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BackgroundWork.isExempt(context)) }
    LifecycleResumeEffect(context) {
        exempt = BackgroundWork.isExempt(context)
        onPauseOrDispose {}
    }
    SettingsRow(
        title = stringResource(R.string.settings_background),
        subtitle = stringResource(if (exempt) R.string.settings_background_allowed else R.string.settings_background_restricted),
        icon = Icons.Filled.PlayArrow,
        onClick = if (exempt) null else ({ context.startSafely(BackgroundWork.exemptionRequest(context)) }),
    )
    if (BackgroundWork.needsAutostart()) {
        ChevronRow(
            title = stringResource(R.string.settings_autostart),
            subtitle = stringResource(R.string.settings_autostart_summary),
            icon = Icons.Filled.Build,
            onClick = { context.startSafely(BackgroundWork.autostartIntent(context)) },
        )
    }
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}
