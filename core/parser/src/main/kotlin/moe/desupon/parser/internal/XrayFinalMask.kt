package moe.desupon.parser.internal

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import moe.desupon.parser.link.QuicLinks

/** Hysteria 2 fields an xray `finalmask` carries, as sing-box values. */
internal data class HysteriaMask(
    val obfs: JsonObject? = null,
    val hopPorts: List<String> = emptyList(),
    val hopInterval: String? = null,
    val hopIntervalMax: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,
    val bbrProfile: String? = null,
)

internal object XrayFinalMask {
    private const val GECKO_MAX_PACKET = 2048
    private val BBR_PROFILES = setOf("standard", "conservative", "aggressive")

    /** Panels write Hysteria 2 obfuscation, hopping and Brutal rates here; a salamander mask with `packetSize` is gecko. */
    fun hysteria(fm: JsonObject, issues: Issues): HysteriaMask {
        var mask = HysteriaMask()
        for (item in fm["udp"].asArr().orEmpty()) {
            val m = item.asObj() ?: malformed("finalmask entry is not an object")
            val settings = m.child("settings") ?: JsonObject(emptyMap())
            when (val type = m.str("type")?.lowercase()) {
                "salamander" -> {
                    if (mask.obfs != null) issues.unsupported("stacked salamander masks")
                    mask = mask.copy(obfs = salamander(settings, issues))
                }
                "udphop" -> {
                    if (settings.strList("remoteIPs").isNotEmpty()) issues.unsupported("xray udphop remote IPs")
                    mask = mask.withHop(settings["remotePorts"], settings["interval"])
                }
                else -> issues.unsupported("xray finalmask ${type ?: "without type"}")
            }
        }
        // Other quicParams only tune the local QUIC stack.
        fm.child("quicParams")?.let { q ->
            q.child("udpHop")?.let { mask = mask.withHop(it["ports"], it["interval"]) }
            mask = mask.copy(
                upMbps = bandwidthMbps(q["brutalUp"]),
                downMbps = bandwidthMbps(q["brutalDown"]),
                bbrProfile = q.str("bbrProfile")?.lowercase()?.takeIf { it in BBR_PROFILES },
            )
        }
        return mask
    }

    fun obfs(type: String, password: String, minPacket: Int? = null, maxPacket: Int? = null): JsonObject = jsonObj {
        str("type", type)
        str("password", password)
        num("min_packet_size", minPacket)
        num("max_packet_size", maxPacket)
    }

    private fun salamander(settings: JsonObject, issues: Issues): JsonObject {
        val password = settings.rawStr("password")?.takeIf { it.isNotEmpty() } ?: malformed("salamander mask without password")
        val size = range(settings["packetSize"]) ?: return obfs("salamander", password)
        if (size.second <= 0) return obfs("salamander", password)
        if (size.first <= 0 || size.second > GECKO_MAX_PACKET) issues.unsupported("gecko packet size out of range")
        return obfs("gecko", password, size.first, size.second)
    }

    private fun HysteriaMask.withHop(ports: JsonElement?, interval: JsonElement?): HysteriaMask {
        val spec = ports.scalar() ?: return this
        val seconds = range(interval)
        return copy(
            hopPorts = (hopPorts + QuicLinks.portRanges(spec)).distinct(),
            hopInterval = seconds?.first?.takeIf { it > 0 }?.let { "${it}s" } ?: hopInterval,
            hopIntervalMax = seconds?.takeIf { it.second > it.first }?.let { "${it.second}s" } ?: hopIntervalMax,
        )
    }

    /** xray `Int32Range`: a number or `"a-b"`, ordered. */
    private fun range(el: JsonElement?): Pair<Int, Int>? {
        val s = el.scalar()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split('-').map { it.trim().toIntOrNull() ?: malformed("bad finalmask range") }
        val (a, b) = when (parts.size) {
            1 -> parts[0] to parts[0]
            2 -> parts[0] to parts[1]
            else -> malformed("bad finalmask range")
        }
        return minOf(a, b) to maxOf(a, b)
    }

    /** xray `Bandwidth`: bits per second with an optional k/m/g/t unit. */
    private fun bandwidthMbps(el: JsonElement?): Int? {
        val s = el.scalar()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val split = s.indexOfFirst { !it.isDigit() && it != '.' }.takeIf { it >= 0 } ?: s.length
        val value = s.substring(0, split).toDoubleOrNull() ?: return null
        val scale = when (s.substring(split).trim().removeSuffix("ps").removeSuffix("b")) {
            "" -> 1e-6
            "k" -> 1e-3
            "m" -> 1.0
            "g" -> 1e3
            "t" -> 1e6
            else -> return null
        }
        return (value * scale).toInt().takeIf { it > 0 }
    }
}
