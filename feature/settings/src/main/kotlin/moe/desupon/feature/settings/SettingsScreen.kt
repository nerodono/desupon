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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import moe.desupon.data.fetch.FetchError
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.geo.GeoDataSettings
import moe.desupon.data.geo.GeoDataUpdater
import moe.desupon.data.geo.GeoKind
import moe.desupon.data.repo.UrlMask
import moe.desupon.data.settings.LogVerbosity
import moe.desupon.data.settings.ThemePreference
import moe.desupon.feature.settings.logic.Ago
import moe.desupon.feature.settings.logic.DnsAddress
import moe.desupon.feature.settings.logic.GeoStatusLine
import moe.desupon.feature.settings.logic.GeoUrl
import moe.desupon.feature.settings.logs.verbosityLabel
import moe.desupon.feature.settings.ui.ChoiceRow
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

private enum class SettingsDialog { DNS, USER_AGENT, GEOIP_URL, GEOSITE_URL, RESET }

/** Settings root; [contentPadding] carries the host's insets and bars. */
@Composable
fun SettingsScreen(
    onOpenSplitTunnel: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val geo by viewModel.geo.collectAsStateWithLifecycle()
    val verbosity by viewModel.logVerbosity.collectAsStateWithLifecycle()
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
            GeoSection(
                state = geo,
                viewModel = viewModel,
                onEditUrl = { kind -> dialog = if (kind == GeoKind.GEOIP) SettingsDialog.GEOIP_URL else SettingsDialog.GEOSITE_URL },
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
        item { LogsSection(verbosity, viewModel::setLogVerbosity, onOpenLogs) }
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
        SettingsDialog.GEOIP_URL, SettingsDialog.GEOSITE_URL -> {
            val kind = if (dialog == SettingsDialog.GEOIP_URL) GeoKind.GEOIP else GeoKind.GEOSITE
            key(kind) {
                GeoUrlDialog(
                    kind = kind,
                    current = if (kind == GeoKind.GEOIP) geo.settings.geoipUrl else geo.settings.geositeUrl,
                    onDismiss = { dialog = null },
                    onSave = { if (viewModel.setGeoUrl(kind, it)) dialog = null },
                )
            }
        }
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

@Composable
private fun GeoSection(state: GeoUiState, viewModel: SettingsViewModel, onEditUrl: (GeoKind) -> Unit) {
    val config = state.settings
    Section(R.string.settings_section_geo) {
        SwitchRow(
            title = stringResource(R.string.settings_geo_auto),
            subtitle = stringResource(R.string.settings_geo_auto_summary),
            icon = SettingsGlyphs.Database,
            checked = config.auto,
            onCheckedChange = viewModel::setGeoAuto,
        )
        val presets = (GeoDataSettings.INTERVAL_PRESETS + listOfNotNull(config.intervalDays)).distinct().sorted()
        ChoiceRow(title = stringResource(R.string.settings_geo_interval), icon = SettingsGlyphs.Clock, enabled = config.auto) {
            (presets + null).forEach { days ->
                FilterChip(
                    selected = config.intervalDays == days,
                    onClick = { viewModel.setGeoInterval(days) },
                    enabled = config.auto,
                    label = {
                        Text(
                            if (days == null) {
                                stringResource(R.string.settings_geo_interval_manual)
                            } else {
                                pluralStringResource(R.plurals.settings_geo_interval_days, days, days)
                            },
                        )
                    },
                )
            }
        }
        SwitchRow(
            title = stringResource(R.string.settings_geo_unmetered),
            subtitle = stringResource(R.string.settings_geo_unmetered_summary),
            icon = SettingsGlyphs.Wifi,
            checked = config.unmeteredOnly,
            enabled = config.auto,
            onCheckedChange = viewModel::setGeoUnmeteredOnly,
        )
        for (kind in GeoKind.entries) {
            val url = config.url(kind)
            val shown = if (url == GeoDataSettings.defaultUrl(kind)) stringResource(R.string.settings_geo_url_default, url) else UrlMask.mask(url)
            ChevronRow(
                title = stringResource(geoUrlTitle(kind)),
                subtitle = if (GeoUrl.cleartext(url)) shown + "\n" + stringResource(R.string.settings_geo_url_cleartext) else shown,
                icon = SettingsGlyphs.Globe,
                onClick = { onEditUrl(kind) },
            )
        }
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(AGO_TICK_MS)
                value = System.currentTimeMillis()
            }
        }
        val lines = listOfNotNull(stringResource(R.string.settings_geo_updating).takeIf { state.running }) +
            GeoStatusLine.of(state).map { geoStatusText(it, now) }
        SettingsRow(
            title = stringResource(R.string.settings_geo_update_now),
            subtitle = lines.joinToString("\n"),
            icon = SettingsGlyphs.Download,
            enabled = !state.running,
            onClick = viewModel::updateGeoNow,
        ) {
            if (state.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun LogsSection(verbosity: LogVerbosity, onVerbosity: (LogVerbosity) -> Unit, onOpenLogs: () -> Unit) {
    Section(R.string.settings_section_logs) {
        ChevronRow(
            title = stringResource(R.string.settings_logs_core),
            subtitle = stringResource(R.string.settings_logs_core_summary),
            icon = SettingsGlyphs.Logs,
            onClick = onOpenLogs,
        )
        val hint = when (verbosity) {
            LogVerbosity.ERRORS -> R.string.settings_logs_level_hint_errors
            LogVerbosity.WARNINGS -> R.string.settings_logs_level_hint
            LogVerbosity.INFO, LogVerbosity.DEBUG -> R.string.settings_logs_level_hint_private
        }
        ChoiceRow(
            title = stringResource(R.string.settings_logs_level),
            icon = Icons.AutoMirrored.Filled.List,
            subtitle = stringResource(hint),
        ) {
            LogVerbosity.entries.forEach { v ->
                FilterChip(selected = v == verbosity, onClick = { onVerbosity(v) }, label = { Text(verbosityLabel(v)) })
            }
        }
    }
}

private fun geoUrlTitle(kind: GeoKind): Int = when (kind) {
    GeoKind.GEOIP -> R.string.settings_geo_geoip_url
    GeoKind.GEOSITE -> R.string.settings_geo_geosite_url
}

@Composable
private fun geoStatusText(line: GeoStatusLine, nowMs: Long): String = when (line) {
    GeoStatusLine.WaitingForUnmetered -> stringResource(R.string.settings_geo_status_waiting)
    is GeoStatusLine.Installed -> {
        val categories = pluralStringResource(R.plurals.settings_geo_categories, line.categories, line.categories)
        val updated = line.updatedAtMs
        val checked = line.checkedAtMs
        when {
            updated == null -> stringResource(R.string.settings_geo_status_installed, line.kind.id, categories)
            checked == null -> stringResource(R.string.settings_geo_status_ok, line.kind.id, categories, ago(updated, nowMs))
            else -> stringResource(R.string.settings_geo_status_checked, line.kind.id, categories, ago(updated, nowMs), ago(checked, nowMs))
        }
    }
    is GeoStatusLine.Missing -> stringResource(R.string.settings_geo_status_never, line.kind.id)
    is GeoStatusLine.DownloadFailed ->
        stringResource(R.string.settings_geo_status_error, line.kind.id, geoErrorText(line.code), ago(line.atMs ?: nowMs, nowMs))
    is GeoStatusLine.ConvertFailed -> stringResource(R.string.settings_geo_status_convert_failed, line.kind.id, ago(line.atMs, nowMs))
}

@Composable
private fun geoErrorText(code: String): String {
    if (code.startsWith(HTTP_ERROR_PREFIX)) return stringResource(R.string.geo_error_http, code.removePrefix(HTTP_ERROR_PREFIX))
    return stringResource(
        when (code) {
            FetchError.Timeout.code -> R.string.geo_error_timeout
            FetchError.Network.code -> R.string.geo_error_network
            FetchError.EmptyBody.code -> R.string.geo_error_empty
            FetchError.TooLarge.code -> R.string.geo_error_too_large
            GeoDataUpdater.ERROR_BAD_URL -> R.string.geo_error_bad_url
            GeoDataUpdater.ERROR_CHECKSUM -> R.string.geo_error_checksum
            GeoDataUpdater.ERROR_INVALID -> R.string.geo_error_invalid
            GeoDataUpdater.ERROR_STORAGE -> R.string.geo_error_storage
            else -> R.string.geo_error_unknown
        },
    )
}

@Composable
private fun ago(atMs: Long, nowMs: Long): String = when (val a = Ago.of(atMs, nowMs)) {
    Ago.JustNow -> stringResource(R.string.ago_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.ago_minutes, a.n, a.n)
    is Ago.Hours -> pluralStringResource(R.plurals.ago_hours, a.n, a.n)
    is Ago.Days -> pluralStringResource(R.plurals.ago_days, a.n, a.n)
}

/** [current] is the custom URL, null for the default. */
@Composable
private fun GeoUrlDialog(kind: GeoKind, current: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val valid = GeoUrl.isValid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(geoUrlTitle(kind))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = !valid,
                placeholder = { Text(GeoDataSettings.defaultUrl(kind)) },
                supportingText = {
                    when {
                        !valid -> Text(stringResource(R.string.settings_geo_url_invalid))
                        GeoUrl.cleartext(text) -> Text(stringResource(R.string.settings_geo_url_cleartext), color = MaterialTheme.colorScheme.error)
                        else -> Text(stringResource(R.string.settings_geo_url_dialog_hint))
                    }
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
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

private const val AGO_TICK_MS = 60_000L
private const val HTTP_ERROR_PREFIX = "HTTP_"
