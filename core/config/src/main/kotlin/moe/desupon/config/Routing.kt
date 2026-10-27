package moe.desupon.config

import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal class Routing(
    val routeRules: List<RouteRuleDto>,
    val dnsRules: List<DnsRuleDto>,
    val ruleSets: List<RuleSetDto>,
    val skipped: List<SkippedRule>,
)

internal object RoutingBuilder {
    private val RULE_SET_NAME = Regex("[A-Za-z0-9._-]+")
    private val DOMAIN_TEXT = Regex("[^\\s,\"'/\\\\]+")
    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
    private val IPV6_CHARS = Regex("[0-9A-Fa-f:.]+")
    internal val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Fixed head of route.rules; user rules follow in [SplitRule.order]. */
    fun headRules(selfPackage: String): List<RouteRuleDto> = listOf(
        RouteRuleDto(action = "sniff"),
        RouteRuleDto(protocol = "dns", action = "hijack-dns"),
        RouteRuleDto(packageName = listOf(selfPackage), action = "route", outbound = ConfigGenerator.TAG_PROXY),
    )

    fun build(rules: List<SplitRule>, ruleSets: Map<String, LocalRuleSet>, selfPackage: String): Routing {
        val routeRules = headRules(selfPackage).toMutableList()
        val dnsRules = mutableListOf<DnsRuleDto>()
        val usedSets = LinkedHashMap<String, RuleSetDto>()
        val skipped = mutableListOf<SkippedRule>()
        for (rule in rules.filter { it.enabled }.sortedBy { it.order }) {
            val value = rule.value.trim()
            val match: Match = when (rule.kind) {
                SplitRuleKind.DOMAIN, SplitRuleKind.DOMAIN_SUFFIX, SplitRuleKind.DOMAIN_KEYWORD -> {
                    val domain = value.lowercase(Locale.ROOT)
                    if (!DOMAIN_TEXT.matches(domain)) {
                        skipped += SkippedRule(rule, "invalid domain")
                        continue
                    }
                    Match(rule.kind, domain, dns = true)
                }
                SplitRuleKind.IP_CIDR -> {
                    val cidr = normalizeCidr(value)
                    if (cidr == null) {
                        skipped += SkippedRule(rule, "invalid CIDR")
                        continue
                    }
                    Match(rule.kind, cidr, dns = false)
                }
                SplitRuleKind.RULE_SET -> {
                    val set = ruleSets[value]
                    if (!RULE_SET_NAME.matches(value) || set == null) {
                        skipped += SkippedRule(rule, "rule-set unavailable")
                        continue
                    }
                    usedSets.getOrPut(value) {
                        RuleSetDto(tag = value, format = if (set.path.endsWith(".json")) "source" else "binary", path = set.path)
                    }
                    Match(rule.kind, value, dns = set.domainBased)
                }
            }
            routeRules += match.routeRule(rule.action)
            if (match.dns) dnsRules += match.dnsRule(rule.action)
        }
        return Routing(routeRules, dnsRules, usedSets.values.toList(), skipped)
    }

    private class Match(val kind: SplitRuleKind, val value: String, val dns: Boolean) {
        private val one = listOf(value)

        fun routeRule(action: SplitAction): RouteRuleDto {
            val (verb, outbound) = when (action) {
                SplitAction.DIRECT -> "route" to ConfigGenerator.TAG_DIRECT
                SplitAction.PROXY -> "route" to ConfigGenerator.TAG_PROXY
                SplitAction.BLOCK -> "reject" to null
            }
            return RouteRuleDto(
                domain = one.takeIf { kind == SplitRuleKind.DOMAIN },
                domainSuffix = one.takeIf { kind == SplitRuleKind.DOMAIN_SUFFIX },
                domainKeyword = one.takeIf { kind == SplitRuleKind.DOMAIN_KEYWORD },
                ipCidr = one.takeIf { kind == SplitRuleKind.IP_CIDR },
                ruleSet = one.takeIf { kind == SplitRuleKind.RULE_SET },
                action = verb,
                outbound = outbound,
            )
        }

        fun dnsRule(action: SplitAction): DnsRuleDto {
            val (verb, server) = when (action) {
                SplitAction.DIRECT -> "route" to ConfigGenerator.DNS_LOCAL
                SplitAction.PROXY -> "route" to ConfigGenerator.DNS_REMOTE
                SplitAction.BLOCK -> "reject" to null
            }
            return DnsRuleDto(
                domain = one.takeIf { kind == SplitRuleKind.DOMAIN },
                domainSuffix = one.takeIf { kind == SplitRuleKind.DOMAIN_SUFFIX },
                domainKeyword = one.takeIf { kind == SplitRuleKind.DOMAIN_KEYWORD },
                ruleSet = one.takeIf { kind == SplitRuleKind.RULE_SET },
                action = verb,
                server = server,
            )
        }
    }

    /** IP literal or prefix to canonical `addr/len`; never triggers a DNS lookup. */
    internal fun normalizeCidr(text: String): String? {
        val slash = text.indexOf('/')
        val addr = if (slash < 0) text else text.substring(0, slash)
        val prefix = if (slash < 0) null else text.substring(slash + 1).toIntOrNull() ?: return null
        IPV4.matchEntire(addr)?.let { m ->
            if (m.groupValues.drop(1).any { it.toInt() > 255 }) return null
            if (prefix != null && prefix !in 0..32) return null
            return "$addr/${prefix ?: 32}"
        }
        if (':' !in addr || !IPV6_CHARS.matches(addr)) return null
        if (prefix != null && prefix !in 0..128) return null
        try {
            InetAddress.getByName(addr)
        } catch (_: Exception) {
            return null
        }
        return "${addr.lowercase(Locale.ROOT)}/${prefix ?: 128}"
    }
}

internal object RemoteDns {
    private val SCHEMES = mapOf(
        "https" to (443 to "/dns-query"),
        "h3" to (443 to "/dns-query"),
        "tls" to (853 to null),
        "quic" to (853 to null),
        "tcp" to (53 to null),
        "udp" to (53 to null),
    )

    /** `remote` server reached through `proxy`; null when [spec] is not a supported DNS address. */
    fun parse(spec: String): DnsServerDto? {
        val text = spec.trim()
        if (text.isEmpty()) return null
        if ("://" !in text && text.count { it == ':' } > 1) return server("udp", text.removeSurrounding("[", "]"), null, null)
        val uri = try {
            URI(if ("://" in text) text else "udp://$text")
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val (defaultPort, defaultPath) = SCHEMES[scheme] ?: return null
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val port = uri.port.takeIf { it != -1 && it != defaultPort }
        val path = if (defaultPath == null) {
            if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
            null
        } else {
            uri.path?.takeIf { it.isNotEmpty() && it != defaultPath }
        }
        return server(scheme, host, port, path)
    }

    private fun server(type: String, host: String, port: Int?, path: String?) = DnsServerDto(
        type = type,
        tag = ConfigGenerator.DNS_REMOTE,
        server = host,
        serverPort = port,
        path = path,
        detour = ConfigGenerator.TAG_PROXY,
    )
}
