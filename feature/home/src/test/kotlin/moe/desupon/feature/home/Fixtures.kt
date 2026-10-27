package moe.desupon.feature.home

import moe.desupon.data.location.LocationCountry
import moe.desupon.data.location.LocationListData
import moe.desupon.data.location.LocationNode
import moe.desupon.data.location.ProbeState
import moe.desupon.data.location.SubscriptionRef
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionSetEntry
import moe.desupon.model.Secret
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.selection.SubscriptionSet
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionInfo
import moe.desupon.model.subscription.UserInfo

internal const val GIB = 1024L * 1024 * 1024
internal const val NOW_MS = 1_800_000_000_000L
internal const val DAY_MILLIS = 86_400_000L

internal fun sub(id: Long, title: String? = "Sub $id", userInfo: UserInfo? = null) = SubscriptionEntry(
    info = SubscriptionInfo(
        id = SubId(id),
        url = Secret("https://example.invalid/sub/$id"),
        title = title,
        userInfo = userInfo,
        updateIntervalH = null,
        supportUrl = null,
        webPageUrl = null,
        announce = null,
        lastOkAtMs = null,
        lastError = null,
    ),
    nodeCount = 3,
    isLocal = false,
    userAgent = null,
    lastAttemptAtMs = null,
    skippedCount = null,
)

internal fun node(name: String, vararg subs: Long, country: String? = "NL") = Node(
    id = nodeId(name),
    subs = subs.map(::SubId).toSet(),
    protocol = Protocol.VLESS,
    displayName = name,
    country = country,
    capability = Capability.SUPPORTED,
    outboundJson = Secret("{}"),
    isEndpoint = false,
    udpOnly = false,
)

internal fun setEntry(id: Long, name: String, vararg members: Long, active: Boolean = false) = SubscriptionSetEntry(
    set = SubscriptionSet(SetId(id), name, position = id.toInt(), members = members.map(::SubId).toSet()),
    subscriptionCount = members.size,
    supportedNodeCount = 0,
    enabledNodeCount = 0,
    isActive = active,
)

internal fun locNode(
    name: String,
    country: String? = "NL",
    state: ProbeState = ProbeState.UNKNOWN,
    rtt: Int? = null,
    vararg subs: Long,
) = LocationNode(
    id = nodeId(name),
    displayName = name,
    country = country,
    protocol = Protocol.VLESS,
    subscriptions = subs.map { SubscriptionRef(SubId(it), null) },
    rttMs = rtt,
    state = state,
    inActiveSet = true,
)

internal fun locCountry(iso: String, state: ProbeState, best: Int? = null, nodes: List<LocationNode> = emptyList()) =
    LocationCountry(iso, nodes.size, best, state, nodes)

internal fun listData(
    countries: List<LocationCountry> = emptyList(),
    other: List<LocationNode> = emptyList(),
    pinnedOutside: LocationNode? = null,
    choice: LocationChoice = LocationChoice.Any,
) = LocationListData(SetId.ALL, choice, countries, other, pinnedOutside, network = null)
internal fun nodeId(name: String) = NodeId(name.padEnd(64, '0'))
