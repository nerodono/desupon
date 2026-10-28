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
import moe.desupon.platform.log.CoreLogText
import moe.desupon.platform.log.LogIntake
import moe.desupon.platform.log.Redactor

/** Native calls of [LibboxCommand]; a seam so its sequencing runs without the core library. */
internal interface CoreGateway {
    fun onAttachChanged(listener: (Boolean) -> Unit)

    fun startOrReload(configJson: String, overrides: PackageOverrides)

    fun select(group: String, tag: String)

    fun closeConnections()

    fun resetNetwork()

    fun resetOutbound(tag: String)

    fun closeService()

    /** A client for the log stream alone when [logs], else for the status, group and connection streams. */
    fun newClient(handler: CommandClientHandler, logs: Boolean): CoreClient
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

    override fun resetNetwork() = Bridge.resetNetwork(runtime.requireServer())

    override fun resetOutbound(tag: String) = Bridge.resetOutbound(runtime.requireServer(), tag)

    override fun closeService() = runtime.closeService()

    override fun newClient(handler: CommandClientHandler, logs: Boolean): CoreClient {
        val options = CommandClientOptions().apply {
            if (logs) {
                addCommand(Libbox.CommandLog)
            } else {
                addCommand(Libbox.CommandStatus)
                addCommand(Libbox.CommandGroup)
                addCommand(Libbox.CommandConnections)
                statusInterval = STATUS_INTERVAL_NS
            }
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

/** [LibboxControl] over the in-process command server; status, group, connection and log streams come from unix-socket clients. */
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

    // Status, group and connection streams belong to one box and are reopened with every reload.
    private val streams = Link(logs = false) { generation -> StreamHandler(generation) }

    // The log stream belongs to the command server, so it stays open across reloads until the server goes.
    private val logLink = Link(logs = true) { generation -> LogHandler(generation, intake!!.session()) }
    private var intake: LogIntake? = null

    init {
        core.onAttachChanged { attached ->
            if (!attached) {
                disconnectClient()
                closeLogs()
            }
        }
    }

    /** Returns once the streams of the new service are open, or after [dialWaitMs] without them. */
    override suspend fun reload(configJson: String, apps: AppSplit) {
        // The old box's streams end during the reload; their callbacks are stale from here on.
        streams.close()
        onCore { core.startOrReload(configJson, PackageOverrides.of(apps, ownPackage)) }
        openLogs()
        val dial = streams.open()
        withTimeoutOrNull(dialWaitMs) { dial.join() }
    }

    override suspend fun select(group: String, tag: String) = onCore { core.select(group, tag) }

    override suspend fun closeConnections() = onCore { core.closeConnections() }

    override suspend fun resetNetwork() = onCore { core.resetNetwork() }

    override suspend fun resetOutbound(tag: String) = onCore { core.resetOutbound(tag) }

    /** Logs stay connected until the server detaches, so the box's shutdown is recorded. */
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

    /** Opens the log stream once per server; it can open only after a box has started. */
    private fun openLogs() {
        synchronized(logLink) {
            if (logLink.isOpen) return
            intake?.close()
            intake = LogIntake(LibboxSetup.LOG_MAX_LINES) { _logs.tryEmit(it.copy(message = CoreLogText.clean(it.message))) }
            logLink.open()
        }
    }

    private fun closeLogs() {
        synchronized(logLink) {
            logLink.close()
            intake?.close()
            intake = null
        }
    }

    private fun disconnectClient() {
        streams.close()
        _groups.value = emptyList()
        _status.value = TrafficStatus(0, 0, 0, 0, 0, 0)
        synchronized(table) { _allConnections.value = table.apply(true, emptyList(), System.currentTimeMillis()) }
    }

    /** A stream of the current client ended; ends of streams the client was closed for are routine. */
    private fun noteDisconnect(message: String?) {
        val text = Redactor.mask(message)
        val level = if (STREAM_END.containsMatchIn(text)) CoreLogLevel.DEBUG else CoreLogLevel.WARN
        _logs.tryEmit(CoreLogEntry(level, "command client: $text"))
    }

    /** One command client and its redial loop; every open makes a new client, so callbacks of replaced ones are told apart by generation. */
    private inner class Link(private val logs: Boolean, private val handler: (generation: Int) -> CommandClientHandler) {
        private var client: CoreClient? = null
        private var job: Job? = null
        private var generation = 0
        private var failures = 0

        val isOpen: Boolean get() = synchronized(this) { client != null }

        fun isCurrent(generation: Int): Boolean = synchronized(this) { generation == this.generation && client != null }

        /** The job completes once the new client has connected. */
        fun open(): Job = synchronized(this) {
            failures = 0
            replace(0)
        }

        /** The current client lost a stream: a new client, after a delay that grows while this keeps happening. */
        fun redial(generation: Int) {
            synchronized(this) {
                if (!isCurrent(generation)) return
                failures++
                replace((RETRY_BASE_MS shl (failures - 1).coerceAtMost(RETRY_DOUBLINGS)).coerceAtMost(RETRY_MAX_MS))
            }
        }

        fun close() {
            val old = synchronized(this) {
                generation++
                job?.cancel()
                job = null
                client.also { client = null }
            }
            old?.let { scope.launch { runCatching { it.disconnect() } } }
        }

        private fun replace(delayMs: Long): Job {
            val old = client
            val next = ++generation
            job?.cancel()
            val c = core.newClient(handler(next), logs)
            client = c
            old?.let { scope.launch { runCatching { it.disconnect() } } }
            return scope.launch { dial(next, c, delayMs) }.also { job = it }
        }

        private suspend fun dial(generation: Int, c: CoreClient, initialDelayMs: Long) {
            var wait = initialDelayMs
            while (true) {
                if (wait > 0) delay(wait)
                if (!isCurrent(generation)) return
                if (runCatching { c.connect() }.isSuccess) return
                wait = (wait * 2).coerceIn(RETRY_BASE_MS, RETRY_MAX_MS)
            }
        }
    }

    private inner class LogHandler(private val generation: Int, private val session: LogIntake.Session) : CommandClientHandler {
        override fun connected() {}

        override fun disconnected(message: String?) {
            if (!logLink.isCurrent(generation)) return
            noteDisconnect(message)
            logLink.redial(generation)
        }

        override fun setDefaultLogLevel(level: Int) {
            if (logLink.isCurrent(generation)) _defaultLogLevel.value = CoreLogLevel.fromCore(level)
        }

        override fun clearLogs() = session.cleared()

        override fun writeLogs(messageList: LogIterator?) {
            if (messageList == null) return
            val batch = ArrayList<CoreLogEntry>()
            while (messageList.hasNext()) {
                val entry = messageList.next() ?: continue
                batch += CoreLogEntry(CoreLogLevel.fromCore(entry.level), entry.message.orEmpty())
            }
            session.lines(batch)
        }

        override fun writeStatus(message: StatusMessage?) {}

        override fun writeGroups(message: OutboundGroupIterator?) {}

        override fun writeOutbounds(message: OutboundGroupItemIterator?) {}

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}

        override fun updateClashMode(newMode: String?) {}

        override fun writeConnectionEvents(events: ConnectionEvents?) {}
    }

    private inner class StreamHandler(private val generation: Int) : CommandClientHandler {
        private fun current() = streams.isCurrent(generation)

        override fun connected() {}

        override fun disconnected(message: String?) {
            if (!current()) return
            noteDisconnect(message)
            streams.redial(generation)
        }

        override fun setDefaultLogLevel(level: Int) {}

        override fun clearLogs() {}

        override fun writeLogs(messageList: LogIterator?) {}

        override fun writeStatus(message: StatusMessage?) {
            if (message == null || !current()) return
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
            if (message == null || !current()) return
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
            if (events == null || !current()) return
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
        // Room for a whole core ring sent when the log stream opens.
        const val LOG_BUFFER = 4_096
        const val RETRY_BASE_MS = 500L
        const val RETRY_MAX_MS = 5_000L
        const val RETRY_DOUBLINGS = 4
        val STREAM_END = Regex("(?i)cancel|EOF|unavailable")
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
