package moe.desupon.config

import moe.desupon.model.Secret
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier
import moe.desupon.model.routing.SplitRule

/** Bundled rule-set file; [domainBased] sets whether DNS rules may reference it (IP rule-sets there are a legacy address filter). */
data class LocalRuleSet(
    val path: String,
    val domainBased: Boolean = true,
)

data class MainConfigRequest(
    /** Nodes to load, best first within a tier. */
    val selection: Map<Tier, List<Node>>,
    val mode: NetworkMode,
    val rules: List<SplitRule> = emptyList(),
    /** Rule-set name used in [SplitRule.value] to its local file. */
    val ruleSets: Map<String, LocalRuleSet> = emptyMap(),
    val dns: DnsSettings = DnsSettings(),
    val lanBypass: Boolean = false,
    val mtu: Int = ConfigGenerator.DEFAULT_MTU,
    /** Package always routed through `proxy`; the running app's own name, which differs between build types. */
    val selfPackage: String = ConfigGenerator.SELF_PACKAGE,
    /** Selector default, so the box starts on the brain's seed; the first node when absent or not built. */
    val preferred: NodeId? = null,
)

data class SkippedRule(val rule: SplitRule, val reason: String)

data class GeneratedConfig(
    val json: Secret,
    /** Node tags in the selector, in failover order. */
    val nodeTags: List<String>,
    /** Nodes left out, with a reason that carries no secrets. */
    val rejected: Map<NodeId, String>,
    val skippedRules: List<SkippedRule> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Raised when no usable config can be built; [violations] never contain secrets. */
class ConfigException(val violations: List<String>) : Exception(violations.joinToString("; ")) {
    constructor(violation: String) : this(listOf(violation))
}

enum class ConfigKind { PROBE, MAIN }
