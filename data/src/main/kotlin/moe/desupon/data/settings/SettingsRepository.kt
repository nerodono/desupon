package moe.desupon.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.SettingsPort
import moe.desupon.data.geo.GeoDataSettings
import moe.desupon.data.geo.GeoFileStatus
import moe.desupon.data.geo.GeoKind
import moe.desupon.data.split.AppBypass
import moe.desupon.data.split.RuDefaults
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitMode
import java.io.File

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class UiSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val amoled: Boolean = false,
    val desuMode: Boolean = false,
    /** Material You wallpaper colors instead of the brand palette. */
    val dynamicColor: Boolean = false,
    /** One big connect button and nothing else; the location is forced to Any meanwhile. */
    val simpleMode: Boolean = false,
)

/** Installed packages starting with one of [prefixes] bypass the tunnel in ALL_EXCEPT_SELECTED while [enabled]. */
data class PrefixRule(val enabled: Boolean, val prefixes: Set<String>)

/**
 * Settings backed by a preferences DataStore; unset keys read as defaults.
 * Each split mode keeps its own package set: ALL_EXCEPT_SELECTED defaults to [RuDefaults.bypassApps] plus the
 * [RuDefaults.bypassPrefixes] rule, ONLY_SELECTED to none.
 */
class SettingsRepository(private val store: DataStore<Preferences>) : SettingsPort {
    /** [AppSplit.packages] is the set of the active mode, empty for [SplitMode.ALL]. */
    override val settings: Flow<ConnectionSettings> = store.data.map { p ->
        ConnectionSettings(
            apps = p.appSplit(),
            lanBypass = p[LAN_BYPASS] ?: true,
            dns = DnsSettings(
                remote = p[DNS_REMOTE]?.takeIf { it.isNotBlank() } ?: DnsSettings.DEFAULT_REMOTE,
                strategy = if (p[IPV4_ONLY] == true) DnsStrategy.IPV4_ONLY else DnsStrategy.PREFER_IPV4,
            ),
        )
    }.distinctUntilChanged()

    val ui: Flow<UiSettings> = store.data.map { p ->
        UiSettings(
            theme = p[THEME]?.let { t -> ThemePreference.entries.firstOrNull { it.name == t } } ?: ThemePreference.SYSTEM,
            amoled = p[AMOLED] ?: false,
            desuMode = p[DESU_MODE] ?: false,
            dynamicColor = p[DYNAMIC_COLOR] ?: false,
            simpleMode = p[SIMPLE_MODE] ?: false,
        )
    }.distinctUntilChanged()

    val simpleMode: Flow<Boolean> = store.data.map { it[SIMPLE_MODE] ?: false }.distinctUntilChanged()

    /** ALL_EXCEPT_SELECTED's prefix rule as stored, whether on or off; [AppSplit.bypassPrefixes] carries it only while on. */
    val bypassPrefixRule: Flow<PrefixRule> = store.data.map { p ->
        PrefixRule(enabled = p[PREFIX_BYPASS] ?: true, prefixes = p[PREFIXES] ?: RuDefaults.bypassPrefixes)
    }.distinctUntilChanged()

    /** Packages kept in the tunnel although selected or prefix-matched in ALL_EXCEPT_SELECTED. */
    val forceTunnel: Flow<Set<String>> = store.data.map { it[FORCE_TUNNEL].orEmpty() }.distinctUntilChanged()

    /** App picker lists system apps too. */
    val showSystemApps: Flow<Boolean> = store.data.map { it[SHOW_SYSTEM_APPS] ?: false }.distinctUntilChanged()

    /** User-Agent for subscriptions without their own; null means [moe.desupon.data.fetch.SubscriptionFetcher.DEFAULT_USER_AGENT]. */
    val subscriptionUserAgent: Flow<String?> =
        store.data.map { it[SUB_USER_AGENT]?.takeIf { ua -> ua.isNotBlank() } }.distinctUntilChanged()

    val geoData: Flow<GeoDataSettings> = store.data.map { p ->
        GeoDataSettings(
            auto = p[GEO_AUTO] ?: true,
            geoipUrl = p[GEOIP_URL]?.takeIf { it.isNotBlank() },
            geositeUrl = p[GEOSITE_URL]?.takeIf { it.isNotBlank() },
            intervalDays = when (val days = p[GEO_INTERVAL]) {
                null -> GeoDataSettings.DEFAULT_INTERVAL_DAYS
                GEO_INTERVAL_MANUAL -> null
                else -> days.takeIf { it > 0 } ?: GeoDataSettings.DEFAULT_INTERVAL_DAYS
            },
            unmeteredOnly = p[GEO_UNMETERED_ONLY] ?: true,
        )
    }.distinctUntilChanged()

