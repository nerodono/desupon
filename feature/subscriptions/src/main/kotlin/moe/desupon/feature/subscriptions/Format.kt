package moe.desupon.feature.subscriptions

import moe.desupon.model.node.Protocol
import moe.desupon.model.subscription.UserInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal sealed interface Expiry {
    data object Expired : Expiry

    /** Whole days remaining, 0 meaning less than a day. */
    data class Left(val days: Int) : Expiry
}

internal sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val n: Int) : Ago

    data class Hours(val n: Int) : Ago

    data class Days(val n: Int) : Ago
}

internal data class Traffic(val used: Long, val total: Long) {
    val limited: Boolean get() = total > 0
    val fraction: Float get() = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f
}

internal object Format {
    const val WARN_DAYS = 3
    private const val DAY_MS = 86_400_000L
    private val UNITS = listOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")

    /** Parts joined by ` · `, line breaks allowed only after a separator. */
    fun stats(parts: List<String>): String = parts.joinToString("\u00A0· ") { it.replace(' ', '\u00A0') }

    /** True when any `|`-separated mirror of [url] is plain http, so its address and token travel unencrypted. */
    fun cleartext(url: String): Boolean = url.split('|').any { it.trim().startsWith("http://", ignoreCase = true) }

    /** Binary units with a Russian decimal comma: `0 Б`, `1,5 КБ`, `12,3 ГБ`, `250 ГБ`. */
    fun bytes(value: Long): String {
        if (value < 1024) return "${value.coerceAtLeast(0)} ${UNITS[0]}"
        var scaled = value.toDouble()
        var unit = 0
        while (scaled >= 1024 && unit < UNITS.lastIndex) {
            scaled /= 1024
            unit++
        }
        val tenths = Math.round(scaled * 10)
        val text = when {
            tenths >= 1000 -> Math.round(scaled).toString()
            tenths % 10 == 0L -> (tenths / 10).toString()
            else -> "${tenths / 10},${tenths % 10}"
        }
        return "$text ${UNITS[unit]}"
    }

    fun traffic(info: UserInfo?): Traffic? {
        info ?: return null
        val used = info.upload.coerceAtLeast(0) + info.download.coerceAtLeast(0)
        if (!info.hasTrafficLimit && used == 0L) return null
        return Traffic(used, info.total.coerceAtLeast(0))
    }

    fun expiry(info: UserInfo?, nowMs: Long): Expiry? {
        if (info == null || !info.hasExpiry) return null
        val remaining = info.expireEpochSec * 1000 - nowMs
        return if (remaining <= 0) Expiry.Expired else Expiry.Left((remaining / DAY_MS).toInt())
    }

    /** Coarse age of [atMs]; a clock running behind reads as just now. */
    fun ago(atMs: Long, nowMs: Long): Ago {
        val minutes = ((nowMs - atMs) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> Ago.JustNow
            minutes < 60 -> Ago.Minutes(minutes.toInt())
            minutes < 24 * 60 -> Ago.Hours((minutes / 60).toInt())
            else -> Ago.Days((minutes / (24 * 60)).toInt())
        }
    }

    fun date(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("dd.MM.yyyy").format(Instant.ofEpochSecond(epochSec).atZone(zone))

    fun expiringSoon(expiry: Expiry?): Boolean = when (expiry) {
        null -> false
        Expiry.Expired -> true
        is Expiry.Left -> expiry.days < WARN_DAYS
    }

    /** Regional-indicator flag for an ISO 3166-1 alpha-2 code; null for anything else. */
    fun flag(country: String?): String? {
        val code = country?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
        return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    fun protocol(protocol: Protocol): String = when (protocol) {
        Protocol.VLESS -> "VLESS"
        Protocol.VMESS -> "VMess"
        Protocol.TROJAN -> "Trojan"
        Protocol.SHADOWSOCKS -> "Shadowsocks"
        Protocol.HYSTERIA -> "Hysteria"
        Protocol.HYSTERIA2 -> "Hysteria2"
        Protocol.TUIC -> "TUIC"
        Protocol.JUICITY -> "Juicity"
        Protocol.ANYTLS -> "AnyTLS"
        Protocol.SOCKS -> "SOCKS"
        Protocol.HTTP -> "HTTP"
        Protocol.WIREGUARD -> "WireGuard"
        Protocol.AMNEZIAWG -> "AmneziaWG"
        Protocol.FREETURN -> "FreeTurn"
    }
}
