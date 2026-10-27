package moe.desupon.data.ruleset

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import moe.desupon.config.LocalRuleSet
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RuleSetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun installCopiesBundledSetsMatchingTheNotice() = runTest {
        val dir = File(tmp.root, "ruleset")
        val store = RuleSetStore(context.assets, dir)
        assertTrue(store.installed().isEmpty())

        val sets = store.install()
        assertEquals(store.names.toSet(), sets.keys)
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
        val dir = File(tmp.root, "ruleset")
        val store = RuleSetStore(context.assets, dir)
        val path = store.install().getValue(RuleSetStore.GEOSITE_TLD_RU).path
        val original = File(path).readBytes()

        File(path).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(path, store.install().getValue(RuleSetStore.GEOSITE_TLD_RU).path)
        assertTrue(original.contentEquals(File(path).readBytes()))
        assertEquals(store.install(), store.installed())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
