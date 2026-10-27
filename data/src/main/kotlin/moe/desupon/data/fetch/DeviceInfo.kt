package moe.desupon.data.fetch

import android.os.Build

/** Values of the device headers some panels require for HWID limits. */
data class DeviceInfo(
    val os: String = "Android",
    val osVersion: String,
    val model: String,
) {
    companion object {
        fun current() = DeviceInfo(osVersion = Build.VERSION.RELEASE.orEmpty(), model = Build.MODEL.orEmpty())
    }
}
