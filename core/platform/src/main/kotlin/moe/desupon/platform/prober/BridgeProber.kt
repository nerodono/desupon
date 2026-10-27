package moe.desupon.platform.prober

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import moe.desupon.brain.port.Prober
import moe.desupon.brain.port.SocketProtector
import moe.desupon.core.bridge.Bridge
import moe.desupon.core.bridge.Protector
import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.Throughput
import moe.desupon.platform.libbox.CoreRuntime
import moe.desupon.platform.log.Redactor
import moe.desupon.platform.net.NetworkObserver
import org.json.JSONObject
import java.net.NetworkInterface
import moe.desupon.brain.port.ProbeRequest as PoolRequest
import moe.desupon.core.bridge.ProbeRequest as CoreProbeRequest

/** Arguments of one native pool probe; not a data class, so the node config never reaches a string. */
internal class PoolProbeInput(
    val nodesJson: String,
    val url: String,
    val dnsCsv: String,
    val interfaceName: String,
    val interfaceIndex: Int,
    val timeoutMs: Int,
    val concurrency: Int,
    val ipv4Only: Boolean = false,
    /** JSON object of per-tag timeouts in milliseconds; empty for none. */
    val timeoutsJson: String = "",
)

/** The native pool probe; returns the core's JSON results. */
internal fun interface PoolProbeCall {
    fun probe(input: PoolProbeInput, protector: SocketProtector): String
}

private val BridgePoolCall = PoolProbeCall { input, protector ->
    val request = CoreProbeRequest().apply {
        setNodesJSON(input.nodesJson)
        setURL(input.url)
        setDNSCSV(input.dnsCsv)
        setInterfaceName(input.interfaceName)
        setInterfaceIndex(input.interfaceIndex)
        setTimeoutMs(input.timeoutMs)
        setConcurrency(input.concurrency)
        setIPv4Only(input.ipv4Only)
        setTimeoutsJSON(input.timeoutsJson)
    }
    Bridge.probe(request, Protector { fd -> protector.protect(fd) })
}

/** [Prober] over the core bridge: a private TUN-less box for pools, the running box for single outbounds. */
class BridgeProber internal constructor(
    context: Context,
    private val runtime: CoreRuntime,
    private val observer: NetworkObserver,
    private val io: CoroutineDispatcher,
    private val poolCall: PoolProbeCall,
) : Prober {
    constructor(
        context: Context,
        runtime: CoreRuntime,
        observer: NetworkObserver,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context, runtime, observer, io, BridgePoolCall)

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    // Native probes ignore cancellation, so they run detached and a cancelled caller stops waiting at once.
    private val blocking = CoroutineScope(SupervisorJob() + io)

    override suspend fun probePool(request: PoolRequest, protector: SocketProtector): Map<String, ProbeResult> {
        val nodes = request.nodesJson.raw()
        val target = withContext(io) { resolveNetwork(request) }
        val dns = request.dnsCsv.ifBlank { target?.dns?.joinToString(",").orEmpty() }
        if (dns.isBlank()) return failAll(nodes, "no DNS server of the underlying network")
        val input = PoolProbeInput(
            nodesJson = nodes,
            url = request.url,
            dnsCsv = dns,
            interfaceName = target?.name.orEmpty(),
            interfaceIndex = target?.index ?: 0,
            timeoutMs = request.timeoutMs,
            concurrency = request.concurrency,
            ipv4Only = request.ipv4Only,
            timeoutsJson = if (request.timeouts.isEmpty()) "" else JSONObject(request.timeouts.toMap()).toString(),
        )
        val attempt = blocking.async { runCatching { ProbeResults.parse(poolCall.probe(input, protector)) } }
        return attempt.await().getOrElse { e -> failAll(nodes, Redactor.message(e)) }
    }

    override suspend fun probeLive(tag: String, url: String, timeoutMs: Int): ProbeResult {
        val server = runtime.server ?: return ProbeResult.Fail(ProbeStage.TRANSPORT, "service is not running")
        val attempt = blocking.async {
            try {
                val r = Bridge.probeOutbound(server, tag, url, timeoutMs)
                ProbeResults.of(r.ok, r.stage, r.code, r.rttMs, r.error, r.connectMs, r.firstMs, r.warm)
            } catch (e: Exception) {
                ProbeResult.Fail(ProbeStage.TRANSPORT, Redactor.message(e))
            }
        }
        return attempt.await()
    }

    override suspend fun throughputLive(tag: String, url: String, maxBytes: Int, timeoutMs: Int): Throughput? {
        val server = runtime.server ?: return null
        val attempt = blocking.async {
            try {
                val r = Bridge.throughputOutbound(server, tag, url, maxBytes, timeoutMs)
                Throughput(r.ok, r.bytes, r.bodyMs)
            } catch (_: Exception) {
                null
            }
        }
        return attempt.await()
    }

    private fun failAll(nodes: String, reason: String): Map<String, ProbeResult> {
        val fail = ProbeResult.Fail(ProbeStage.TRANSPORT, reason)
        return ProbeResults.probedTags(nodes).associateWith { fail }
    }

    private class Target(val name: String, val index: Int, val dns: List<String>)

    private fun resolveNetwork(request: PoolRequest): Target? {
        val current = observer.current.value
        val network: Network = request.network?.let { Network.fromNetworkHandle(it.netId) }
            ?: return current?.let { Target(it.interfaceName, it.interfaceIndex.coerceAtLeast(0), it.dnsServers) }
        if (current != null && current.network == network) {
            return Target(current.interfaceName, current.interfaceIndex.coerceAtLeast(0), current.dnsServers)
        }
        val link = connectivity.getLinkProperties(network) ?: return null
        val name = link.interfaceName.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: 0
        return Target(name, index, link.dnsServers.mapNotNull { it.hostAddress })
    }
}
