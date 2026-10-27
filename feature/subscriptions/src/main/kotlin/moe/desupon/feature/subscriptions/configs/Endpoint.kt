package moe.desupon.feature.subscriptions.configs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Where a node connects, read from its outbound JSON. */
internal object Endpoint {
    /** `host:port`, an IPv6 host in brackets; null when the JSON names no server. */
    fun of(outboundJson: String): String? {
        val o = runCatching { Json.parseToJsonElement(outboundJson).jsonObject }.getOrNull() ?: return null
        val peer = (o["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
        val host = (if (peer != null) peer.text("address") else o.text("server")) ?: return null
        val port = if (peer != null) peer.text("port") else o.text("server_port")
        val shown = if (':' in host) "[$host]" else host
        return if (port.isNullOrEmpty()) shown else "$shown:$port"
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
