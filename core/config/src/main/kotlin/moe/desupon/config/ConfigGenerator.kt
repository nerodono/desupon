package moe.desupon.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.desupon.model.Secret
import moe.desupon.model.network.DnsSettings
import moe.desupon.model.network.DnsStrategy
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Tier

/** Builds sing-box-extended 1.14 configs; every result passes [validate]. */
class ConfigGenerator {
    /** TUN-less config for the standalone prober: nodes only, no inbounds, cache file or experimental section. */
    fun probeConfig(nodes: List<Node>, dns: DnsSettings = DnsSettings()): GeneratedConfig {
        val resolver = resolver(dns.strategy)
        val built = buildNodes(nodes, resolver)
        val config = ConfigDto(
            log = LogDto(PROBE_LOG_LEVEL),
            dns = DnsDto(servers = listOf(DnsServerDto(type = "local", tag = DNS_LOCAL))),
            outbounds = built.outbounds,
            endpoints = built.endpoints.ifEmpty { null },
            route = RouteDto(defaultDomainResolver = resolver),
        )
        return finish(config, ConfigKind.PROBE, built.tags, built.rejected)
    }

    fun mainConfig(request: MainConfigRequest): GeneratedConfig {
        if (request.mtu !in MIN_MTU..MAX_MTU) throw ConfigException("mtu must be within $MIN_MTU..$MAX_MTU")
        if (!RoutingBuilder.PACKAGE_NAME.matches(request.selfPackage)) throw ConfigException("own package name is invalid")
        val resolver = resolver(request.dns.strategy)
        val tiers = tierOrder(request.mode).let { order -> order + (request.selection.keys - order.toSet()) }
        val ordered = tiers.flatMap { tier -> request.selection[tier].orEmpty().map { tier to it } }
        val built = buildNodes(ordered.map { it.second }, resolver)
        val default = request.preferred?.tag?.takeIf { it in built.tags } ?: built.tags.first()

        val warnings = mutableListOf<String>()
        val remote = RemoteDns.parse(request.dns.remote) ?: run {
            warnings += "remote DNS address unsupported, using the default"
            RemoteDns.parse(DnsSettings.DEFAULT_REMOTE)!!
        }
        val routing = RoutingBuilder.build(request.rules, request.ruleSets, request.selfPackage)

        val selector = encode(SelectorDto.serializer(), SelectorDto(tag = TAG_PROXY, outbounds = built.tags, default = default))
        val config = ConfigDto(
            log = LogDto(MAIN_LOG_LEVEL),
            dns = DnsDto(
                servers = listOf(DnsServerDto(type = "local", tag = DNS_LOCAL), remote),
                rules = routing.dnsRules.ifEmpty { null },
                final = DNS_REMOTE,
                strategy = strategyName(request.dns.strategy),
            ),
            inbounds = listOf(tun(request.mtu, request.lanBypass, request.dns.ipv4Only)),
            outbounds = listOf(selector) + built.outbounds + encode(DirectDto.serializer(), DirectDto(tag = TAG_DIRECT)),
            endpoints = built.endpoints.ifEmpty { null },
            route = RouteDto(
                rules = routing.routeRules,
                ruleSet = routing.ruleSets.ifEmpty { null },
                final = TAG_PROXY,
                defaultDomainResolver = resolver,
            ),
            experimental = ExperimentalDto(CacheFileDto()),
        )
        return finish(config, ConfigKind.MAIN, built.tags, built.rejected, routing.skipped, warnings)
    }

    /**
     * TUN that rejects every packet, DNS included: brings the tunnel up before anything is probed, so no app
     * traffic leaves in the clear meanwhile. A MAIN config without nodes, so there is no selector to pick.
     */
    fun placeholderConfig(lanBypass: Boolean = false, mtu: Int = DEFAULT_MTU, ipv4Only: Boolean = false): Secret {
        if (mtu !in MIN_MTU..MAX_MTU) throw ConfigException("mtu must be within $MIN_MTU..$MAX_MTU")
        val strategy = if (ipv4Only) DnsStrategy.IPV4_ONLY else DnsStrategy.PREFER_IPV4
        val config = ConfigDto(
            log = LogDto(MAIN_LOG_LEVEL),
            dns = DnsDto(servers = listOf(DnsServerDto(type = "local", tag = DNS_LOCAL)), strategy = strategyName(strategy)),
            inbounds = listOf(tun(mtu, lanBypass, ipv4Only)),
            outbounds = listOf(encode(BlockDto.serializer(), BlockDto(tag = TAG_BLOCK))),
            route = RouteDto(final = TAG_BLOCK, defaultDomainResolver = resolver(strategy)),
            experimental = ExperimentalDto(CacheFileDto()),
        )
        val json = configJson.encodeToString(ConfigDto.serializer(), config)
        val violations = ConfigValidator.validate(json, ConfigKind.MAIN)
        if (violations.isNotEmpty()) throw ConfigException(violations)
        return Secret(json)
    }

