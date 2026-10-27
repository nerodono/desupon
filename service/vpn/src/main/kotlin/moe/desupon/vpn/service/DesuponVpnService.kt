package moe.desupon.vpn.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.desupon.brain.FailReason
import moe.desupon.brain.SelectorBrain
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SocketProtector
import moe.desupon.brain.probe.PoolProbes
import moe.desupon.config.LocalRuleSet
import moe.desupon.data.repo.ProbeStatRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.model.node.Node
import moe.desupon.platform.iface.VpnHost
import moe.desupon.platform.libbox.CoreRuntime
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.vpn.R
import moe.desupon.vpn.lifecycle.AutoRetry
import moe.desupon.vpn.lifecycle.VpnFailReason
import moe.desupon.vpn.lifecycle.VpnStateHolder
import moe.desupon.vpn.lifecycle.WakeDetector
import moe.desupon.vpn.notify.StatusContent
import moe.desupon.vpn.notify.StatusFeed
import moe.desupon.vpn.notify.StatusNotification
import moe.desupon.vpn.notify.subscriptionOf
import moe.desupon.vpn.notify.warmRtt
import moe.desupon.vpn.split.InstalledSplit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one brain session per start; every choice is made here, so always-on starts need no UI.
 * Stops itself when the brain fails, the core asks, consent is revoked or the user disconnects; in simple mode a failure is retried
 * instead, see [AutoRetry].
 */
class DesuponVpnService : VpnService(), VpnHost, KoinComponent {
    private val holder: VpnStateHolder by inject()
    private val runtime: CoreRuntime by inject()
    private val observer: NetworkObserver by inject()
    private val brains: BrainFactory by inject()
    private val ruleSetStore: RuleSetStore by inject()
    private val probes: PoolProbes by inject()
    private val settings: SettingsPort by inject()
    private val preferences: SettingsRepository by inject()
    private val libbox: LibboxControl by inject()
    private val probeStats: ProbeStatRepository by inject()
    private val subscriptions: SubscriptionRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notification: StatusNotification

    private var session: Job? = null
    private var brain: SelectorBrain? = null
    private var brainScope: CoroutineScope? = null
    private var teardown: Job? = null
    private var restartAfterStop = false
    private var brainWasActive = false
    private var destroyed = false
    private var autoRetry: AutoRetry? = null
    private var stopRequested = false
    private val screenOn = MutableStateFlow(true)
    private var screenReceiver: BroadcastReceiver? = null
    private var feed: Job? = null
    private val receivers = mutableListOf<BroadcastReceiver>()
    private var wakeWatch: Job? = null
    private var wakeReceiver: BroadcastReceiver? = null
    private val wakeChecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** How long the device slept, on each wake from a sleep long enough for tunnel state to die; see [WakeDetector]. */
    private val wakes = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Pause and wake reach the core in the order the system reported them. */
    private val powerCalls = Dispatchers.IO.limitedParallelism(1)

    override val sessionLabel: String get() = getString(R.string.svc_session_label)

    override fun onCreate() {
        super.onCreate()
        notification = StatusNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stop(null) else start()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stop(null)
    }

    override fun onDestroy() {
        destroyed = true
        if (session != null || autoRetry?.waiting == true) stop(null)
        stopFeed()
        stopWakeWatch()
        scope.cancel()
        super.onDestroy()
    }

    // region VpnHost

    override fun newTunBuilder(): Builder = Builder().also { builder -> openAppIntent()?.let(builder::setConfigureIntent) }

    override fun isVpnPrepared(): Boolean = prepare(this) == null

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean = super.setUnderlyingNetworks(networks)

    override fun onCoreStopRequested() {
        scope.launch { stop(null) }
    }

    // endregion

