@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import moe.desupon.brain.plan.DefaultTierPlanner
import moe.desupon.brain.plan.PlannerConfig
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.brain.port.NetworkMemoryRepo
import moe.desupon.brain.port.NodeRepo
import moe.desupon.brain.port.PoolSelection
import moe.desupon.brain.port.ProbeRequest
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.Prober
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SettingsPort
import moe.desupon.brain.port.SocketProtector
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.brain.port.TierLearningRepo
import moe.desupon.brain.port.TierPlanner
import moe.desupon.brain.probe.ProbeActivity
import moe.desupon.config.ConfigGenerator
import moe.desupon.config.LocalRuleSet
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.NodeKey
import moe.desupon.model.node.Protocol
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.probe.Throughput
import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.ConnectionSettings
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.status.ConnInfo
import moe.desupon.model.status.GroupInfo
import moe.desupon.model.status.TrafficStatus

const val WALL_BASE = 1_800_000_000_000L

class FakeClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun nowMs(): Long = WALL_BASE + scheduler.currentTime

    override fun elapsedMs(): Long = scheduler.currentTime
}

/** Synthetic nodes only; credentials are fake. */
object Nodes {
    private var next = 1

    fun ss(
        name: String,
        country: String? = "NL",
        labels: Set<String> = emptySet(),
        capability: Capability = Capability.SUPPORTED,
    ): Node {
        val host = "198.51.100.${next++}"
        val key = NodeKey(Protocol.SHADOWSOCKS, host, 8388, credential = Secret("cred-$name"))
        return Node(
            id = key.id,
            subs = emptySet(),
            protocol = Protocol.SHADOWSOCKS,
            displayName = name,
            country = country,
            capability = capability,
            outboundJson = Secret(
                """{"type":"shadowsocks","server":"$host","server_port":8388,"method":"2022-blake3-aes-128-gcm","password":"WlpaWlpaWlpaWlpaWlpaWg=="}""",
            ),
            isEndpoint = false,
            udpOnly = false,
            labels = labels,
        )
    }

    fun wg(name: String, country: String? = "NL"): Node {
        val host = "198.51.100.${next++}"
        val key = NodeKey(Protocol.WIREGUARD, host, 51820, credential = Secret("cred-$name"))
        return Node(
            id = key.id,
            subs = emptySet(),
            protocol = Protocol.WIREGUARD,
            displayName = name,
            country = country,
            capability = Capability.SUPPORTED,
            outboundJson = Secret(
                """{"type":"wireguard","address":["10.7.0.2/32"],"private_key":"$WG_KEY","peers":[{"address":"$host","port":51820,"public_key":"$WG_KEY","allowed_ips":["0.0.0.0/0"]}]}""",
            ),
            isEndpoint = true,
            udpOnly = true,
            labels = emptySet(),
        )
    }

    fun tuic(name: String, country: String? = "NL"): Node = quic(name, country, Protocol.TUIC, tuicJson(nextHost()))

    fun hy2(name: String, country: String? = "NL"): Node {
        val host = nextHost()
        return quic(
            name, country, Protocol.HYSTERIA2,
            """{"type":"hysteria2","server":"$host","server_port":8443,"password":"pw-$name","tls":{"enabled":true,"server_name":"hy2.test","alpn":["h3"]}}""",
        )
    }

    /** VLESS over XHTTP whose first ALPN h3 makes it ride QUIC. */
    fun vlessH3(name: String, country: String? = "NL"): Node = vlessXhttp(name, country, alpn = "\"h3\",\"h2\"", udpOnly = true)

    /** VLESS over XHTTP on HTTP/2: every flow shares one TCP connection. */
    fun vlessH2(name: String, country: String? = "NL"): Node = vlessXhttp(name, country, alpn = "\"h2\"", udpOnly = false)

    /** A Shadowsocks exit dialed through a VLESS-over-QUIC hop. */
    fun ssViaVlessQuic(name: String, country: String? = "NL"): Node {
        val host = nextHost()
        val hop = """{"type":"vless","server":"${nextHost()}","server_port":443,"uuid":"$UUID",""" +
            """"tls":{"enabled":true,"server_name":"v.test","alpn":["h3"]},"transport":{"type":"quic"}}"""
        return outbound(
            name, country, Protocol.SHADOWSOCKS, host, udpOnly = false, via = listOf("vless-quic-hop-$name"),
            json = """{"type":"shadowsocks","server":"$host","server_port":8388,"method":"2022-blake3-aes-128-gcm",""" +
                """"password":"WlpaWlpaWlpaWlpaWlpaWg==","chain":[$hop]}""",
        )
    }

