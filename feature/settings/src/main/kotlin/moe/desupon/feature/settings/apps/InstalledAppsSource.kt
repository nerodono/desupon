package moe.desupon.feature.settings.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.desupon.feature.settings.logic.InstalledApp

interface InstalledAppsSource {
    /** Every installed app except this one, which always goes through the tunnel. */
    suspend fun load(): List<InstalledApp>

    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap?
}

/** Needs `QUERY_ALL_PACKAGES` in the app manifest to see beyond visible packages. */
class PackageManagerAppsSource(private val context: Context) : InstalledAppsSource {
    private val pm: PackageManager = context.packageManager
    private val icons = LruCache<String, ImageBitmap>(ICON_CACHE)

    override suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launchable = launcherActivities().mapTo(HashSet()) { it.activityInfo.packageName }
        installedApplications()
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString().ifBlank { info.packageName },
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.packageName !in launchable,
                )
            }
    }

    override suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? {
        val key = "$packageName@$sizePx"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap().also { icons.put(key, it) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

    private fun launcherActivities(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }

    private companion object {
        const val ICON_CACHE = 256
    }
}
