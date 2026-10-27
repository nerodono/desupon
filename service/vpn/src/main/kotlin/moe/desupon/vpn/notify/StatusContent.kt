package moe.desupon.vpn.notify

import android.content.res.Resources
import androidx.annotation.StringRes
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.status.TrafficStatus
import moe.desupon.vpn.R
import java.util.Locale
import kotlin.math.roundToInt

/** What the status notification shows: live details while connected, a neutral state word otherwise. */
sealed interface StatusContent {
    data class Plain(@StringRes val text: Int) : StatusContent

    /** [rttMs] null when the node has no measured round trip on this network yet. */
    data class Connected(
        val node: Node,
        val sinceMs: Long,
        val subscription: SubscriptionLabel?,
        val rttMs: Int?,
        val traffic: TrafficStatus,
    ) : StatusContent
}

/** [title] null falls back to "Подписка [ordinal]". */
data class SubscriptionLabel(val title: String?, val ordinal: Int)

/** Notification lines of [StatusContent.Connected]. */
internal class ConnectedText(val title: String, val text: String, val subText: String?, val bigText: String)

private val RU: Locale = Locale.forLanguageTag("ru-RU")
private const val SEPARATOR = " · "
private const val KIB = 1024.0

internal fun Resources.connectedText(c: StatusContent.Connected): ConnectedText {
    val speeds = listOfNotNull(
        c.rttMs?.let { getString(R.string.svc_rtt, it) },
        getString(R.string.svc_down, rate(c.traffic.downlinkRate)),
        getString(R.string.svc_up, rate(c.traffic.uplinkRate)),
    ).joinToString(SEPARATOR)
    val session = getString(R.string.svc_session, amount(c.traffic.downlinkTotal), amount(c.traffic.uplinkTotal))
    val server = getString(R.string.svc_server, c.node.displayName)
    return ConnectedText(
        title = placeTitle(c.node),
        text = speeds,
        subText = c.subscription?.let { it.title ?: getString(R.string.svc_subscription_untitled, it.ordinal) },
        bigText = listOf(speeds, session, server).joinToString("\n"),
    )
}

/** Flag and Russian country name; the node name when the country is unknown. */
internal fun placeTitle(node: Node): String {
    val iso = node.country?.uppercase(Locale.ROOT)?.takeIf { it in ISO_COUNTRIES } ?: return node.displayName
    val name = Locale.Builder().setRegion(iso).build().getDisplayCountry(RU)
    if (name.isBlank() || name == iso) return node.displayName
    val flag = buildString { iso.forEach { appendCodePoint(REGIONAL_A + (it - 'A')) } }
    return "$flag $name"
}

private const val REGIONAL_A = 0x1F1E6

private val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toSet()

internal fun Resources.rate(bytesPerSec: Long): String = scaled(bytesPerSec) { value, unit -> getString(R.string.svc_rate, value, getString(unit)) }

internal fun Resources.amount(bytes: Long): String = scaled(bytes) { value, unit -> getString(R.string.svc_amount, value, getString(unit)) }

private val UNITS = intArrayOf(R.string.svc_unit_b, R.string.svc_unit_kb, R.string.svc_unit_mb, R.string.svc_unit_gb, R.string.svc_unit_tb)

/** Binary units with a decimal comma: one decimal below 100, a trailing ",0" dropped. */
private inline fun scaled(bytes: Long, format: (String, Int) -> String): String {
    val b = bytes.coerceAtLeast(0)
    if (b < KIB) return format(b.toString(), UNITS[0])
    var value = b.toDouble()
    var unit = 0
    while (value >= KIB && unit < UNITS.lastIndex) {
        value /= KIB
        unit++
    }
    val text = if (value >= 100) String.format(RU, "%.0f", value) else String.format(RU, "%.1f", value).removeSuffix(",0")
    return format(text, UNITS[unit])
}

/** The smoothed probe round trip of [node]; null before it has a sample on this network. */
internal fun warmRtt(stats: Map<NodeId, ProbeStat>, node: Node): Int? =
    stats[node.id]?.takeIf { it.rttSamples > 0 }?.ewmaRttMs?.roundToInt()

/** The first subscription in list order that carries [node]. */
internal fun subscriptionOf(subs: List<SubscriptionEntry>, node: Node): SubscriptionLabel? {
    val i = subs.indexOfFirst { it.info.id in node.subs }.takeIf { it >= 0 } ?: return null
    return SubscriptionLabel(subs[i].info.title?.takeIf(String::isNotBlank), i + 1)
}
