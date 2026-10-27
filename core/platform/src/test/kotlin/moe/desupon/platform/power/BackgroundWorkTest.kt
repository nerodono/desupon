package moe.desupon.platform.power

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackgroundWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `exemption follows the power manager and is requested for this package`() {
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BackgroundWork.isExempt(context))
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BackgroundWork.isExempt(context))

        val request = BackgroundWork.exemptionRequest(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.action)
        assertEquals("package:${context.packageName}", request.dataString)
    }

    @Test
    fun `autostart applies to honor and huawei only`() {
        for (m in listOf("HONOR", "hihonor", "HUAWEI")) assertTrue(BackgroundWork.needsAutostart(m), m)
        for (m in listOf("Google", "samsung", "Xiaomi")) assertFalse(BackgroundWork.needsAutostart(m), m)
    }

    @Test
    fun `autostart opens the vendor list when it resolves, else the app settings`() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, BackgroundWork.autostartIntent(context).action)
        assertEquals("package:${context.packageName}", BackgroundWork.autostartIntent(context).dataString)

        val honor = ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(honor)
            addIntentFilterForActivity(honor, IntentFilter())
        }
        assertEquals(honor, BackgroundWork.autostartIntent(context).component)
    }
}
