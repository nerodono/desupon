package moe.desupon.data.ruleset

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.desupon.config.LocalRuleSet
import java.io.File
import java.io.IOException

/**
 * Bundled `.srs` rule-sets, copied from assets to [dir] because the core loads rule-sets from file paths only.
 * Names are the [moe.desupon.model.routing.SplitRule.value] of rule-set rules.
 */
class RuleSetStore(private val assets: AssetManager, private val dir: File) {
    private class Bundled(val name: String, val domainBased: Boolean)

    /** Copies missing or outdated files and returns every installed rule-set by name. */
    suspend fun install(): Map<String, LocalRuleSet> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        for (set in BUNDLED) {
            val bytes = assets.open("$ASSET_DIR/${set.name}$EXT").use { it.readBytes() }
            val target = file(set.name)
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
            val tmp = File(dir, "${set.name}$EXT.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot install rule-set ${set.name}")
            }
        }
        installed()
    }

    /** Rule-sets present on disk, without touching assets. */
    fun installed(): Map<String, LocalRuleSet> =
        BUNDLED.filter { file(it.name).isFile }
            .associate { it.name to LocalRuleSet(file(it.name).absolutePath, it.domainBased) }

    val names: List<String> get() = BUNDLED.map { it.name }

    private fun file(name: String) = File(dir, "$name$EXT")

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
    }
}
