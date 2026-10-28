package moe.desupon.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.desupon.data.geo.GeoDataSettings
import moe.desupon.data.geo.GeoDataUpdater
import moe.desupon.data.geo.GeoFileStatus
import moe.desupon.data.geo.GeoKind
import moe.desupon.data.repo.SplitRuleRepository
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.LogVerbosity
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.data.settings.ThemePreference
import moe.desupon.data.settings.UiSettings
import moe.desupon.feature.settings.apps.InstalledAppsSource
import moe.desupon.feature.settings.logic.AppRows
import moe.desupon.feature.settings.logic.DnsAddress
import moe.desupon.feature.settings.logic.GeoUrl
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitMode

data class SettingsUiState(
    val ui: UiSettings = UiSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    /** Null means the built-in default. */
    val userAgent: String? = null,
    val enabledRules: Int = 0,
    /** Installed apps checked in the split picker, see [AppRows.checked]; null until the app list loads. */
    val splitApps: Int? = null,
) {
    val ipv4Only: Boolean get() = connection.dns.ipv4Only
    val splitMode: SplitMode get() = connection.apps.mode
}

data class GeoFileUi(val kind: GeoKind, val status: GeoFileStatus = GeoFileStatus(), val categories: Int = 0)

data class GeoUiState(
    val settings: GeoDataSettings = GeoDataSettings(),
    val files: List<GeoFileUi> = GeoKind.entries.map(::GeoFileUi),
    /** A download or conversion is in progress. */
    val running: Boolean = false,
    /** A download is due but held back by "Только Wi-Fi". */
    val waitingForUnmetered: Boolean = false,
)

sealed interface ConfigState {
    data object Loading : ConfigState
    data object None : ConfigState
    data class Ready(val json: String) : ConfigState
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val splitRules: SplitRuleRepository,
    lastConfig: LastConfigSource,
    appsSource: InstalledAppsSource,
    private val geoData: GeoDataUpdater,
    ruleSetStore: RuleSetStore,
    ownPackage: String,
) : ViewModel() {
    private val installed = MutableStateFlow<List<String>?>(null)

    init {
        viewModelScope.launch { installed.value = appsSource.load().map { it.packageName } }
    }

    val state: StateFlow<SettingsUiState> = combine(
        settings.ui,
        settings.settings,
        settings.subscriptionUserAgent,
        splitRules.rules.map { rules -> rules.count { it.enabled } },
        installed,
    ) { ui, connection, ua, rules, apps ->
        SettingsUiState(ui, connection, ua, rules, apps?.let { AppRows.checked(connection.apps, it, ownPackage).size })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    val geo: StateFlow<GeoUiState> = combine(
        settings.geoData,
        settings.geoStatus,
        ruleSetStore.downloaded,
        geoData.running,
        geoData.waitingForUnmetered,
    ) { config, statuses, downloaded, running, waiting ->
        GeoUiState(config, GeoKind.entries.map { GeoFileUi(it, statuses.getValue(it), downloaded[it].orEmpty().size) }, running, waiting)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GeoUiState())

    val logVerbosity: StateFlow<LogVerbosity> =
        settings.logVerbosity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogVerbosity.WARNINGS)

    val config: StateFlow<ConfigState> = lastConfig.maskedConfig()
        .map { json -> json?.takeIf { it.isNotBlank() }?.let(ConfigState::Ready) ?: ConfigState.None }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConfigState.Loading)

    fun setTheme(theme: ThemePreference) = launch { settings.setTheme(theme) }

    fun setAmoled(enabled: Boolean) = launch { settings.setAmoled(enabled) }

    fun setDesuMode(enabled: Boolean) = launch { settings.setDesuMode(enabled) }

    fun setDynamicColor(enabled: Boolean) = launch { settings.setDynamicColor(enabled) }

    fun setSimpleMode(enabled: Boolean) = launch { settings.setSimpleMode(enabled) }

    fun setLanBypass(enabled: Boolean) = launch { settings.setLanBypass(enabled) }

    fun setIpv4Only(enabled: Boolean) = launch { settings.setIpv4Only(enabled) }

    fun setLogVerbosity(verbosity: LogVerbosity) = launch { settings.setLogVerbosity(verbosity) }

    /** Returns false and saves nothing when [address] is not a supported DNS address; blank restores the default. */
    fun setDnsRemote(address: String): Boolean {
        if (!DnsAddress.isValid(address)) return false
        launch { settings.setDnsRemote(address.trim().takeUnless { it == DnsSettings.DEFAULT_REMOTE }.orEmpty()) }
        return true
    }

    /** Blank restores the default. */
    fun setUserAgent(userAgent: String) = launch { settings.setSubscriptionUserAgent(userAgent) }

    fun setGeoAuto(enabled: Boolean) = launchThenCheck { settings.setGeoAuto(enabled) }

    /** Returns false and saves nothing when [url] is not an http(s) file URL; blank restores the default. */
    fun setGeoUrl(kind: GeoKind, url: String): Boolean {
        if (!GeoUrl.isValid(url)) return false
        launchThenCheck { settings.setGeoUrl(kind, url) }
        return true
    }

    /** Null checks only on request. */
    fun setGeoInterval(days: Int?) = launchThenCheck { settings.setGeoInterval(days) }

    fun setGeoUnmeteredOnly(enabled: Boolean) = launchThenCheck { settings.setGeoUnmeteredOnly(enabled) }

    /** Ignores the schedule and the network type. */
    fun updateGeoNow() = geoData.updateInBackground(force = true)

    /** Everything this screen and split tunneling control; subscriptions are untouched. */
    fun resetAll() = launch {
        settings.resetAll()
        splitRules.resetToDefaults()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** A changed setting may make a download due right away. */
    private fun launchThenCheck(block: suspend () -> Unit) = launch {
        block()
        geoData.updateInBackground(force = false)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