    private fun start() {
        // A foreground start must reach startForeground even when it ends right away.
        val refused = enterForeground {
            ServiceCompat.startForeground(this, StatusNotification.ID, notification.build(StatusContent.Plain(R.string.svc_state_connecting)), foregroundType())
        }
        if (refused != null) {
            if (session == null && teardown?.isActive != true) {
                autoRetry?.reset()
                holder.stopped(refused)
                stopSelf()
            }
            return
        }
        if (teardown?.isActive == true) {
            restartAfterStop = true
            return
        }
        if (session != null) return
        if (!isVpnPrepared()) {
            holder.stopped(VpnFailReason.NOT_PREPARED)
            finish()
            return
        }
        autoRetry?.cancelWait()
        if (autoRetry == null) {
            val simpleMode = preferences.simpleMode.stateIn<Boolean?>(scope, SharingStarted.Eagerly, null)
            autoRetry = AutoRetry(scope, simpleMode, observer.current.map { it?.key }, wakes)
        }
        stopRequested = false
        holder.starting()
        startFeed()
        startWakeWatch()
        holder.bindStop { scope.launch { stop(null) } }
        brainWasActive = false
        session = scope.launch { runSession() }
    }

    private suspend fun runSession() {
        try {
            autoRetry?.awaitSettings()
            withContext(Dispatchers.IO) { runtime.attach(this@DesuponVpnService) }
            val ruleSets = withContext(Dispatchers.IO) { installRuleSets() }
            val bScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { brainScope = it }
            val b = brains.create(bScope, SocketProtector(::protect), ruleSets).also { brain = it }
            holder.running()
            probes.attach(b)
            coroutineScope {
                launch { b.state.collect(::onBrainState) }
                launch { b.liveSubscriptions.collect(holder::publishLive) }
                launch { b.networkMode.collect(holder::publishMode) }
                launch { b.activeStep.collect(holder::publishStep) }
                launch {
                    observer.current
                        .map { it?.let { n -> BrainEvent.NetworkChanged(n.key, n.handle, n.dnsServers, n.ipv6) } ?: BrainEvent.NetworkLost }
                        .distinctUntilChanged()
                        .collect(b::onEvent)
                }
                launch {
                    wakes.collect { slept ->
                        runtime.log(CoreLogLevel.INFO, "woke after ${slept / 1_000} s asleep, resetting the network")
                        b.onWake()
                    }
                }
                registerReceivers(b)
                b.connect()
                awaitCancellation()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            stop(ConnState.Failed(FailReason.CORE))
        }
    }

    private suspend fun installRuleSets(): Map<String, LocalRuleSet> =
        try {
            ruleSetStore.install()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ruleSetStore.installed()
        }

    private fun onBrainState(state: ConnState) {
        holder.publish(state)
        if (state is ConnState.Connected) autoRetry?.reset()
        when {
            state is ConnState.Failed -> stop(state)
            state == ConnState.Idle && brainWasActive -> stop(null)
            state != ConnState.Idle -> brainWasActive = true
        }
    }

    /** Teardown runs outside the service scope so it completes even after onDestroy. */
    private fun stop(failure: ConnState.Failed?) {
        if (failure == null) {
            stopRequested = true
            autoRetry?.reset()
        }
        if (teardown?.isActive == true) return
        if (session == null) {
            holder.stoppedWith(failure)
            finish()
            return
        }
        val retry = autoRetry?.takeIf { it.wants(failure) && !destroyed }
        holder.stopping(retry = retry != null)
        unregisterReceivers()
        val b = brain
        b?.let(probes::detach)
        val s = session
        val bs = brainScope
        teardown = cleanup.launch {
            withContext(NonCancellable) {
                runCatching { b?.disconnect() }
                s?.cancelAndJoin()
                withContext(Dispatchers.IO) { runCatching { runtime.detach() } }
                bs?.cancel()
            }
            brain = null
            brainScope = null
            session = null
            when {
                restartAfterStop && !destroyed -> {
                    holder.stoppedWith(failure)
                    restartAfterStop = false
                    start()
                }
                retry != null && failure != null && !stopRequested && !destroyed -> awaitRetry(retry, failure)
                else -> {
                    holder.stoppedWith(failure)
                    finish()
                }
            }
        }
    }

    /** Stays in the foreground while waiting, so the retry needs no UI and survives the app being closed. */
    private fun awaitRetry(retry: AutoRetry, failure: ConnState.Failed) {
        holder.awaitingRetry(failure)
        retry.schedule(retry = ::start, giveUp = { stop(failure) })
    }

    private fun finish() {
        stopFeed()
        stopWakeWatch()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Lives from the first start to [finish], across retries; see [StatusFeed] for when it posts. */
    private fun startFeed() {
        if (feed != null) return
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        val contents = StatusFeed(
            state = holder.state,
            retrying = holder.retrying,
            screenOn = screenOn,
            traffic = flow { emitAll(libbox.status) },
            rtt = ::rttOf,
            subscription = { node -> subscriptions.subscriptions.map { subscriptionOf(it, node) } },
        ).contents
        feed = scope.launch { contents.collect(notification::show) }
    }

    private fun stopFeed() {
        feed?.cancel()
        feed = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** Spans retries like the feed, whose screen state it reads, so a wake also cuts a retry wait short. */
    private fun startWakeWatch() {
        if (wakeWatch != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val awake = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> !power.isDeviceIdleMode
                    else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !power.isDeviceLightIdleMode
                }
                if (awake) wakeChecks.tryEmit(Unit)
            }
        }.also {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
            }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        val detector = WakeDetector(interactive = { screenOn.value })
        wakeWatch = scope.launch { detector.wakes(merge(wakeChecks, observer.unblocked)).collect(wakes::emit) }
    }

    private fun stopWakeWatch() {
        wakeWatch?.cancel()
        wakeWatch = null
        wakeReceiver?.let { runCatching { unregisterReceiver(it) } }
        wakeReceiver = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rttOf(node: Node): Flow<Int?> = observer.current.map { it?.key }.distinctUntilChanged().flatMapLatest { key ->
        if (key == null) flowOf(null) else probeStats.observe(key).map { warmRtt(it, node) }
    }

    private fun registerReceivers(b: SelectorBrain) {
        val power = getSystemService(PowerManager::class.java)
        b.onScreenChanged(power.isInteractive)
        register(IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }) { intent ->
            b.onScreenChanged(intent.action == Intent.ACTION_SCREEN_ON)
        }
        register(IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
            val idle = power.isDeviceIdleMode
            scope.launch(powerCalls) { runCatching { if (idle) runtime.pause() else runtime.wake() } }
        }
        register(IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }) { intent ->
            val added = addedPackage(intent) ?: return@register
            scope.launch { if (InstalledSplit.affects(settings.settings.first().apps, added, packageName)) b.onAppsChanged() }
        }
    }

    private fun register(filter: IntentFilter, onReceive: (Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receivers += receiver
    }

    private fun unregisterReceivers() {
        for (r in receivers) runCatching { unregisterReceiver(r) }
        receivers.clear()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_START = "moe.desupon.vpn.action.START"
        const val ACTION_STOP = "moe.desupon.vpn.action.STOP"

        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun startIntent(context: Context): Intent = Intent(context, DesuponVpnService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, DesuponVpnService::class.java).setAction(ACTION_STOP)

        /** Null once [start] put the service in the foreground, else why the system refused. */
        internal fun enterForeground(start: () -> Unit): String? = try {
            start()
            null
        } catch (_: SecurityException) {
            // Android 14+ refuses the systemExempted type to an app without VPN consent.
            VpnFailReason.NOT_PREPARED
        } catch (_: IllegalStateException) {
            VpnFailReason.START_BLOCKED
        }

        /** A fresh install, not an update of an existing package. */
        internal fun addedPackage(intent: Intent): String? =
            intent.data?.schemeSpecificPart?.takeIf { !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) }

        private fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
    }
}
