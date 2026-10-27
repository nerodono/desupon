package moe.desupon.platform.libbox

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.desupon.brain.port.LibboxControl
import moe.desupon.core.bridge.Bridge
import moe.desupon.core.libbox.CommandClient
import moe.desupon.core.libbox.CommandClientHandler
import moe.desupon.core.libbox.CommandClientOptions
import moe.desupon.core.libbox.Connection
import moe.desupon.core.libbox.ConnectionEvents
import moe.desupon.core.libbox.Libbox
import moe.desupon.core.libbox.LogIterator
import moe.desupon.core.libbox.OutboundGroupItemIterator
import moe.desupon.core.libbox.OutboundGroupIterator
import moe.desupon.core.libbox.OverrideOptions
import moe.desupon.core.libbox.StatusMessage
import moe.desupon.core.libbox.StringIterator
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.status.ConnInfo
import moe.desupon.model.status.GroupInfo
import moe.desupon.model.status.GroupItem
import moe.desupon.model.status.TrafficStatus
import moe.desupon.platform.iface.StringArray
import moe.desupon.platform.iface.toList
import moe.desupon.platform.log.CoreException
import moe.desupon.platform.log.CoreLogEntry
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.log.Redactor

/** Native calls of [LibboxCommand]; a seam so its sequencing runs without the core library. */
internal interface CoreGateway {
    fun onAttachChanged(listener: (Boolean) -> Unit)

    fun startOrReload(configJson: String, overrides: PackageOverrides)

    fun select(group: String, tag: String)

    fun closeConnections()

    fun closeService()

    fun newClient(handler: CommandClientHandler): CoreClient
}

internal interface CoreClient {
    /** Blocks until the streams are open; reconnecting cancels the previous streams. */
    fun connect()

    fun disconnect()
}

/** [CoreGateway] over the attached command server; control calls go to the server itself, never through the client. */
internal class RuntimeGateway(private val app: Context, private val runtime: CoreRuntime) : CoreGateway {
    override fun onAttachChanged(listener: (Boolean) -> Unit) = runtime.onAttachChanged(listener)

    override fun startOrReload(configJson: String, overrides: PackageOverrides) {
        val options = OverrideOptions().apply {
            autoRedirect = false
            if (overrides.include.isNotEmpty()) includePackage = StringArray(overrides.include)
            if (overrides.exclude.isNotEmpty()) excludePackage = StringArray(overrides.exclude)
        }
        try {
            runtime.requireServer().startOrReloadService(configJson, options)
        } finally {
            LibboxSetup.dropConfigSnapshot(app)
        }
    }

    override fun select(group: String, tag: String) = Bridge.selectOutbound(runtime.requireServer(), group, tag)

    override fun closeConnections() = Bridge.closeConnections(runtime.requireServer())

    override fun closeService() = runtime.closeService()

    override fun newClient(handler: CommandClientHandler): CoreClient {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandGroup)
            addCommand(Libbox.CommandConnections)
            addCommand(Libbox.CommandLog)
            statusInterval = STATUS_INTERVAL_NS
        }
        val client = CommandClient(handler, options)
        return object : CoreClient {
            override fun connect() = client.connect()

            override fun disconnect() = client.disconnect()
        }
    }

    private companion object {
        const val STATUS_INTERVAL_NS = 1_000_000_000L
    }
}