    private fun vlessXhttp(name: String, country: String?, alpn: String, udpOnly: Boolean): Node {
        val host = nextHost()
        return outbound(
            name, country, Protocol.VLESS, host, udpOnly,
            json = """{"type":"vless","server":"$host","server_port":443,"uuid":"$UUID",""" +
                """"tls":{"enabled":true,"server_name":"v.test","alpn":[$alpn]},""" +
                """"transport":{"type":"xhttp","mode":"stream-one","path":"/q","x_padding_bytes":"200-800"}}""",
        )
    }

    private fun outbound(
        name: String,
        country: String?,
        protocol: Protocol,
        host: String,
        udpOnly: Boolean,
        json: String,
        via: List<String> = emptyList(),
    ): Node = Node(
        id = NodeKey(protocol, host, 443, credential = Secret("cred-$name"), via = via).id,
        subs = emptySet(),
        protocol = protocol,
        displayName = name,
        country = country,
        capability = Capability.SUPPORTED,
        outboundJson = Secret(json),
        isEndpoint = false,
        udpOnly = udpOnly,
        labels = emptySet(),
    )

    /** A Shadowsocks exit dialed through a TUIC hop. */
    fun ssViaTuic(name: String, country: String? = "NL"): Node {
        val host = nextHost()
        val key = NodeKey(Protocol.SHADOWSOCKS, host, 8388, credential = Secret("cred-$name"), via = listOf("tuic-hop-$name"))
        return Node(
            id = key.id,
            subs = emptySet(),
            protocol = Protocol.SHADOWSOCKS,
            displayName = name,
            country = country,
            capability = Capability.SUPPORTED,
            outboundJson = Secret(
                """{"type":"shadowsocks","server":"$host","server_port":8388,"method":"2022-blake3-aes-128-gcm","password":"WlpaWlpaWlpaWlpaWlpaWg==","chain":[${tuicJson(nextHost())}]}""",
            ),
            isEndpoint = false,
            udpOnly = false,
            labels = emptySet(),
        )
    }

    private fun quic(name: String, country: String?, protocol: Protocol, json: String): Node {
        val host = Regex(""""server":"([^"]+)"""").find(json)!!.groupValues[1]
        val key = NodeKey(protocol, host, 443, credential = Secret("cred-$name"))
        return Node(
            id = key.id,
            subs = emptySet(),
            protocol = protocol,
            displayName = name,
            country = country,
            capability = Capability.SUPPORTED,
            outboundJson = Secret(json),
            isEndpoint = false,
            udpOnly = true,
            labels = emptySet(),
        )
    }

    private const val UUID = "00000000-0000-4000-8000-00000000c0de"

    private fun tuicJson(host: String) =
        """{"type":"tuic","server":"$host","server_port":443,"uuid":"00000000-0000-4000-8000-00000000c0de","password":"pw","congestion_control":"bbr","tls":{"enabled":true,"server_name":"tuic.test","alpn":["h3"]}}"""

    private fun nextHost() = "198.51.100.${next++}"

    private const val WG_KEY = "WlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlpaWlo="
}

val Node.tag: String get() = id.tag

/** Ordered record of port calls across fakes. */
class Journal {
    val entries = mutableListOf<String>()

    operator fun plusAssign(entry: String) {
        entries += entry
    }
}

class FakeLibbox(private val journal: Journal) : LibboxControl {
    sealed interface Call {
        data class Reload(val json: String, val apps: AppSplit) : Call

        data class Select(val group: String, val tag: String) : Call

        /** The no-node config that holds the TUN up while the pool is probed. */
        data class Placeholder(val json: String) : Call

        data object ResetNetwork : Call

        data class ResetOutbound(val tag: String) : Call

        data object Stop : Call
    }

    val calls = mutableListOf<Call>()
    var failReload = false

    /** Fails this many upcoming reloads. */
    var failNextReloads = 0
    var failPlaceholder = false

    /** Runs on each network or outbound reset, e.g. to revive nodes that failed only through stale connections. */
    var onReset: (() -> Unit)? = null

