package moe.desupon.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import moe.desupon.feature.home.HomeScreen
import moe.desupon.feature.home.SimpleHomeScreen
import moe.desupon.feature.settings.ConfigScreen
import moe.desupon.feature.settings.SettingsScreen
import moe.desupon.feature.settings.SplitTunnelScreen
import moe.desupon.feature.settings.logs.CoreLogScreen
import moe.desupon.feature.subscriptions.SimpleImportPrompt
import moe.desupon.feature.subscriptions.SubscriptionImportRequests
import moe.desupon.feature.subscriptions.SubscriptionsScreen
import moe.desupon.ui.theme.DesuponGlyphs
import org.koin.compose.koinInject

enum class TopDestination(val route: String, @StringRes val label: Int) {
    HOME("home", R.string.nav_home),
    SUBSCRIPTIONS("subscriptions", R.string.nav_subscriptions),
    SETTINGS("settings", R.string.nav_settings),
}

private const val ROUTE_SPLIT = "settings/split"
private const val ROUTE_CONFIG = "settings/config"
private const val ROUTE_LOGS = "settings/logs"

private val TopDestination.icon: ImageVector
    get() = when (this) {
        TopDestination.HOME -> DesuponGlyphs.Sprout
        TopDestination.SUBSCRIPTIONS -> DesuponGlyphs.WateringCan
        TopDestination.SETTINGS -> Icons.Filled.Settings
    }

private fun NavController.navigateTop(dest: TopDestination) = navigate(dest.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Top-level destinations get the adaptive navigation bar; detail screens take the whole window. */
@Composable
fun DesuponNav(requests: Flow<TopDestination>) {
    val nav = rememberNavController()
    val imports = koinInject<SubscriptionImportRequests>()
    val entry by nav.currentBackStackEntryAsState()
    val current = TopDestination.entries.firstOrNull { it.route == entry?.destination?.route }

    LaunchedEffect(nav, requests) {
        requests.collect { nav.navigateTop(it) }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { d ->
                item(
                    selected = d == current,
                    onClick = { nav.navigateTop(d) },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(stringResource(d.label)) },
                )
            }
        },
        layoutType = if (current == null) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())
        },
    ) {
        NavHost(nav, startDestination = TopDestination.HOME.route) {
            composable(TopDestination.HOME.route) {
                Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    HomeScreen(
                        onAddSubscription = {
                            imports.openAdd()
                            nav.navigateTop(TopDestination.SUBSCRIPTIONS)
                        },
                        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                        onManageSets = {
                            imports.openSets()
                            nav.navigateTop(TopDestination.SUBSCRIPTIONS)
                        },
                    )
                }
            }
            composable(TopDestination.SUBSCRIPTIONS.route) {
                SubscriptionsScreen(Modifier.fillMaxSize())
            }
            composable(TopDestination.SETTINGS.route) {
                Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    SettingsScreen(
                        onOpenSplitTunnel = { nav.navigate(ROUTE_SPLIT) },
                        onOpenConfig = { nav.navigate(ROUTE_CONFIG) },
                        onOpenLogs = { nav.navigate(ROUTE_LOGS) },
                        contentPadding = padding,
                    )
                }
            }
            composable(ROUTE_SPLIT) { SplitTunnelScreen(onBack = { nav.popBackStack() }) }
            composable(ROUTE_CONFIG) { ConfigScreen(onBack = { nav.popBackStack() }) }
            composable(ROUTE_LOGS) { CoreLogScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Simple mode's whole UI: no navigation, so only the screen's hidden exit leads anywhere else; shared links get a yes/no prompt. */
@Composable
fun SimpleModeRoot(onExit: () -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        SimpleHomeScreen(onExit = onExit, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
        SimpleImportPrompt()
    }
}
