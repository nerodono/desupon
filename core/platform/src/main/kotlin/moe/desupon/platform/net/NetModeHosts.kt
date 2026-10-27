package moe.desupon.platform.net

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.desupon.brain.detect.ProbingNetModeDetector
import moe.desupon.brain.port.HostProbe
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.platform.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reference hosts for telling an open network from a whitelist-only one. */
data class NetModeHosts(
    val ru: List<String>,
    val foreign: List<String>,
    val port: Int = DEFAULT_PORT,
) {
    fun toJson(): String = JSONObject()
        .put("port", port)
        .put("ru", JSONArray(ru))
        .put("foreign", JSONArray(foreign))
        .toString(2)

    companion object {
        const val DEFAULT_PORT = 443

        fun parse(json: String): NetModeHosts {
            val o = JSONObject(json)
            fun list(key: String) = o.optJSONArray(key)?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }
            }.orEmpty()
            return NetModeHosts(list("ru"), list("foreign"), o.optInt("port", DEFAULT_PORT))
        }
    }
}

/** Bundled host list with a user-editable override in app storage. */
class NetModeHostsStore(context: Context) {
    private val app = context.applicationContext
    private val override = File(app.filesDir, FILE)

    fun load(): NetModeHosts = runCatching { NetModeHosts.parse(override.readText()) }
        .getOrNull()
        ?.takeIf { it.ru.isNotEmpty() && it.foreign.isNotEmpty() }
        ?: bundled()

    fun bundled(): NetModeHosts =
        NetModeHosts.parse(app.resources.openRawResource(R.raw.netmode_hosts).bufferedReader().use { it.readText() })

    fun save(hosts: NetModeHosts) {
        override.writeText(hosts.toJson())
    }

    fun reset() {
        override.delete()
    }

    private companion object {
        const val FILE = "netmode_hosts.json"
    }
}


/** [ProbingNetModeDetector] over the current host list, re-read on every detection so edits apply at once. */
class HostListNetModeDetector(
    private val hosts: () -> NetModeHosts,
    private val probe: HostProbe,
    private val timeoutMs: Int = ProbingNetModeDetector.DEFAULT_TIMEOUT_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NetModeDetector {
    override suspend fun detect(key: NetworkKey, network: NetworkHandle): NetworkMode {
        val list = withContext(io) { hosts() }
        return ProbingNetModeDetector(probe, list.ru, list.foreign, timeoutMs, list.port).detect(key, network)
    }
}