/** [LibboxControl] over the in-process command server; status, group, connection and log streams come from a unix-socket client. */
class LibboxCommand internal constructor(
    private val core: CoreGateway,
    private val ownPackage: String,
    private val io: CoroutineDispatcher,
    private val dialWaitMs: Long = DIAL_WAIT_MS,
) : LibboxControl {
    constructor(
        context: Context,
        runtime: CoreRuntime,
        ownPackage: String = context.packageName,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(RuntimeGateway(context.applicationContext, runtime), ownPackage, io)

    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _groups = MutableStateFlow<List<GroupInfo>>(emptyList())
    private val _status = MutableStateFlow(TrafficStatus(0, 0, 0, 0, 0, 0))
    private val _allConnections = MutableStateFlow<List<ConnInfo>>(emptyList())
    private val _logs = MutableSharedFlow<CoreLogEntry>(extraBufferCapacity = LOG_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _defaultLogLevel = MutableStateFlow(CoreLogLevel.INFO)

    override val groups: Flow<List<GroupInfo>> = _groups.asStateFlow()
    override val status: Flow<TrafficStatus> = _status.asStateFlow()

    /** Connections whose chain passes the `proxy` selector; direct traffic is left out. */
    override val connections: Flow<List<ConnInfo>> = _allConnections.map { it.proxied() }

    /** Proxied traffic per final outbound. */
    val proxyTraffic: Flow<Map<String, OutboundTraffic>> = _allConnections.map { it.trafficByOutbound() }

    val logs: SharedFlow<CoreLogEntry> = _logs.asSharedFlow()
    val defaultLogLevel: StateFlow<CoreLogLevel> = _defaultLogLevel.asStateFlow()

    private val table = ConnectionTable()
    private var client: CoreClient? = null
    private var dialJob: Job? = null
    private var epoch = 0

    init {
        core.onAttachChanged { attached -> if (!attached) disconnectClient() }
    }

    /** Returns once the streams of the new service are open, or after [dialWaitMs] without them. */
    override suspend fun reload(configJson: String, apps: AppSplit) {
        onCore { core.startOrReload(configJson, PackageOverrides.of(apps, ownPackage)) }
        // Streams open only on a started service and end when it restarts.
        val dial = connectClient()
        withTimeoutOrNull(dialWaitMs) { dial.join() }
    }

    override suspend fun select(group: String, tag: String) = onCore { core.select(group, tag) }

    override suspend fun closeConnections() = onCore { core.closeConnections() }

    override suspend fun stop() = withContext(io) {
        disconnectClient()
        core.closeService()
    }

    private suspend fun onCore(block: () -> Unit) = withContext(io) {
        try {
            block()
        } catch (e: Exception) {
            throw CoreException.from(e)
        }
    }

    private fun connectClient(): Job = synchronized(this) {
        val myEpoch = ++epoch
        if (client == null) client = core.newClient(Handler())
        dialJob?.cancel()
        scope.launch { dial(myEpoch, 0) }.also { dialJob = it }
    }

    /** One dial loop at a time: every stream reports its own disconnect. */
    private fun scheduleRedial() {
        synchronized(this) {
            if (client == null || dialJob?.isActive == true) return
            val myEpoch = epoch
            dialJob = scope.launch { dial(myEpoch, RETRY_BASE_MS) }
        }
    }

    private suspend fun dial(myEpoch: Int, initialDelayMs: Long) {
        var wait = initialDelayMs
        while (true) {
            if (wait > 0) delay(wait)
            val c = synchronized(this) { client.takeIf { epoch == myEpoch } } ?: return
            if (runCatching { c.connect() }.isSuccess) return
            wait = (wait * 2).coerceIn(RETRY_BASE_MS, RETRY_MAX_MS)
        }
    }

    private fun disconnectClient() {
        val c = synchronized(this) {
            epoch++
            dialJob?.cancel()
            dialJob = null
            val old = client
            client = null
            old
        }
        if (c != null) scope.launch { runCatching { c.disconnect() } }
        _groups.value = emptyList()
        _status.value = TrafficStatus(0, 0, 0, 0, 0, 0)
        synchronized(table) { _allConnections.value = table.apply(true, emptyList(), System.currentTimeMillis()) }
    }

    private fun active() = synchronized(this) { client != null }

    private inner class Handler : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            if (!active()) return
            _logs.tryEmit(CoreLogEntry(CoreLogLevel.WARN, "command client: " + Redactor.mask(message)))
            scheduleRedial()
        }

        override fun setDefaultLogLevel(level: Int) {
            if (active()) _defaultLogLevel.value = CoreLogLevel.fromCore(level)
        }

        override fun clearLogs() {}

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null || !active()) return
            while (messageList.hasNext()) {
                val entry = messageList.next() ?: continue
                _logs.tryEmit(CoreLogEntry(CoreLogLevel.fromCore(entry.level), Redactor.mask(entry.message)))
            }
        }

        override fun writeStatus(message: StatusMessage?) {
            if (message == null || !active()) return
            _status.value = TrafficStatus(
                uplinkRate = message.uplink,
                downlinkRate = message.downlink,
                uplinkTotal = message.uplinkTotal,
                downlinkTotal = message.downlinkTotal,
                connectionsIn = message.connectionsIn,
                connectionsOut = message.connectionsOut,
            )
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (message == null || !active()) return
            val out = mutableListOf<GroupInfo>()
            while (message.hasNext()) {
                val g = message.next() ?: continue
                out += GroupInfo(
                    tag = g.tag,
                    type = g.type,
                    selected = g.selected,
                    selectable = g.selectable,
                    items = g.items.toGroupItems(),
                )
            }
            _groups.value = out
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) {}

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}

        override fun updateClashMode(newMode: String?) {}

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null || !active()) return
            val converted = mutableListOf<ConnEvent>()
            val iterator = events.iterator()
            while (iterator.hasNext()) {
                val e = iterator.next() ?: continue
                converted += when (e.type.toLong()) {
                    Libbox.ConnectionEventNew -> e.connection?.toInfo()?.let { ConnEvent.New(it) } ?: continue
                    Libbox.ConnectionEventUpdate -> ConnEvent.Update(e.id, e.uplinkDelta, e.downlinkDelta)
                    Libbox.ConnectionEventClosed -> ConnEvent.Closed(e.id, e.closedAt, e.connection?.toInfo())
                    else -> continue
                }
            }
            synchronized(table) {
                _allConnections.value = table.apply(events.reset, converted, System.currentTimeMillis())
            }
        }
    }

    private companion object {
        const val DIAL_WAIT_MS = 3_000L
        const val LOG_BUFFER = 512
        const val RETRY_BASE_MS = 500L
        const val RETRY_MAX_MS = 5_000L
    }
}

private fun OutboundGroupItemIterator?.toGroupItems(): List<GroupItem> {
    if (this == null) return emptyList()
    val out = mutableListOf<GroupItem>()
    while (hasNext()) {
        val item = next() ?: continue
        out += GroupItem(
            tag = item.tag,
            type = item.type,
            delayMs = item.urlTestDelay.takeIf { item.urlTestTime > 0 && it > 0 },
        )
    }
    return out
}

private fun Connection.toInfo() = ConnInfo(
    id = id,
    network = network,
    destination = destination,
    domain = domain.ifEmpty { null },
    outbound = outbound,
    chain = chain().toList(),
    uplinkTotal = uplinkTotal,
    downlinkTotal = downlinkTotal,
    createdAtMs = createdAt,
    closed = closedAt != 0L,
)