    override val groups = MutableStateFlow<List<GroupInfo>>(emptyList())
    override val status = MutableStateFlow(TrafficStatus(0, 0, 0, 0, 0, 0))
    override val connections = MutableStateFlow<List<ConnInfo>>(emptyList())

    val reloads: List<Call.Reload> get() = calls.filterIsInstance<Call.Reload>()
    val selected: String? get() = calls.filterIsInstance<Call.Select>().lastOrNull()?.tag
    val resets: Int get() = calls.count { it == Call.ResetNetwork }
    val outboundResets: List<String> get() = calls.filterIsInstance<Call.ResetOutbound>().map { it.tag }

    override suspend fun reload(configJson: String, apps: AppSplit) {
        if (PLACEHOLDER in configJson) {
            journal += "placeholder"
            calls += Call.Placeholder(configJson)
            if (failPlaceholder) error("core refused")
            return
        }
        journal += "reload"
        calls += Call.Reload(configJson, apps)
        if (failReload) error("core refused")
        if (failNextReloads > 0) {
            failNextReloads--
            error("core refused")
        }
    }

    override suspend fun select(group: String, tag: String) {
        journal += "select:$tag"
        calls += Call.Select(group, tag)
    }

    override suspend fun closeConnections() = Unit

    override suspend fun resetNetwork() {
        journal += "reset"
        calls += Call.ResetNetwork
        onReset?.invoke()
    }

    override suspend fun resetOutbound(tag: String) {
        journal += "reset:$tag"
        calls += Call.ResetOutbound(tag)
        onReset?.invoke()
    }

    override suspend fun stop() {
        journal += "stop"
        calls += Call.Stop
    }

    fun traffic(on: Boolean) {
        status.value = if (on) TrafficStatus(1_000, 5_000, 0, 0, 1, 1) else TrafficStatus(0, 0, 0, 0, 0, 0)
    }

    private companion object {
        const val PLACEHOLDER = "\"final\":\"block\""
    }
}

class FakeProber(private val journal: Journal) : Prober {
    /** Tags answering 204, off-TUN and live alike. */
    val alive = mutableSetOf<String>()

    /** Tags that pass pool probes but fail through the running box. */
    val liveDead = mutableSetOf<String>()
    val rtt = mutableMapOf<String, Int>()
    val poolRequests = mutableListOf<ProbeRequest>()
    val live = mutableListOf<String>()

    /** Runs before each live probe, e.g. to observe brain state mid-sequence. */
    var onLive: ((String) -> Unit)? = null

    /** Holds pool probes until completed. */
    var poolGate: CompletableDeferred<Unit>? = null

    /** Holds pool probes whose config holds this tag until completed. */
    var tagGate: Pair<String, CompletableDeferred<Unit>>? = null
    val connect = mutableMapOf<String, Int>()
    val throughput = mutableMapOf<String, Throughput>()
    val throughputRequests = mutableListOf<String>()

    override suspend fun probePool(request: ProbeRequest, protector: SocketProtector): Map<String, ProbeResult> {
        journal += "probePool"
        poolRequests += request
        poolGate?.await()
        tagGate?.let { (tag, gate) -> if (tag in request.nodesJson.raw()) gate.await() }
        return TAG.findAll(request.nodesJson.raw()).map { it.groupValues[1] }.associateWith { result(it, false) }
    }

    override suspend fun probeLive(tag: String, url: String, timeoutMs: Int): ProbeResult {
        journal += "probeLive:$tag"
        onLive?.invoke(tag)
        live += tag
        return result(tag, true)
    }

    override suspend fun throughputLive(tag: String, url: String, maxBytes: Int, timeoutMs: Int): Throughput? {
        throughputRequests += tag
        return throughput[tag]
    }

    private fun result(tag: String, live: Boolean): ProbeResult =
        if (tag in alive && !(live && tag in liveDead)) ProbeResult.Ok(ProbeStage.GET204, rtt[tag] ?: 100, connectMs = connect[tag] ?: 0, warm = true) else ProbeResult.Fail(ProbeStage.GET204, "timeout")

    private companion object {
        val TAG = Regex("\"tag\":\"(n-[0-9a-f]+)\"")
    }
}

class FakeDetector(private val journal: Journal) : NetModeDetector {
    val modes = mutableMapOf<NetworkKey, NetworkMode>()