    /** Survives [resetAll]. */
    val geoStatus: Flow<Map<GeoKind, GeoFileStatus>> =
        store.data.map { p -> GeoKind.entries.associateWith { p.geoStatus(it) } }.distinctUntilChanged()

    /** The notification permission was already asked for once; survives [resetAll]. */
    val notificationPromptShown: Flow<Boolean> = store.data.map { it[NOTIFICATION_PROMPT_SHOWN] ?: false }.distinctUntilChanged()

    /** The battery-optimization exemption was already asked for once; survives [resetAll]. */
    val batteryPromptShown: Flow<Boolean> = store.data.map { it[BATTERY_PROMPT_SHOWN] ?: false }.distinctUntilChanged()

    suspend fun setTheme(theme: ThemePreference) {
        store.edit { it[THEME] = theme.name }
    }

    suspend fun setAmoled(enabled: Boolean) = put(AMOLED, enabled)

    suspend fun setDesuMode(enabled: Boolean) = put(DESU_MODE, enabled)

    suspend fun setDynamicColor(enabled: Boolean) = put(DYNAMIC_COLOR, enabled)

    suspend fun setSimpleMode(enabled: Boolean) = put(SIMPLE_MODE, enabled)

    suspend fun setSplitMode(mode: SplitMode) {
        store.edit { it[SPLIT_MODE] = mode.name }
    }

    /** Replaces the package set of [mode]; ignored for [SplitMode.ALL], which has none. */
    suspend fun setSplitPackages(mode: SplitMode, packages: Set<String>) {
        val key = packagesKey(mode) ?: return
        store.edit { it[key] = packages }
    }

    suspend fun setPackageSelected(mode: SplitMode, packageName: String, selected: Boolean) {
        val key = packagesKey(mode) ?: return
        store.edit { p ->
            val current = p.packages(mode)
            p[key] = if (selected) current + packageName else current - packageName
        }
    }

    /** Trimmed, blanks dropped; an empty result matches nothing. */
    suspend fun setBypassPrefixes(prefixes: Collection<String>) {
        store.edit { it[PREFIXES] = prefixes.map(String::trim).filterTo(LinkedHashSet()) { p -> p.isNotEmpty() } }
    }

    suspend fun setPrefixBypassEnabled(enabled: Boolean) = put(PREFIX_BYPASS, enabled)

    suspend fun setForceTunnel(packageName: String, forced: Boolean) {
        store.edit { p ->
            val current = p[FORCE_TUNNEL].orEmpty()
            p[FORCE_TUNNEL] = if (forced) current + packageName else current - packageName
        }
    }

    /**
     * The ALL_EXCEPT_SELECTED picker's checkbox: checking drops an override and selects the package unless the prefix rule already covers it;
     * unchecking deselects it and overrides the prefix rule when that still matches.
     */
    suspend fun setAppBypassed(packageName: String, bypassed: Boolean) {
        store.edit { p ->
            val rule = p[PREFIXES] ?: RuDefaults.bypassPrefixes
            val prefixed = (p[PREFIX_BYPASS] ?: true) && AppBypass.matchesPrefix(packageName, rule)
            val selected = p.packages(SplitMode.ALL_EXCEPT_SELECTED)
            val forced = p[FORCE_TUNNEL].orEmpty()
            if (bypassed) {
                p[FORCE_TUNNEL] = forced - packageName
                if (!prefixed) p[PACKAGES_EXCEPT] = selected + packageName
            } else {
                p[PACKAGES_EXCEPT] = selected - packageName
                p[FORCE_TUNNEL] = if (prefixed) forced + packageName else forced - packageName
            }
        }
    }

    suspend fun setShowSystemApps(enabled: Boolean) = put(SHOW_SYSTEM_APPS, enabled)

    suspend fun setLanBypass(enabled: Boolean) = put(LAN_BYPASS, enabled)

    suspend fun setIpv4Only(enabled: Boolean) = put(IPV4_ONLY, enabled)

    /** Blank restores [DnsSettings.DEFAULT_REMOTE]. */
    suspend fun setDnsRemote(remote: String) {
        store.edit { p -> remote.trim().let { if (it.isEmpty()) p.remove(DNS_REMOTE) else p[DNS_REMOTE] = it } }
    }

