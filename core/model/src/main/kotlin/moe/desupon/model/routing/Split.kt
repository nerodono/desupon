package moe.desupon.model.routing

import moe.desupon.model.network.DnsSettings

enum class SplitMode { ALL, ONLY_SELECTED, ALL_EXCEPT_SELECTED }

data class AppSplit(
    val mode: SplitMode = SplitMode.ALL,
    val packages: Set<String> = emptySet(),
    /** [SplitMode.ALL_EXCEPT_SELECTED] only: installed packages starting with any of these bypass too. */
    val bypassPrefixes: Set<String> = emptySet(),
    /** [SplitMode.ALL_EXCEPT_SELECTED] only: through the tunnel whatever [packages] or [bypassPrefixes] say. */
    val forceTunnel: Set<String> = emptySet(),
)

enum class SplitRuleKind { DOMAIN, DOMAIN_SUFFIX, DOMAIN_KEYWORD, IP_CIDR, RULE_SET }

enum class SplitAction { DIRECT, PROXY, BLOCK }

data class SplitRule(
    val id: Long = 0,
    /** Ascending; the first matching enabled rule wins. */
    val order: Int,
    val enabled: Boolean = true,
    val kind: SplitRuleKind,
    /** Domain, suffix, keyword, CIDR, or a rule-set name for [SplitRuleKind.RULE_SET]. */
    val value: String,
    val action: SplitAction,
)

data class ConnectionSettings(
    val apps: AppSplit = AppSplit(),
    /** Route private ranges around the tunnel. */
    val lanBypass: Boolean = true,
    val dns: DnsSettings = DnsSettings(),
)