    override suspend fun detect(key: NetworkKey, network: NetworkHandle): NetworkMode {
        journal += "detect"
        return modes[key] ?: NetworkMode.OPEN
    }
}

class FakeNodes(nodes: List<Node>) : NodeRepo {
    override val nodes = MutableStateFlow(nodes)
}

class FakeSplit : SplitRuleRepo {
    override val rules = MutableStateFlow<List<SplitRule>>(emptyList())
}

class FakeSelection : SelectionPort {
    override val selection = MutableStateFlow(PoolSelection())
}

class FakeSettings : SettingsPort {
    override val settings = MutableStateFlow(ConnectionSettings())
}

class FakeMemory : NetworkMemoryRepo {
    val map = mutableMapOf<NetworkKey, NetworkMemory>()

    override suspend fun get(key: NetworkKey): NetworkMemory? = map[key]

    override suspend fun put(memory: NetworkMemory) {
        map[memory.key] = memory
    }

    override suspend fun pruneOlderThan(epochMs: Long) {
        map.values.removeAll { it.updatedAtMs < epochMs }
    }
}

class FakeStats : ProbeStatRepo {
    val map = mutableMapOf<Pair<NetworkKey, NodeId>, ProbeStat>()

    override suspend fun forNetwork(key: NetworkKey): Map<NodeId, ProbeStat> =
        map.filterKeys { it.first == key }.mapKeys { it.key.second }

    override suspend fun upsert(stats: Collection<ProbeStat>) {
        for (s in stats) map[s.network to s.nodeId] = s
    }
}

class FakeLearning : TierLearningRepo {
    val map = mutableMapOf<NetworkKey, Set<NodeId>>()

    override suspend fun promoted(key: NetworkKey): Set<NodeId> = map[key].orEmpty()

    override suspend fun promote(key: NetworkKey, nodes: Set<NodeId>) {
        map[key] = map[key].orEmpty() + nodes
    }
}

val WIFI = NetworkKey(Transport.WIFI, "gw-a")
val WIFI_B = NetworkKey(Transport.WIFI, "gw-b")
val CELL = NetworkKey(Transport.CELLULAR, "250-01")

fun netEvent(key: NetworkKey, handle: Long = key.hashCode().toLong(), ipv6: Boolean = true) =
    BrainEvent.NetworkChanged(key, NetworkHandle(handle), listOf("192.0.2.53"), ipv6)

/** Wires a brain to fakes on the test scheduler; repos can be shared to simulate a process restart. */
class Harness(
    val test: TestScope,
    nodes: List<Node>,
    val journal: Journal = Journal(),
    val memory: FakeMemory = FakeMemory(),
    val stats: FakeStats = FakeStats(),
    val learning: FakeLearning = FakeLearning(),
    val prober: FakeProber = FakeProber(journal),
    plannerConfig: PlannerConfig = PlannerConfig(),
    config: BrainConfig = BrainConfig(),
    planner: TierPlanner? = null,
) {
    val clock = FakeClock(test.testScheduler)
    val libbox = FakeLibbox(journal)
    val detector = FakeDetector(journal)
    val nodeRepo = FakeNodes(nodes)
    val split = FakeSplit()
    val settings = FakeSettings()
    val selection = FakeSelection()
    /** Not a StateFlow, so an equal map still reaches the brain and tests see its own filtering. */
    val ruleSets = MutableSharedFlow<Map<String, LocalRuleSet>>(replay = 1).apply { tryEmit(emptyMap()) }
    val activity = ProbeActivity(clock)
    val brain = SelectorBrain(
        libbox = libbox,
        prober = prober,
        protector = { true },
        planner = planner ?: DefaultTierPlanner(clock, plannerConfig),
        detector = detector,
        nodeRepo = nodeRepo,
        splitRules = split,
        settings = settings,
        memory = memory,
        statRepo = stats,
        learning = learning,
        configGen = ConfigGenerator(),
        clock = clock,
        scope = test.backgroundScope,
        config = config,
        ruleSets = ruleSets,
        selectionPort = selection,
        activity = activity,
    )

    fun alive(vararg nodes: Node) {
        prober.alive += nodes.map { it.tag }
    }

    fun kill(vararg nodes: Node) {
        prober.alive -= nodes.map { it.tag }.toSet()
    }

    suspend fun connectOn(key: NetworkKey, mode: NetworkMode = NetworkMode.OPEN) {
        detector.modes[key] = mode
        brain.onEvent(netEvent(key))
        brain.connect()
    }
}