    suspend fun setSubscriptionUserAgent(userAgent: String?) {
        store.edit { p -> userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { p[SUB_USER_AGENT] = it } ?: p.remove(SUB_USER_AGENT) }
    }

    suspend fun setGeoAuto(enabled: Boolean) = put(GEO_AUTO, enabled)

    /** Blank, null or the built-in URL restores the default. */
    suspend fun setGeoUrl(kind: GeoKind, url: String?) {
        val key = if (kind == GeoKind.GEOIP) GEOIP_URL else GEOSITE_URL
        val custom = url?.trim()?.takeIf { it.isNotEmpty() && it != GeoDataSettings.defaultUrl(kind) }
        store.edit { p -> if (custom == null) p.remove(key) else p[key] = custom }
    }

    /** Null checks only on request. */
    suspend fun setGeoInterval(days: Int?) {
        require(days == null || days > 0) { "days must be positive" }
        store.edit { it[GEO_INTERVAL] = days ?: GEO_INTERVAL_MANUAL }
    }

    suspend fun setGeoUnmeteredOnly(enabled: Boolean) = put(GEO_UNMETERED_ONLY, enabled)

    suspend fun updateGeoStatus(kind: GeoKind, transform: (GeoFileStatus) -> GeoFileStatus) {
        store.edit { p -> p.putGeoStatus(kind, transform(p.geoStatus(kind))) }
    }

    suspend fun setNotificationPromptShown() = put(NOTIFICATION_PROMPT_SHOWN, true)

    suspend fun setBatteryPromptShown() = put(BATTERY_PROMPT_SHOWN, true)

    suspend fun resetAppSplitToDefaults() {
        store.edit { p -> APP_SPLIT_KEYS.forEach { p.remove(it) } }
    }

    /** Every user setting back to its default in one edit; split rules live in [moe.desupon.data.repo.SplitRuleRepository]. */
    suspend fun resetAll() {
        store.edit { p -> RESETTABLE_KEYS.forEach { p.remove(it) } }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    private fun Preferences.appSplit(): AppSplit {
        val mode = this[SPLIT_MODE]?.let { m -> SplitMode.entries.firstOrNull { it.name == m } } ?: RuDefaults.appSplit.mode
        if (mode != SplitMode.ALL_EXCEPT_SELECTED) return AppSplit(mode, packages(mode))
        val prefixes = if (this[PREFIX_BYPASS] ?: true) this[PREFIXES] ?: RuDefaults.bypassPrefixes else emptySet()
        return AppSplit(mode, packages(mode), prefixes, this[FORCE_TUNNEL].orEmpty())
    }

    private fun Preferences.packages(mode: SplitMode): Set<String> = when (mode) {
        SplitMode.ALL -> emptySet()
        SplitMode.ALL_EXCEPT_SELECTED -> this[PACKAGES_EXCEPT] ?: RuDefaults.bypassApps
        SplitMode.ONLY_SELECTED -> this[PACKAGES_ONLY] ?: emptySet()
    }

    private fun Preferences.geoStatus(kind: GeoKind): GeoFileStatus {
        val k = GEO_STATUS.getValue(kind)
        return GeoFileStatus(
            url = this[k.url],
            etag = this[k.etag],
            sha256 = this[k.sha256],
            lastOkAtMs = this[k.lastOk],
            updatedAtMs = this[k.updated],
            lastAttemptAtMs = this[k.lastAttempt],
            lastError = this[k.lastError],
            errorUrl = this[k.errorUrl],
            setsSha256 = this[k.setsSha256],
            convertFailedAtMs = this[k.convertFailed],
        )
    }

    private fun MutablePreferences.putGeoStatus(kind: GeoKind, s: GeoFileStatus) {
        val k = GEO_STATUS.getValue(kind)
        putOrRemove(k.url, s.url)
        putOrRemove(k.etag, s.etag)
        putOrRemove(k.sha256, s.sha256)
        putOrRemove(k.lastOk, s.lastOkAtMs)
        putOrRemove(k.updated, s.updatedAtMs)
        putOrRemove(k.lastAttempt, s.lastAttemptAtMs)
        putOrRemove(k.lastError, s.lastError)
        putOrRemove(k.errorUrl, s.errorUrl)
        putOrRemove(k.setsSha256, s.setsSha256)
        putOrRemove(k.convertFailed, s.convertFailedAtMs)
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private class GeoStatusKeys(kind: GeoKind) {
        val url = stringPreferencesKey("${kind.id}_source_url")
        val etag = stringPreferencesKey("${kind.id}_etag")
        val sha256 = stringPreferencesKey("${kind.id}_sha256")
        val lastOk = longPreferencesKey("${kind.id}_last_ok_at")
        val updated = longPreferencesKey("${kind.id}_updated_at")
        val lastAttempt = longPreferencesKey("${kind.id}_last_attempt_at")
        val lastError = stringPreferencesKey("${kind.id}_last_error")
        val errorUrl = stringPreferencesKey("${kind.id}_error_url")
        val setsSha256 = stringPreferencesKey("${kind.id}_sets_sha256")
        val convertFailed = longPreferencesKey("${kind.id}_convert_failed_at")
    }

    private fun packagesKey(mode: SplitMode): Preferences.Key<Set<String>>? = when (mode) {
        SplitMode.ALL -> null
        SplitMode.ALL_EXCEPT_SELECTED -> PACKAGES_EXCEPT
        SplitMode.ONLY_SELECTED -> PACKAGES_ONLY
    }

    /** The single package set shared by both modes becomes the ALL_EXCEPT_SELECTED set, which it was seeded from. */
    internal object SharedPackagesMigration : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean = LEGACY_PACKAGES in currentData

        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
            val legacy = this[LEGACY_PACKAGES]
            remove(LEGACY_PACKAGES)
            if (legacy != null && PACKAGES_EXCEPT !in this) this[PACKAGES_EXCEPT] = legacy
        }

        override suspend fun cleanUp() = Unit
    }

