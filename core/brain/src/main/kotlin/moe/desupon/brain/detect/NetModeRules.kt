package moe.desupon.brain.detect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import moe.desupon.brain.port.HostProbe
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode

object NetModeRules {
    const val MIN_RU_OK = 3
    const val MIN_FOREIGN_FAIL = 2

    /** OFFLINE still means "try every tier", never "stop"; WHITELIST also needs most foreign hosts failing, so longer lists tolerate flaky hosts. */
    fun classify(ruOk: Int, ruFail: Int, foreignOk: Int, foreignFail: Int): NetworkMode = when {
        ruOk + ruFail + foreignOk + foreignFail == 0 -> NetworkMode.UNKNOWN
        ruOk == 0 && foreignOk == 0 -> NetworkMode.OFFLINE
        ruOk >= MIN_RU_OK && foreignFail >= MIN_FOREIGN_FAIL && foreignFail > foreignOk -> NetworkMode.WHITELIST
        else -> NetworkMode.OPEN
    }
}

/** Probes both host lists in parallel; one host failing never decides the mode on its own. */
class ProbingNetModeDetector(
    private val probe: HostProbe,
    private val ruHosts: List<String> = DEFAULT_RU_HOSTS,
    private val foreignHosts: List<String> = DEFAULT_FOREIGN_HOSTS,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val port: Int = 443,
) : NetModeDetector {
    override suspend fun detect(key: NetworkKey, network: NetworkHandle): NetworkMode = coroutineScope {
        val ru = ruHosts.map { async { reachable(it, network) } }
        val foreign = foreignHosts.map { async { reachable(it, network) } }
        val r = ru.awaitAll()
        val f = foreign.awaitAll()
        NetModeRules.classify(r.count { it }, r.count { !it }, f.count { it }, f.count { !it })
    }

    private suspend fun reachable(host: String, network: NetworkHandle): Boolean = try {
        withTimeoutOrNull(timeoutMs.toLong()) { probe.reachable(host, port, network, timeoutMs) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000
        val DEFAULT_RU_HOSTS = listOf("ya.ru", "vk.com", "gosuslugi.ru", "mail.ru", "ozon.ru")
        val DEFAULT_FOREIGN_HOSTS = listOf("www.google.com", "www.apple.com", "www.microsoft.com")
    }
}
