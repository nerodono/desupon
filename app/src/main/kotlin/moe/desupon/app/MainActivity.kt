package moe.desupon.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.data.settings.ThemePreference
import moe.desupon.feature.subscriptions.SubscriptionImportRequests
import moe.desupon.platform.power.BackgroundWork
import moe.desupon.ui.theme.DesuponTheme
import moe.desupon.ui.theme.ThemeMode
import moe.desupon.vpn.tile.DesuponTileService
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()
    private val vpn: VpnControl by inject()
    private val imports: SubscriptionImportRequests by inject()

    private val destinations = Channel<TopDestination>(Channel.CONFLATED)
    private val destinationRequests = destinations.receiveAsFlow()

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) connect() else toast(R.string.consent_denied)
    }

    private var notificationAnswer: CompletableDeferred<Unit>? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAnswer?.complete(Unit)
    }

    private var batteryAnswer: CompletableDeferred<Unit>? = null
    private val batteryExemption = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryAnswer?.complete(Unit)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        runConnectPrompts()

        setContent {
            val ui by settings.ui.collectAsStateWithLifecycle(initialValue = null)
            val u = ui ?: return@setContent
            val mode = when (u.theme) {
                ThemePreference.SYSTEM -> ThemeMode.System
                ThemePreference.LIGHT -> ThemeMode.Light
                ThemePreference.DARK -> ThemeMode.Dark
            }
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            DesuponTheme(themeMode = mode, amoled = u.amoled, desuMode = u.desuMode && !u.simpleMode, dynamicColor = u.dynamicColor) {
                if (u.simpleMode) {
                    SimpleModeRoot(onExit = { lifecycleScope.launch { settings.setSimpleMode(false) } })
                } else {
                    DesuponNav(requests = destinationRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) {
                imports.prefill(text)
                destinations.trySend(TopDestination.SUBSCRIPTIONS)
            }
            return
        }
        if (intent.getBooleanExtra(DesuponTileService.EXTRA_REQUEST_CONNECT, false)) {
            intent.removeExtra(DesuponTileService.EXTRA_REQUEST_CONNECT)
            destinations.trySend(TopDestination.HOME)
            requestConsentThenConnect()
        }
    }

    /**
     * The tile opens the app only when consent is missing; with consent already held the request is ignored,
     * so another app cannot start a connection through this exported activity without the system dialog.
     */
    private fun requestConsentThenConnect() {
        val dialog = VpnService.prepare(this) ?: return
        try {
            consent.launch(dialog)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.consent_unavailable)
        }
    }

    private fun connect() {
        lifecycleScope.launch { vpn.connect() }
    }

    private fun runConnectPrompts() {
        val prompts = ConnectPrompts(notifications = NotificationPrompt(), battery = BatteryPrompt())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { prompts.run(vpn.state) }
        }
    }

    private inner class NotificationPrompt : ConnectPrompts.Prompt {
        override suspend fun due(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return !granted && !settings.notificationPromptShown.first()
        }

        override suspend fun markAsked() = settings.setNotificationPromptShown()

        override suspend fun ask() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val answer = CompletableDeferred<Unit>().also { notificationAnswer = it }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            answer.await()
        }
    }

    private inner class BatteryPrompt : ConnectPrompts.Prompt {
        override suspend fun due(): Boolean = !BackgroundWork.isExempt(this@MainActivity) && !settings.batteryPromptShown.first()

        override suspend fun markAsked() = settings.setBatteryPromptShown()

        override suspend fun ask() {
            val answer = CompletableDeferred<Unit>().also { batteryAnswer = it }
            try {
                batteryExemption.launch(BackgroundWork.exemptionRequest(this@MainActivity))
            } catch (_: ActivityNotFoundException) {
                return
            }
            answer.await()
        }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
