package moe.desupon.data.ruleset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import moe.desupon.config.LocalRuleSet
import moe.desupon.data.geo.GeoCategory
import moe.desupon.data.geo.GeoDataFiles
import moe.desupon.data.geo.GeoKind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RuleSetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir get() = File(tmp.root, "ruleset")
    private val geo get() = GeoDataFiles(File(tmp.root, "geodata"))

    private fun store() = RuleSetStore(context.assets, dir, geo)

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun index(kind: GeoKind, vararg categories: GeoCategory) {
        geo.root.mkdirs()
        geo.index(kind).writeText(GeoDataFiles.JSON.encodeToString(categories.toList()))
    }

    private fun converted(vararg names: String) {
        geo.sets.mkdirs()
        names.forEach { geo.set(it).writeText("SRS") }
    }

    @Test
    fun installCopiesBundledSetsMatchingTheNotice() = runTest {
        val store = store()
        assertTrue(store.sets.value.isEmpty())
        assertEquals(
            listOf(RuleSetStore.GEOSITE_CATEGORY_RU, RuleSetStore.GEOSITE_TLD_RU, RuleSetStore.GEOIP_RU),
            store.available.value.map { it.name },
            "bundled sets are listed before anything is installed",
        )

        val sets = store.install()
        assertEquals(store.available.value.map { it.name }.toSet(), sets.keys)
        assertEquals(sets, store.sets.value)
        assertEquals(LocalRuleSet(File(dir, "geoip-ru.srs").absolutePath, domainBased = false), sets[RuleSetStore.GEOIP_RU])
        assertTrue(sets[RuleSetStore.GEOSITE_CATEGORY_RU]!!.domainBased)

        val notice = context.assets.open("ruleset/NOTICE").bufferedReader().readText()
        for (set in sets.values) {
            val f = File(set.path)
            assertEquals("SRS", String(f.readBytes().copyOf(3)))
            assertTrue(sha256(f) in notice, "checksum of ${f.name} is not in NOTICE")
        }
    }

    @Test
    fun installRepairsTamperedFilesAndIsIdempotent() = runTest {
        val store = store()
        val path = store.install().getValue(RuleSetStore.GEOSITE_TLD_RU).path
        val original = File(path).readBytes()

        File(path).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(path, store.install().getValue(RuleSetStore.GEOSITE_TLD_RU).path)
        assertTrue(original.contentEquals(File(path).readBytes()))
        assertEquals(store.install(), store.sets.value)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun convertedCategoriesOverrideBundledOnesWhileTheirDatabaseListsThem() = runTest {
        index(
            GeoKind.GEOIP,
            GeoCategory("RU", "geoip-ru", 25_107),
            GeoCategory("RU-BLOCKED", "geoip-ru-blocked", 88_587),
        )
        index(
            GeoKind.GEOSITE,
            GeoCategory("CATEGORY-RU", "geosite-category-ru", 1_103),
            GeoCategory("GEOLOCATION-!CN", "geosite-geolocation-not-cn", 5),
            GeoCategory("RU-BLOCKED-ALL", "geosite-ru-blocked-all", 1_374_049),
        )
        converted("geoip-ru", "geosite-geolocation-not-cn")
        val store = store()
        store.install()

        val sets = store.sets.value
        assertEquals(LocalRuleSet(geo.set("geoip-ru").absolutePath, domainBased = false), sets["geoip-ru"])
        assertEquals(LocalRuleSet(File(dir, "geosite-category-ru.srs").absolutePath), sets["geosite-category-ru"], "not converted: bundled")
        assertEquals(LocalRuleSet(geo.set("geosite-geolocation-not-cn").absolutePath), sets["geosite-geolocation-not-cn"])
        assertFalse("geoip-ru-blocked" in sets, "listed but not converted")

        val available = store.available.value
        assertEquals(
            listOf(
                "geosite-category-ru", "geosite-tld-ru", "geoip-ru",
                "geoip-ru-blocked", "geosite-geolocation-not-cn", "geosite-ru-blocked-all",
            ),
            available.map { it.name },
        )
        assertEquals(RuleSetInfo("geoip-ru", "RU", RuleSetInfo.Source.BUNDLED, 25_107), available[2])
        assertEquals(RuleSetInfo("geosite-tld-ru", null, RuleSetInfo.Source.BUNDLED, null), available[1])
        assertEquals(RuleSetInfo("geoip-ru-blocked", "RU-BLOCKED", RuleSetInfo.Source.DOWNLOADED, 88_587), available[3])
        assertFalse(available[3].domainBased)
        assertTrue(available[4].domainBased)
        assertEquals(listOf("geosite-ru-blocked-all"), available.filter { it.huge }.map { it.name })
        assertEquals(listOf(5, 1_374_049), store.downloaded.value.getValue(GeoKind.GEOSITE).drop(1).map { it.count })
    }

    @Test
    fun bundledSetIsTheFallbackOnceTheDatabaseDropsTheCategory() = runTest {
        index(GeoKind.GEOIP, GeoCategory("RU", "geoip-ru", 1))
        converted("geoip-ru")
        val store = store()
        store.install()
        assertEquals(geo.set("geoip-ru").absolutePath, store.sets.value.getValue("geoip-ru").path)

        index(GeoKind.GEOIP, GeoCategory("RU-BLOCKED", "geoip-ru-blocked", 1))
        store.refresh()
        assertEquals(File(dir, "geoip-ru.srs").absolutePath, store.sets.value.getValue("geoip-ru").path)

        geo.index(GeoKind.GEOIP).writeText("{not json")
        store.refresh()
        assertEquals(listOf("geosite-category-ru", "geosite-tld-ru", "geoip-ru"), store.available.value.map { it.name })
        assertEquals(setOf("geosite-category-ru", "geosite-tld-ru", "geoip-ru"), store.sets.value.keys)
    }
}
