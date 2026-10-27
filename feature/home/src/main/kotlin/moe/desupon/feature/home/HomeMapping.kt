package moe.desupon.feature.home

import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.data.location.LocationListData
import moe.desupon.data.location.LocationNode
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.ConnectionButtonState
import java.util.Locale

/** Selection inputs of the home screen; defaults describe a fresh install. */
internal data class SelectionContext(
    val choice: LocationChoice = LocationChoice.Any,
    val setId: SetId = SetId.ALL,
    val sets: List<SubscriptionSetEntry> = emptyList(),
    val list: LocationListData? = null,
    val chain: List<SubId> = emptyList(),
    /** Null unless a session runs. */
    val step: ChainStep? = null,
)

internal fun homeUiState(
    conn: ConnState,
    subs: List<SubscriptionEntry>,
    sessionStartMs: Long?,
    nowMs: Long,
    liveSubscriptions: Set<SubId> = emptySet(),
    selection: SelectionContext = SelectionContext(),
): HomeUiState {
    val idle = conn is ConnState.Idle || conn is ConnState.Failed
    if (subs.isEmpty() && idle) return HomeUiState.Empty

    val node = conn.currentNode()
    val nodeSubIndex = node?.let { n -> subs.indexOfFirst { it.info.id in n.subs }.takeIf { it >= 0 } }
    val cardSub = nodeSubIndex?.let(subs::get) ?: subs.firstOrNull { it.info.userInfo != null }
    val userInfo = cardSub?.info?.userInfo
    val caption = caption(conn)

    return HomeUiState.Ready(
        button = buttonState(conn, subs, liveSubscriptions),
        caption = caption,
        action = if (idle) ButtonAction.CONNECT else ButtonAction.DISCONNECT,
        sessionStartMs = sessionStartMs,
        location = locationSummary(node, idle, subs, selection),
        traffic = userInfo?.let { trafficCard(it.upload + it.download, it.total) },
        expiry = userInfo?.takeIf { it.hasExpiry }?.let { ExpiryCard(daysLeft(it.expireEpochSec, nowMs)) },
        showRetry = conn is ConnState.Failed,
        offerAnyCountry = caption is Caption.CountryUnavailable || caption == Caption.NodeUnavailable,
    )
}

/** A running session names its actual node; otherwise the stored choice. */
internal fun locationSummary(node: Node?, idle: Boolean, subs: List<SubscriptionEntry>, selection: SelectionContext): LocationSummary {
    val place = if (node != null) Place.Named(flagEmoji(node.country), node.displayName) else when (val choice = selection.choice) {
        LocationChoice.Any -> Place.AnyCountry
        is LocationChoice.Country -> Place.Named(flagEmoji(choice.iso), countryName(choice.iso))
        is LocationChoice.Node ->
            selection.list?.findNode(choice.id)?.let { Place.Named(flagEmoji(it.country), it.displayName) } ?: Place.UnknownNode
    }
    val set = selection.sets.firstOrNull { it.set.id == selection.setId }
    val mixed = (set?.subscriptionCount ?: subs.size) > 1
    val chain = chainLine(subs, selection.chain, selection.step)
    return LocationSummary(
        place = place,
        auto = node != null && selection.choice !is LocationChoice.Node,
        setName = set?.takeUnless { it.isAll }?.set?.name,
        // A highlighted chain step already names the node's subscription.
        subscription = node?.takeIf { mixed && chain?.active == null }?.let { subscriptionLabel(subs, it.subs) },
        showHint = idle,
        chain = chain,
    )
}

/** Chain subscriptions missing from [subs] are skipped; null when nothing is left. */
internal fun chainLine(subs: List<SubscriptionEntry>, chain: List<SubId>, step: ChainStep?): ChainLine? {
    val labeled = chain.mapNotNull { id -> subscriptionLabel(subs, listOf(id))?.let { id to it } }
    if (labeled.isEmpty()) return null
    val activeId = (step as? ChainStep.Sub)?.id
    return ChainLine(
        steps = labeled.map { it.second },
        active = labeled.indexOfFirst { it.first == activeId }.takeIf { it >= 0 },
        rest = step == ChainStep.Rest,
    )
}

/** States in which the session runs on a node. */
internal val ConnState.running: Boolean get() = currentNode() != null

private fun LocationListData.findNode(id: NodeId): LocationNode? =
    pinnedOutsideSet?.takeIf { it.id == id }
        ?: countries.firstNotNullOfOrNull { c -> c.nodes.firstOrNull { it.id == id } }
        ?: other.firstOrNull { it.id == id }

/** First subscription in list order that carries the node. */
internal fun subscriptionLabel(subs: List<SubscriptionEntry>, of: Collection<SubId>): SubscriptionLabel? {
    val i = subs.indexOfFirst { it.info.id in of }.takeIf { it >= 0 } ?: return null
    return SubscriptionLabel(subs[i].info.title?.takeIf(String::isNotBlank), i + 1)
}

