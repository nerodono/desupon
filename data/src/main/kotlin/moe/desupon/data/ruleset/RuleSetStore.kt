package moe.desupon.data.ruleset

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.desupon.config.LocalRuleSet
import moe.desupon.data.geo.GeoCategory
import moe.desupon.data.geo.GeoDataFiles
import moe.desupon.data.geo.GeoKind
import java.io.File
import java.io.IOException

/** A rule-set the editor can offer; [code] is the v2ray category of a downloaded one, [count] its entries when known. */
data class RuleSetInfo(
    val name: String,
    val code: String?,
    val source: Source,
    val count: Int?,
) {
    enum class Source { BUNDLED, DOWNLOADED }

    val domainBased: Boolean get() = name.startsWith(GeoKind.GEOSITE.id)

    /** Large enough to cost noticeable memory and conversion time. */
    val huge: Boolean get() = (count ?: 0) > HUGE_ENTRIES

    companion object {
        const val HUGE_ENTRIES = 200_000
    }
}

/**
 * Rule-sets by the [moe.desupon.model.routing.SplitRule.value] of rule-set rules: bundled `.srs` copied to [dir], since the core loads
 * rule-sets from paths only, each overridden by the converted category of the same name while a downloaded database lists it.
 */
class RuleSetStore(private val assets: AssetManager, private val dir: File, private val geo: GeoDataFiles) {
    private class Bundled(val name: String, val domainBased: Boolean)

    private val lock = Mutex()

    /** The app and the VPN service both install, and two writers of one temporary file could rename a partial set into place. */
    private val installing = Mutex()
    private val _downloaded = MutableStateFlow<Map<GeoKind, List<GeoCategory>>>(emptyMap())
    private val _sets = MutableStateFlow(resolve(emptyMap()))
    private val _available = MutableStateFlow(infos(emptyMap()))

    /** Rule-sets with a file on disk, the ones a config may reference. */
    val sets: StateFlow<Map<String, LocalRuleSet>> = _sets.asStateFlow()

    /** Bundled sets first, then every category of the downloaded databases, converted or not. */
    val available: StateFlow<List<RuleSetInfo>> = _available.asStateFlow()

    val downloaded: StateFlow<Map<GeoKind, List<GeoCategory>>> = _downloaded.asStateFlow()

    /** Copies missing or outdated bundled files, then re-reads the downloaded side; returns [sets]. */
    suspend fun install(): Map<String, LocalRuleSet> = withContext(Dispatchers.IO) {
        try {
            installing.withLock { copyBundled() }
        } finally {
            refresh()
        }
        sets.value
    }

    private fun copyBundled() {
        dir.mkdirs()
        for (set in BUNDLED) {
            val bytes = assets.open("$ASSET_DIR/${set.name}$EXT").use { it.readBytes() }
            val target = bundledFile(set.name)
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
            val tmp = File(dir, "${set.name}$EXT.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot install rule-set ${set.name}")
            }
        }
    }

    /** Re-reads the category indexes and the converted files. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            val read = GeoKind.entries.associateWith(geo::readIndex)
            _downloaded.value = read
            _sets.value = resolve(read)
            _available.value = infos(read)
        }
    }

    private fun resolve(read: Map<GeoKind, List<GeoCategory>>): Map<String, LocalRuleSet> {
        val out = LinkedHashMap<String, LocalRuleSet>()
        for (set in BUNDLED) {
            val f = bundledFile(set.name)
            if (f.isFile) out[set.name] = LocalRuleSet(f.absolutePath, set.domainBased)
        }
        for ((kind, categories) in read) {
            for (c in categories) {
                val f = geo.set(c.name)
                if (f.isFile) out[c.name] = LocalRuleSet(f.absolutePath, domainBased = kind == GeoKind.GEOSITE)
            }
        }
        return out
    }

    private fun infos(read: Map<GeoKind, List<GeoCategory>>): List<RuleSetInfo> {
        val all = GeoKind.entries.flatMap { read[it].orEmpty() }
        val byName = all.associateBy { it.name }
        val bundled = BUNDLED.map { b ->
            val d = byName[b.name]
            RuleSetInfo(b.name, d?.code, RuleSetInfo.Source.BUNDLED, d?.count)
        }
        return bundled + all.filter { it.name !in BUNDLED_NAMES }.map { RuleSetInfo(it.name, it.code, RuleSetInfo.Source.DOWNLOADED, it.count) }
    }

    private fun bundledFile(name: String) = File(dir, "$name$EXT")

    companion object {
        const val GEOSITE_CATEGORY_RU = "geosite-category-ru"
        const val GEOSITE_TLD_RU = "geosite-tld-ru"
        const val GEOIP_RU = "geoip-ru"
        const val ASSET_DIR = "ruleset"
        private const val EXT = ".srs"

        private val BUNDLED = listOf(
            Bundled(GEOSITE_CATEGORY_RU, domainBased = true),
            Bundled(GEOSITE_TLD_RU, domainBased = true),
            Bundled(GEOIP_RU, domainBased = false),
        )
        private val BUNDLED_NAMES = BUNDLED.mapTo(HashSet()) { it.name }
    }
}
