package moe.desupon.feature.settings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.desupon.data.repo.SplitRuleRepository
import moe.desupon.data.settings.PrefixRule
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.feature.settings.apps.InstalledAppsSource
import moe.desupon.feature.settings.logic.AppFilter
import moe.desupon.feature.settings.logic.AppRowState
import moe.desupon.feature.settings.logic.AppRows
import moe.desupon.feature.settings.logic.InstalledApp
import moe.desupon.feature.settings.logic.PrefixValidation
import moe.desupon.feature.settings.logic.PrefixValidator
import moe.desupon.feature.settings.logic.Reorder
import moe.desupon.feature.settings.logic.RuleValidation
import moe.desupon.feature.settings.logic.RuleValidator
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitMode
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind

data class SplitAppsState(
    val loading: Boolean = true,
    val mode: SplitMode = SplitMode.ALL,
    /** Installed apps shown checked, see [AppRows.checked]. */
    val checkedCount: Int = 0,
    val query: String = "",
    val showSystem: Boolean = false,
    /** Stored ALL_EXCEPT_SELECTED prefix rule, shown whether on or off. */
    val prefixRule: PrefixRule = PrefixRule(enabled = true, prefixes = emptySet()),
    val visible: List<AppRowState> = emptyList(),
)

class SplitTunnelViewModel(
    private val settings: SettingsRepository,
    private val splitRules: SplitRuleRepository,
    val ruleSetNames: List<String>,
    private val appsSource: InstalledAppsSource,
    private val ownPackage: String,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")

    /** Checked-set snapshot for sorting, so toggled rows stay in place until the next refresh. */
    private val pinned = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch {
            val list = appsSource.load()
            pinned.value = checkedNow(list)
            installed.value = list
        }
    }

    val apps: StateFlow<SplitAppsState> = combine(
        settings.settings.map { it.apps },
        combine(settings.showSystemApps, settings.bypassPrefixRule, ::Pair),
        query,
        installed,
        pinned,
    ) { split: AppSplit, (showSystem, prefixRule), q, list, pin ->
        SplitAppsState(
            loading = list == null,
            mode = split.mode,
            checkedCount = list?.let { apps -> AppRows.checked(split, apps.map { it.packageName }, ownPackage).size } ?: 0,
            query = q,
            showSystem = showSystem,
            prefixRule = prefixRule,
            visible = list?.let { AppFilter.apply(it, q, showSystem, pin) }.orEmpty().map { AppRows.row(split, it, ownPackage) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SplitAppsState())

    val rules: StateFlow<List<SplitRule>> = splitRules.rules
        .map { list -> list.sortedWith(compareBy({ it.order }, { it.id })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? = appsSource.icon(packageName, sizePx)

    fun setMode(mode: SplitMode) = launch {
        settings.setSplitMode(mode)
        repin()
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setShowSystem(enabled: Boolean) = launch {
        repin()
        settings.setShowSystemApps(enabled)
    }

    fun setSelected(packageName: String, selected: Boolean) = launch {
        when (val mode = settings.settings.first().apps.mode) {
            SplitMode.ALL_EXCEPT_SELECTED -> settings.setAppBypassed(packageName, selected)
            else -> settings.setPackageSelected(mode, packageName, selected)
        }
    }

    /** Drops the manual override that keeps a prefix-matched app in the tunnel. */
    fun clearForceTunnel(packageName: String) = launch { settings.setForceTunnel(packageName, false) }

    /** Checks or clears every app currently visible under the filter. */
    fun setAllVisible(selected: Boolean) = launch {
        val visible = apps.value.visible
        val split = settings.settings.first().apps
        if (split.mode == SplitMode.ALL_EXCEPT_SELECTED) {
            visible.filter { it.checked != selected }.forEach { settings.setAppBypassed(it.app.packageName, selected) }
        } else {
            val packages = visible.mapTo(HashSet()) { it.app.packageName }
            settings.setSplitPackages(split.mode, if (selected) split.packages + packages else split.packages - packages)
        }
    }

    fun setPrefixBypassEnabled(enabled: Boolean) = launch {
        settings.setPrefixBypassEnabled(enabled)
        repin()
    }

    /** Validates and stores [raw] as a new bypass prefix; returns the validation outcome. */
    fun addPrefix(raw: String): PrefixValidation {
        val result = PrefixValidator.validate(raw, apps.value.prefixRule.prefixes)
        if (result is PrefixValidation.Valid) {
            launch {
                settings.setBypassPrefixes(settings.bypassPrefixRule.first().prefixes + result.value)
                repin()
            }
        }
        return result
    }

    fun removePrefix(prefix: String) = launch {
        settings.setBypassPrefixes(settings.bypassPrefixRule.first().prefixes - prefix)
        repin()
    }

    fun resetApps() = launch {
        settings.resetAppSplitToDefaults()
        repin()
    }

    /** Validates and stores a new rule (when [id] is null) or an edit; returns the validation outcome. */
    fun saveRule(id: Long?, kind: SplitRuleKind, value: String, action: SplitAction): RuleValidation {
        val result = RuleValidator.validate(kind, value, ruleSetNames)
        if (result !is RuleValidation.Valid) return result
        launch {
            val existing = id?.let { rid -> splitRules.rules.first().firstOrNull { it.id == rid } }
            if (existing == null) {
                splitRules.add(SplitRule(order = 0, kind = kind, value = result.value, action = action))
            } else {
                splitRules.update(existing.copy(kind = kind, value = result.value, action = action))
            }
        }
        return result
    }

    fun setRuleEnabled(rule: SplitRule, enabled: Boolean) = launch { splitRules.update(rule.copy(enabled = enabled)) }

    fun deleteRule(rule: SplitRule) = launch { splitRules.delete(rule.id) }

    /** [delta] -1 moves the rule up, +1 down. */
    fun moveRule(rule: SplitRule, delta: Int) = launch {
        val ids = splitRules.rules.first().sortedWith(compareBy({ it.order }, { it.id })).map { it.id }
        val moved = Reorder.shift(ids, rule.id, delta)
        if (moved != ids) splitRules.reorder(moved)
    }

    fun resetRules() = launch { splitRules.resetToDefaults() }

    private suspend fun repin() {
        installed.value?.let { pinned.value = checkedNow(it) }
    }

    private suspend fun checkedNow(list: List<InstalledApp>): Set<String> =
        AppRows.checked(settings.settings.first().apps, list.map { it.packageName }, ownPackage)

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
