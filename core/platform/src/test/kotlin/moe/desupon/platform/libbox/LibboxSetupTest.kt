package moe.desupon.platform.libbox

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibboxSetupTest {
    @Test
    fun fixAndroidStackFollowsAffectedLevels() {
        assertTrue(LibboxSetup.fixAndroidStack(sdk = 24))
        assertTrue(LibboxSetup.fixAndroidStack(sdk = 25))
        assertFalse(LibboxSetup.fixAndroidStack(sdk = 26))
        assertFalse(LibboxSetup.fixAndroidStack(sdk = 27))
        assertTrue(LibboxSetup.fixAndroidStack(sdk = 27, debug = true))
        assertTrue(LibboxSetup.fixAndroidStack(sdk = 28))
        assertTrue(LibboxSetup.fixAndroidStack(sdk = 37))
    }

    @Test
    fun scrubRemovesEverySnapshotButKeepsReports() {
        val box = Files.createTempDirectory("box").toFile()
        try {
            val report = box.resolve("crash_reports/2026-01-01T00-00-00").apply { mkdirs() }
            val draft = box.resolve("power_reports/draft").apply { mkdirs() }
            listOf(box.resolve("configuration.json"), report.resolve("configuration.json"), draft.resolve("configuration.json"))
                .forEach { it.writeText("{\"private_key\":\"synthetic\"}") }
            report.resolve("go.log").writeText("panic")

            LibboxSetup.scrubConfigSnapshots(box)

            assertEquals(emptyList(), box.walkTopDown().filter { it.name == "configuration.json" }.toList())
            assertTrue(report.resolve("go.log").isFile)
        } finally {
            box.deleteRecursively()
        }
    }
}
