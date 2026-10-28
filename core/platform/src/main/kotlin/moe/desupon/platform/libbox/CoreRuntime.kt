package moe.desupon.platform.libbox

import android.content.Context
import moe.desupon.core.bridge.Bridge
import moe.desupon.core.libbox.CommandServer
import moe.desupon.core.libbox.CommandServerHandler
import moe.desupon.core.libbox.Libbox
import moe.desupon.core.libbox.SystemProxyStatus
import moe.desupon.platform.iface.CorePlatform
import moe.desupon.platform.iface.VpnHost
import moe.desupon.platform.log.CoreException
import moe.desupon.platform.log.CoreLogBuffer
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.log.Redactor
import moe.desupon.platform.net.NetworkObserver

/** Holds the in-process command server for as long as the VpnService runs. */
class CoreRuntime(context: Context, val observer: NetworkObserver) {
    private val app = context.applicationContext

    private class Attached(val host: VpnHost, val platform: CorePlatform, val server: CommandServer)

    @Volatile
    private var attached: Attached? = null

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    private var logLevel = CoreLogBuffer.DEFAULT_THRESHOLD

    internal val server: CommandServer? get() = attached?.server

    val host: VpnHost? get() = attached?.host

    /** Starts the command server; call from the service before the first reload. */
    @Synchronized
    fun attach(host: VpnHost) {
        if (attached != null) return
        LibboxSetup.ensure(app)
        Bridge.setPlatformLogLevel(logLevel.core)
        observer.start()
        val platform = CorePlatform(app, host, observer)
        val server = try {
            Libbox.newCommandServer(Handler(host), platform).also { it.start() }
        } catch (e: Exception) {
            throw CoreException.from(e)
        }
        attached = Attached(host, platform, server)
        notify(true)
    }

    /** Stops the box, the command server and the TUN interface. */
    @Synchronized
    fun detach() {
        val current = attached ?: return
        attached = null
        notify(false)
        runCatching { current.server.closeService() }
        runCatching { current.server.close() }
        current.platform.closeTun()
        observer.stop()
        LibboxSetup.dropConfigSnapshot(app)
    }

    internal fun requireServer(): CommandServer = server ?: throw CoreException("core: service is not running")

    /** Cache the user's verbosity before attachment, and apply it at the source while the core runs. */
    @Synchronized
    internal fun setLogLevel(level: CoreLogLevel) {
        logLevel = level
        if (attached != null) Bridge.setPlatformLogLevel(level.core)
    }

    /** Closes the box and the TUN interface but keeps the command server. */
    fun closeService() {
        val current = attached ?: return
        try {
            current.server.closeService()
        } catch (e: Exception) {
            throw CoreException.from(e)
        } finally {
            current.platform.closeTun()
        }
    }

    /** Doze: the core pauses its timers until [wake]. */
    fun pause() {
        server?.pause()
    }

    fun wake() {
        server?.wake()
    }

    /** Adds a line to the core log stream; [message] is masked first. */
    fun log(level: CoreLogLevel, message: String) {
        server?.writeMessage(level.core, Redactor.mask(message))
    }

    internal fun onAttachChanged(listener: (Boolean) -> Unit) {
        synchronized(listeners) { listeners += listener }
        listener(attached != null)
    }

    private fun notify(value: Boolean) {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) l(value)
    }

    private class Handler(private val host: VpnHost) : CommandServerHandler {
        override fun serviceStop() = host.onCoreStopRequested()

        override fun serviceReload() = throw UnsupportedOperationException("reload goes through the app")

        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().also {
            it.available = false
            it.enabled = false
        }

        override fun setSystemProxyEnabled(enabled: Boolean) = throw UnsupportedOperationException("system proxy is not supported")

        override fun triggerNativeCrash() = throw UnsupportedOperationException("not supported")

        override fun writeDebugMessage(message: String?) {}

        override fun connectSSHAgent(): Int = -1
    }
}
