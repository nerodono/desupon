package moe.desupon.feature.home

import moe.desupon.data.location.LocationListData
import moe.desupon.data.location.LocationNode
import moe.desupon.data.location.ProbeState
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import java.text.Collator

internal const val RUSSIA = "RU"
private const val GOOD_RTT_MS = 120
private const val FAIR_RTT_MS = 350

internal fun locationSheetState(
    data: LocationListData?,
    sets: List<SubscriptionSetEntry>,
    activeSet: SetId,
    choice: LocationChoice,
    mode: NetworkMode?,
    /** A probe the user asked for is running; automatic ones show only per row. */
    refreshing: Boolean,
    subs: List<SubscriptionEntry>,
    chain: ChainLine? = null,
): LocationSheetState {
    val active = sets.firstOrNull { it.set.id == activeSet }
    val mixed = (active?.subscriptionCount ?: subs.size) > 1
    val pinned = (choice as? LocationChoice.Node)?.id

    fun row(n: LocationNode) = NodeRow(
        id = n.id,
        name = n.displayName,
        flag = flagEmoji(n.country),
        state = n.state,
        rttMs = n.rttMs,
        selected = n.id == pinned,
        subscription = if (mixed) subscriptionLabel(subs, n.subscriptions.map { it.id }) else null,
    )

    val countries = data?.countries.orEmpty().map { c ->
        CountryRow(
            iso = c.iso,
            name = countryName(c.iso),
            flag = flagEmoji(c.iso),
            relay = c.iso == RUSSIA,
            state = c.state,
            bestRttMs = c.bestRttMs,
            selected = choice is LocationChoice.Country && choice.iso == c.iso,
            nodes = sortNodes(c.nodes).map(::row),
        )
    }
    return LocationSheetState(
        sets = sets.map { SetChip(it.set.id, if (it.isAll) null else it.set.name, it.set.id == activeSet) },
        refreshing = refreshing,
        whitelistBanner = showWhitelistBanner(mode, choice, data),
        anySelected = choice == LocationChoice.Any,
        pinnedOutside = data?.pinnedOutsideSet?.let(::row),
        countries = sortCountries(countries),
        other = sortNodes(data?.other.orEmpty()).map(::row),
        chain = chain,
    )
}

/** Last answered RTT per node, valid for [network] only. */
internal data class RttMemory(val network: NetworkKey? = null, val rtts: Map<NodeId, Int> = emptyMap())

/**
 * Gives CHECKING nodes their last answered RTT, and CHECKING countries the best of those, so a re-probe dims values instead of hiding them.
 * Returns the filled list and the memory for the next emission.
 */
internal fun carryRtts(memory: RttMemory, data: LocationListData): Pair<LocationListData, RttMemory> {
    val known = if (memory.network == data.network) memory.rtts else emptyMap()
    val next = HashMap<NodeId, Int>()
    fun fill(n: LocationNode): LocationNode {
        val rtt = when (n.state) {
            ProbeState.OK -> n.rttMs
            ProbeState.CHECKING -> n.rttMs ?: known[n.id]
            else -> null
        } ?: return n
        next[n.id] = rtt
        return if (rtt == n.rttMs) n else n.copy(rttMs = rtt)
    }
    val countries = data.countries.map { c ->
        val nodes = c.nodes.map(::fill)
        val best = if (c.state == ProbeState.CHECKING) nodes.mapNotNull { it.rttMs }.minOrNull() else c.bestRttMs
        c.copy(nodes = nodes, bestRttMs = best)
    }
    val filled = data.copy(countries = countries, other = data.other.map(::fill), pinnedOutsideSet = data.pinnedOutsideSet?.let(::fill))
    return filled to RttMemory(data.network, next)
}

/** Whitelists let through only Russian relays, so any choice abroad goes nowhere. */
internal fun showWhitelistBanner(mode: NetworkMode?, choice: LocationChoice, data: LocationListData?): Boolean {
    if (mode != NetworkMode.WHITELIST) return false
    return when (choice) {
        LocationChoice.Any -> false
        is LocationChoice.Country -> choice.iso != RUSSIA
        is LocationChoice.Node -> data?.countries?.firstOrNull { c -> c.nodes.any { it.id == choice.id } }?.iso != RUSSIA
    }
}

/** A row still showing an RTT ranks as answering, so a re-probe does not reshuffle the list. */
private fun rank(state: ProbeState, rttMs: Int?): Int = when {
    rttMs != null || state == ProbeState.OK -> 0
    state == ProbeState.CHECKING -> 1
    state == ProbeState.UNKNOWN -> 2
    else -> 3
}

/** Russia first, then answering by best RTT, checking, unknown, dead; names break ties. */
internal fun sortCountries(rows: List<CountryRow>): List<CountryRow> {
    val collator = Collator.getInstance(RU)
    return rows.sortedWith(
        compareBy<CountryRow>({ !it.relay }, { rank(it.state, it.bestRttMs) }, { it.bestRttMs ?: Int.MAX_VALUE })
            .thenComparator { a, b -> collator.compare(a.name, b.name) },
    )
}

/** Answering by RTT, then checking, unknown, dead; pool order otherwise. */
internal fun sortNodes(nodes: List<LocationNode>): List<LocationNode> =
    nodes.sortedWith(compareBy({ rank(it.state, it.rttMs) }, { it.rttMs ?: Int.MAX_VALUE }))

internal fun rttLevel(ms: Int): RttLevel = when {
    ms <= GOOD_RTT_MS -> RttLevel.GOOD
    ms <= FAIR_RTT_MS -> RttLevel.FAIR
    else -> RttLevel.SLOW
}

/** Seconds to wait, rounded up so "0 s" never shows. */
internal fun throttleSeconds(retryInMs: Long): Long = ((retryInMs.coerceAtLeast(1) + 999) / 1000)
