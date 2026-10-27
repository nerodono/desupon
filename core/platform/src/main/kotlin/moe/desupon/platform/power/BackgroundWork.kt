package moe.desupon.platform.power

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/** System screens that keep the app running in the background: the battery-optimization exemption and vendor autostart lists. */
object BackgroundWork {
    private val AUTOSTART_VENDORS = setOf("hihonor", "honor", "huawei")

    private val AUTOSTART_SCREENS = listOf(
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** A VPN has to keep running with the screen off, which is what this exemption is for. */
    @SuppressLint("BatteryLife")
    fun exemptionRequest(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))

    fun needsAutostart(manufacturer: String = Build.MANUFACTURER): Boolean = manufacturer.lowercase(Locale.ROOT) in AUTOSTART_VENDORS

    /** The vendor's autostart list when installed, else the app's system settings page. */
    fun autostartIntent(context: Context): Intent =
        AUTOSTART_SCREENS.map { Intent().setComponent(it) }.firstOrNull { context.packageManager.queryIntentActivities(it, 0).isNotEmpty() }
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
