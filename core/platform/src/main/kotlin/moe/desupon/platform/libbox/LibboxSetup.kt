package moe.desupon.platform.libbox

import android.content.Context
import android.os.Build
import moe.desupon.core.libbox.Libbox
import moe.desupon.core.libbox.SetupOptions
import moe.desupon.platform.log.CoreException
import java.io.File

/** Memory guard of the core; defaults keep the core's own killer off. */
data class OomOptions(
    val killerEnabled: Boolean = false,
    val killerDisabled: Boolean = true,
    val memoryLimitMb: Int = 50,
)

/** One-time process setup of the core: private, non-backed-up paths and a unix-socket command server. */
object LibboxSetup {
    private const val BOX_DIR = "box"
    private const val CONFIG_SNAPSHOT = "configuration.json"
    internal const val LOG_MAX_LINES = 3000

    @Volatile
    private var done = false

    /** Base and working path of the core; the core writes its config snapshot and crash output here. */
    fun boxDir(context: Context): File = File(context.noBackupFilesDir, BOX_DIR)

    fun ensure(context: Context, oom: OomOptions = OomOptions(), debug: Boolean = false) {
        if (done) return
        synchronized(this) {
            if (done) return
            val box = boxDir(context).apply { mkdirs() }
            // Setup archives a crash log together with any config snapshot it finds.
            File(box, CONFIG_SNAPSHOT).delete()
            try {
                Libbox.setup(options(box.path, context.cacheDir.path, oom, debug))
            } catch (e: Exception) {
                throw CoreException.from(e)
            } finally {
                scrubConfigSnapshots(box)
            }
            done = true
        }
    }

    fun reloadOom(context: Context, oom: OomOptions, debug: Boolean = false) {
        Libbox.reloadSetupOptions(options(boxDir(context).path, context.cacheDir.path, oom, debug))
    }

    /** Removes a config snapshot, which would hold node keys, should the core have written one. */
    fun dropConfigSnapshot(context: Context) {
        File(boxDir(context), CONFIG_SNAPSHOT).delete()
    }

    /** Deletes every config snapshot under [box], crash and power reports included. */
    internal fun scrubConfigSnapshots(box: File) {
        box.walkTopDown().filter { it.isFile && it.name == CONFIG_SNAPSHOT }.forEach { it.delete() }
    }

    /** golang/go#68760: Go entered from small Java thread stacks overflows on these API levels. */
    fun fixAndroidStack(sdk: Int = Build.VERSION.SDK_INT, debug: Boolean = false): Boolean =
        debug || sdk in Build.VERSION_CODES.N..Build.VERSION_CODES.N_MR1 || sdk >= Build.VERSION_CODES.P

    private fun options(box: String, temp: String, oom: OomOptions, debug: Boolean) = SetupOptions().also {
        it.basePath = box
        it.workingPath = box
        it.tempPath = temp
        it.fixAndroidStack = fixAndroidStack(debug = debug)
        // Port 0 selects the unix socket under basePath; any other value opens a TCP listener.
        it.commandServerListenPort = 0
        it.logMaxLines = LOG_MAX_LINES.toLong()
        it.debug = debug
        it.crashReportSource = "app"
        it.oomKillerEnabled = oom.killerEnabled
        it.oomKillerDisabled = oom.killerDisabled
        it.oomMemoryLimit = oom.memoryLimitMb.toLong() * 1024L * 1024L
        it.powerReportEnabled = false
    }
}