    companion object {
        const val FILE_NAME = "settings.preferences_pb"

        private val THEME = stringPreferencesKey("theme")
        private val AMOLED = booleanPreferencesKey("amoled")
        private val DESU_MODE = booleanPreferencesKey("desu_mode")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        private val SPLIT_MODE = stringPreferencesKey("split_mode")
        internal val LEGACY_PACKAGES = stringSetPreferencesKey("split_packages")
        private val PACKAGES_EXCEPT = stringSetPreferencesKey("split_packages_except")
        private val PACKAGES_ONLY = stringSetPreferencesKey("split_packages_only")
        private val SHOW_SYSTEM_APPS = booleanPreferencesKey("split_show_system_apps")
        private val LAN_BYPASS = booleanPreferencesKey("lan_bypass")
        private val IPV4_ONLY = booleanPreferencesKey("ipv4_only")
        private val DNS_REMOTE = stringPreferencesKey("dns_remote")
        private val SUB_USER_AGENT = stringPreferencesKey("subscription_user_agent")
        private val NOTIFICATION_PROMPT_SHOWN = booleanPreferencesKey("notification_prompt_shown")
        private val BATTERY_PROMPT_SHOWN = booleanPreferencesKey("battery_prompt_shown")

        private val PREFIXES = stringSetPreferencesKey("split_bypass_prefixes")
        private val PREFIX_BYPASS = booleanPreferencesKey("split_prefix_bypass")
        private val FORCE_TUNNEL = stringSetPreferencesKey("split_force_tunnel")

        private val GEO_AUTO = booleanPreferencesKey("geo_auto")
        private val GEOIP_URL = stringPreferencesKey("geoip_url")
        private val GEOSITE_URL = stringPreferencesKey("geosite_url")
        private val GEO_INTERVAL = intPreferencesKey("geo_interval")
        private val GEO_UNMETERED_ONLY = booleanPreferencesKey("geo_unmetered_only")
        private const val GEO_INTERVAL_MANUAL = 0
        private val GEO_STATUS = GeoKind.entries.associateWith(::GeoStatusKeys)

        private val APP_SPLIT_KEYS = listOf(SPLIT_MODE, PACKAGES_EXCEPT, PACKAGES_ONLY, PREFIXES, PREFIX_BYPASS, FORCE_TUNNEL)
        private val RESETTABLE_KEYS = APP_SPLIT_KEYS + listOf(
            THEME, AMOLED, DESU_MODE, DYNAMIC_COLOR, SIMPLE_MODE, SHOW_SYSTEM_APPS, LAN_BYPASS, IPV4_ONLY, DNS_REMOTE, SUB_USER_AGENT,
            GEO_AUTO, GEOIP_URL, GEOSITE_URL, GEO_INTERVAL, GEO_UNMETERED_ONLY,
        )

        /** One instance per process: DataStore forbids two stores on the same file. */
        fun createDataStore(context: Context): DataStore<Preferences> = createDataStore(File(context.noBackupFilesDir, FILE_NAME))

        fun createDataStore(file: File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(migrations = listOf(SharedPackagesMigration), produceFile = { file })
    }
}
