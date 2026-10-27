package moe.desupon.data.geo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A v2ray database; [id] is the kind the core's bridge takes and the prefix of its rule-set names. */
enum class GeoKind(val id: String) {
    GEOIP("geoip"),
    GEOSITE("geosite"),
}

data class GeoDataSettings(
    val auto: Boolean = true,
    /** Null means [DEFAULT_GEOIP_URL]. */
    val geoipUrl: String? = null,
    /** Null means [DEFAULT_GEOSITE_URL]. */
    val geositeUrl: String? = null,
    /** Days between automatic checks; null checks only on request. */
    val intervalDays: Int? = DEFAULT_INTERVAL_DAYS,
    /** Automatic downloads wait for an unmetered network; the pair weighs about 92 MB. */
    val unmeteredOnly: Boolean = true,
) {
    fun url(kind: GeoKind): String = when (kind) {
        GeoKind.GEOIP -> geoipUrl ?: DEFAULT_GEOIP_URL
        GeoKind.GEOSITE -> geositeUrl ?: DEFAULT_GEOSITE_URL
    }

    companion object {
        const val DEFAULT_GEOIP_URL = "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geoip.dat"
        const val DEFAULT_GEOSITE_URL = "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geosite.dat"
        const val DEFAULT_INTERVAL_DAYS = 5
        val INTERVAL_PRESETS = listOf(1, 3, 5, 7, 14)

        fun defaultUrl(kind: GeoKind): String = GeoDataSettings().url(kind)
    }
}

/** Downloads of one database and the rule-sets converted from it. */
data class GeoFileStatus(
    /** Where the file on disk and [etag] came from. */
    val url: String? = null,
    val etag: String? = null,
    val sha256: String? = null,
    /** Last time the server confirmed or replaced the file. */
    val lastOkAtMs: Long? = null,
    /** When the file on disk was downloaded. */
    val updatedAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    /** Stable code of the last failed download, see [GeoDataUpdater]; null after a success. */
    val lastError: String? = null,
    /** The address that failed with [lastError]. */
    val errorUrl: String? = null,
    /** [sha256] of the database all converted sets of this kind come from; any other value makes them stale. */
    val setsSha256: String? = null,
    /** Start of a conversion that failed or never finished; null once one succeeds. */
    val convertFailedAtMs: Long? = null,
)

/** One category of a downloaded database; [name] is its rule-set name, [count] its entries. */
@Serializable
data class GeoCategory(val code: String, val name: String, val count: Int)

@Serializable
internal data class ConvertedSet(val name: String, val count: Int, val skipped: Int)

/** The core's .dat reader and `.srs` writer, JSON in and out as native/bridge/geodata.go defines; failures are thrown. */
interface GeoDataBridge {
    fun index(datPath: String, kind: String): String

    fun convert(datPath: String, kind: String, namesJson: String, outDir: String): String
}

/** Whether the default non-VPN network is metered; null while there is none. */
fun interface MeteredNetwork {
    suspend fun metered(): Boolean?
}

/** Downloaded databases, their category indexes and the rule-sets converted from them. */
class GeoDataFiles(val root: File) {
    val sets: File get() = File(root, "sets")

    fun dat(kind: GeoKind) = File(root, "${kind.id}.dat")

    fun index(kind: GeoKind) = File(root, "${kind.id}.json")

    fun set(name: String) = File(sets, "$name$SET_EXT")

    /** The persisted category index of [kind]; empty when missing or unreadable. */
    fun readIndex(kind: GeoKind): List<GeoCategory> = try {
        index(kind).takeIf { it.isFile }?.let { JSON.decodeFromString<List<GeoCategory>>(it.readText()) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    fun convertedNames(): Set<String> =
        sets.listFiles { f -> f.isFile && f.name.endsWith(SET_EXT) }.orEmpty().mapTo(HashSet()) { it.name.removeSuffix(SET_EXT) }

    companion object {
        const val DIR = "geodata"
        const val SET_EXT = ".srs"

        internal val JSON = Json { ignoreUnknownKeys = true }
    }
}
