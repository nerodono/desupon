package moe.desupon.vpn.split

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import moe.desupon.brain.port.LibboxControl
import moe.desupon.data.split.AppBypass
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode
import moe.desupon.platform.libbox.PackageOverrides

/** Per-app split resolved against installed packages, with the own package always inside the tunnel. */
object InstalledSplit {
    /** [installedPackages] is listed only when a prefix rule needs it. */
    fun filter(
        split: AppSplit,
        ownPackage: String,
        isInstalled: (String) -> Boolean,
        installedPackages: () -> Collection<String> = ::emptyList,
    ): AppSplit {
        fun installed(packages: Collection<String>) = packages.filter { it.isNotBlank() && it != ownPackage && isInstalled(it) }
        return when (split.mode) {
            SplitMode.ALL -> AppSplit(SplitMode.ALL, emptySet())
            SplitMode.ONLY_SELECTED -> AppSplit(SplitMode.ONLY_SELECTED, installed(split.packages).toSet() + ownPackage)
            SplitMode.ALL_EXCEPT_SELECTED -> {
                val prefixed = if (split.bypassPrefixes.isEmpty()) emptyList() else installedPackages().filter { AppBypass.matchesPrefix(it, split.bypassPrefixes) }
                AppSplit(SplitMode.ALL_EXCEPT_SELECTED, AppBypass.bypassed(split, installed(split.packages) + prefixed, ownPackage))
            }
        }
    }

    fun overrides(
        split: AppSplit,
        ownPackage: String,
        isInstalled: (String) -> Boolean,
        installedPackages: () -> Collection<String> = ::emptyList,
    ): PackageOverrides = PackageOverrides.of(filter(split, ownPackage, isInstalled, installedPackages), ownPackage)

    /** Whether newly installing [packageName] changes what [split] resolves to. */
    fun affects(split: AppSplit, packageName: String, ownPackage: String): Boolean = when (split.mode) {
        SplitMode.ALL -> false
        SplitMode.ONLY_SELECTED -> packageName in split.packages && packageName != ownPackage
        SplitMode.ALL_EXCEPT_SELECTED -> AppBypass.route(split, packageName, ownPackage).bypasses
    }
}

/** Visibility of other packages needs `QUERY_ALL_PACKAGES` in the app manifest; invisible ones count as not installed. */
class InstalledApps(context: Context) {
    private val packages = context.applicationContext.packageManager

    fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getApplicationInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun installedPackages(): List<String> {
        val apps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getInstalledApplications(0)
        }
        return apps.map { it.packageName }
    }
}

/** `VpnService.Builder.addDisallowedApplication` throws on unknown packages, so the core only ever sees installed ones. */
class InstalledOnlyLibbox(
    private val delegate: LibboxControl,
    private val ownPackage: String,
    private val isInstalled: (String) -> Boolean,
    private val installedPackages: () -> Collection<String> = ::emptyList,
) : LibboxControl by delegate {
    override suspend fun reload(configJson: String, apps: AppSplit) =
        delegate.reload(configJson, InstalledSplit.filter(apps, ownPackage, isInstalled, installedPackages))
}