/** While connecting, one arc per subscription lights up once it has a live node; with none there is nothing to segment. */
internal fun buttonState(conn: ConnState, subs: List<SubscriptionEntry>, live: Set<SubId>): ConnectionButtonState = when (conn) {
    ConnState.Idle -> ConnectionButtonState.Disconnected
    is ConnState.Connecting ->
        if (subs.isNotEmpty()) ConnectionButtonState.Checking(subs.map { it.info.id in live })
        else ConnectionButtonState.Connecting
    is ConnState.TurnWarmup -> ConnectionButtonState.Connecting
    is ConnState.Connected, is ConnState.Degraded, is ConnState.Unstable -> ConnectionButtonState.Connected
    is ConnState.Switching -> ConnectionButtonState.Switching
    is ConnState.Failed -> ConnectionButtonState.Error
}

internal fun caption(conn: ConnState): Caption? = when (conn) {
    is ConnState.TurnWarmup -> Caption.TurnWarmup
    is ConnState.Degraded -> locationCaption(conn.reason, conn.detail) ?: Caption.Degraded
    is ConnState.Unstable -> Caption.Unstable
    is ConnState.Failed -> locationCaption(conn.reason, conn.detail) ?: when (conn.reason) {
        // The button's own error caption already says no server is available.
        FailReason.NO_NODES -> null
        FailReason.CONFIG -> Caption.FailConfig
        FailReason.CORE -> Caption.FailCore
        FailReason.NOT_PREPARED -> Caption.FailNotPrepared
        FailReason.START_BLOCKED -> Caption.FailStartBlocked
        else -> Caption.FailUnknown
    }
    else -> null
}

private fun locationCaption(reason: String?, detail: String?): Caption? = when (reason) {
    FailReason.COUNTRY_UNAVAILABLE -> Caption.CountryUnavailable(detail?.takeIf { it.isNotBlank() }?.let(::countryName))
    FailReason.NODE_UNAVAILABLE -> Caption.NodeUnavailable
    else -> null
}

/** Session start survives degraded/unstable/switching phases, which carry no start time of their own. */
internal fun nextSessionStart(previous: Long?, conn: ConnState): Long? = when (conn) {
    is ConnState.Connected -> conn.connectedAtMs
    is ConnState.Degraded, is ConnState.Unstable, is ConnState.Switching -> previous
    else -> null
}

private fun ConnState.currentNode(): Node? = when (this) {
    is ConnState.Connected -> node
    is ConnState.Degraded -> node
    is ConnState.Switching -> to
    is ConnState.Unstable -> best
    else -> null
}

/** Null when the header reports neither usage nor a limit. */
internal fun trafficCard(usedBytes: Long, totalBytes: Long): TrafficCard? {
    if (usedBytes <= 0 && totalBytes <= 0) return null
    return TrafficCard(used = formatBytes(usedBytes), total = totalBytes.takeIf { it > 0 }?.let(::formatBytes))
}

internal val RU: Locale = Locale.forLanguageTag("ru-RU")
private const val KIB = 1024.0

/** Binary units, as subscription panels count them; one decimal below 100, trailing ",0" dropped. */
internal fun formatBytes(bytes: Long): ByteAmount {
    val b = bytes.coerceAtLeast(0)
    if (b < KIB) return ByteAmount(b.toString(), ByteUnit.B)
    var value = b.toDouble()
    var unit = 0
    while (value >= KIB && unit < ByteUnit.TB.ordinal) {
        value /= KIB
        unit++
    }
    val text = if (value >= 100) String.format(RU, "%.0f", value) else String.format(RU, "%.1f", value).removeSuffix(",0")
    return ByteAmount(text, ByteUnit.entries[unit])
}

private const val DAY_MS = 86_400_000L

/** Whole days left, a started day counting as one; 0 once expired. */
internal fun daysLeft(expireEpochSec: Long, nowMs: Long): Int {
    val left = expireEpochSec * 1000 - nowMs
    if (left <= 0) return 0
    return ((left + DAY_MS - 1) / DAY_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun flagEmoji(country: String?): String? {
    val code = country?.uppercase(Locale.ROOT)?.takeIf { c -> c.length == 2 && c.all { it in 'A'..'Z' } } ?: return null
    return buildString { code.forEach { appendCodePoint(REGIONAL_A + (it - 'A')) } }
}

private const val REGIONAL_A = 0x1F1E6

/** Russian display name; the code itself when the platform has none. */
internal fun countryName(iso: String): String {
    val code = iso.trim().uppercase(Locale.ROOT)
    val name = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(RU) }.getOrNull()
    return name?.takeIf { it.isNotBlank() && it != code } ?: code
}
