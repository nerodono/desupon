package moe.desupon.data.db

import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.probe.ProbeStage
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UserInfo

private const val LABEL_SEPARATOR = "\n"

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

internal fun Node.toEntity(unsupportedReason: String?) = NodeEntity(
    id = id.hash,
    protocol = protocol.name,
    displayName = displayName,
    country = country,
    capability = capability.name,
    outboundJson = outboundJson.raw(),
    isEndpoint = isEndpoint,
    udpOnly = udpOnly,
    labels = labels.joinToString(LABEL_SEPARATOR),
    unsupportedReason = unsupportedReason,
)

/** Null when the row names a protocol this build no longer knows. */
internal fun NodeEntity.toNode(subId: Long): Node? {
    val proto = enumOrNull<Protocol>(protocol) ?: return null
    return Node(
        id = NodeId(id),
        subs = setOf(SubId(subId)),
        protocol = proto,
        displayName = displayName,
        country = country,
        capability = enumOrNull<Capability>(capability) ?: Capability.UNSUPPORTED,
        outboundJson = Secret(outboundJson),
        isEndpoint = isEndpoint,
        udpOnly = udpOnly,
        labels = labels.split(LABEL_SEPARATOR).filter { it.isNotEmpty() }.toSet(),
    )
}

internal fun UserInfo.toColumns() = UserInfoColumns(upload, download, total, expireEpochSec)

internal fun UserInfoColumns.toModel() = UserInfo(upload, download, total, expire)

internal fun ProbeStat.toEntity(updatedAt: Long) = ProbeStatEntity(
    nodeId = nodeId.hash,
    networkKey = network.encode(),
    ewmaRttMs = ewmaRttMs,
    ok = ok,
    fail = fail,
    failStreak = failStreak,
    lastStage = lastStage?.name,
    lastOkAt = lastOkAtMs,
    updatedAt = updatedAt,
    ewmaConnectMs = ewmaConnectMs,
    madMs = madMs,
    lastFailAt = lastFailAtMs,
    okRecent = okRecent,
    failRecent = failRecent,
    rttSamples = rttSamples,
    ewmaKbps = ewmaKbps,
    throttled = throttled,
)

internal fun ProbeStatEntity.toModel(network: NetworkKey) = ProbeStat(
    nodeId = NodeId(nodeId),
    network = network,
    ewmaRttMs = ewmaRttMs,
    ok = ok,
    fail = fail,
    failStreak = failStreak,
    lastStage = enumOrNull<ProbeStage>(lastStage),
    lastOkAtMs = lastOkAt,
    ewmaConnectMs = ewmaConnectMs,
    madMs = madMs,
    lastFailAtMs = lastFailAt,
    okRecent = okRecent,
    failRecent = failRecent,
    rttSamples = rttSamples,
    ewmaKbps = ewmaKbps,
    throttled = throttled,
)

internal fun NetworkMemory.toEntity() = NetworkMemoryEntity(
    networkKey = key.encode(),
    mode = mode.name,
    lastGoodNodeId = lastGood?.hash,
    updatedAt = updatedAtMs,
)

internal fun NetworkMemoryEntity.toModel(key: NetworkKey) = NetworkMemory(
    key = key,
    mode = enumOrNull<NetworkMode>(mode) ?: NetworkMode.UNKNOWN,
    lastGood = lastGoodNodeId?.let(::NodeId),
    updatedAtMs = updatedAt,
)

internal fun SplitRule.toEntity() = SplitRuleEntity(
    id = id,
    order = order,
    enabled = enabled,
    kind = kind.name,
    value = value,
    action = action.name,
)

/** Null when the row names a kind or action this build no longer knows. */
internal fun SplitRuleEntity.toModel(): SplitRule? {
    val k = enumOrNull<SplitRuleKind>(kind) ?: return null
    val a = enumOrNull<SplitAction>(action) ?: return null
    return SplitRule(id = id, order = order, enabled = enabled, kind = k, value = value, action = a)
}