    fun validate(json: String, kind: ConfigKind? = null): List<String> = ConfigValidator.validate(json, kind)

    /**
     * Without an IPv6 address Android blocks IPv6 for tunnelled apps, which is how [ipv4Only] keeps it out;
     * IPv6 LAN excludes stay, since throw routes never make Android allow a family.
     */
    private fun tun(mtu: Int, lanBypass: Boolean, ipv4Only: Boolean) = TunInboundDto(
        tag = TAG_TUN,
        address = if (ipv4Only) TUN_ADDRESS.filterNot { ':' in it } else TUN_ADDRESS,
        mtu = mtu,
        routeExcludeAddress = LAN_RANGES.takeIf { lanBypass },
    )

    private class BuiltNodes(
        val outbounds: List<JsonObject>,
        val endpoints: List<JsonObject>,
        val tags: List<String>,
        val rejected: Map<NodeId, String>,
    )

    private fun buildNodes(nodes: List<Node>, resolver: DomainResolverDto): BuiltNodes {
        val outbounds = mutableListOf<JsonObject>()
        val endpoints = mutableListOf<JsonObject>()
        val tags = mutableListOf<String>()
        val rejected = LinkedHashMap<NodeId, String>()
        for (node in nodes.distinctBy { it.id }) {
            when (val outcome = NodeRules.normalize(node, resolver)) {
                is NodeRules.Outcome.Ok -> {
                    (if (outcome.endpoint) endpoints else outbounds) += outcome.json
                    for (hop in outcome.hops) (if (hop.endpoint) endpoints else outbounds) += hop.json
                    tags += node.id.tag
                }
                is NodeRules.Outcome.Rejected -> rejected[node.id] = outcome.reason
            }
        }
        if (tags.isEmpty()) {
            throw ConfigException(listOf("no usable nodes") + rejected.map { (id, reason) -> "${id.tag}: $reason" })
        }
        return BuiltNodes(outbounds, endpoints, tags, rejected)
    }

    private fun finish(
        config: ConfigDto,
        kind: ConfigKind,
        tags: List<String>,
        rejected: Map<NodeId, String>,
        skipped: List<SkippedRule> = emptyList(),
        warnings: List<String> = emptyList(),
    ): GeneratedConfig {
        val json = configJson.encodeToString(ConfigDto.serializer(), config)
        val violations = ConfigValidator.validate(json, kind)
        if (violations.isNotEmpty()) throw ConfigException(violations)
        return GeneratedConfig(Secret(json), tags, rejected, skipped, warnings)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject =
        configJson.encodeToJsonElement(serializer, value).jsonObject

    companion object {
        const val SELF_PACKAGE = "moe.desupon.app"
        const val TAG_PROXY = "proxy"
        const val TAG_DIRECT = "direct"
        const val TAG_BLOCK = "block"
        const val TAG_TUN = "tun-in"
        const val DNS_LOCAL = "local"
        const val DNS_REMOTE = "remote"

        const val DEFAULT_MTU = 1500
        const val MIN_MTU = 1280
        const val MAX_MTU = 1500

        /** sing-box types of the outbound [node] loads as and of every hop it dials through; empty when its JSON is unreadable. */
        fun dialedTypes(node: Node): Set<String> {
            val source = parseObject(node.outboundJson.raw()) ?: return emptySet()
            val hops = source.array(Node.CHAIN_KEY).mapNotNull { (it as? JsonObject)?.string("type") }
            return setOfNotNull(source.string("type")) + hops
        }

        private const val MAIN_LOG_LEVEL = "warn"
        private const val PROBE_LOG_LEVEL = "error"

        /** Away from the documented 172.19.0.1/30 default and outside every [LAN_RANGES] IPv4 prefix. */
        val TUN_ADDRESS = listOf("198.19.250.1/30", "fdc8:6e1a:9d3f::1/126")

        val LAN_RANGES = listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "fc00::/7",
            "fe80::/10",
        )

        /** Tier order of the selector listing; the brain picks members explicitly. */
        fun tierOrder(mode: NetworkMode): List<Tier> = when (mode) {
            NetworkMode.OPEN, NetworkMode.UNKNOWN -> listOf(Tier.DIRECT, Tier.RU, Tier.TURN)
            NetworkMode.WHITELIST, NetworkMode.OFFLINE -> listOf(Tier.RU, Tier.DIRECT, Tier.TURN)
        }

        internal fun strategyName(strategy: DnsStrategy): String = when (strategy) {
            DnsStrategy.PREFER_IPV4 -> "prefer_ipv4"
            DnsStrategy.IPV4_ONLY -> "ipv4_only"
        }

        private fun resolver(strategy: DnsStrategy) = DomainResolverDto(DNS_LOCAL, strategyName(strategy))
    }
}
