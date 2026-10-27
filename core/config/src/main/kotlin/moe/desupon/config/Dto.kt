package moe.desupon.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val configJson = Json {
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
internal data class ConfigDto(
    val log: LogDto,
    val dns: DnsDto,
    val inbounds: List<TunInboundDto>? = null,
    val outbounds: List<JsonObject>,
    val endpoints: List<JsonObject>? = null,
    val route: RouteDto,
    val experimental: ExperimentalDto? = null,
)

@Serializable
internal data class LogDto(val level: String)

@Serializable
internal data class DnsDto(
    val servers: List<DnsServerDto>,
    val rules: List<DnsRuleDto>? = null,
    val final: String? = null,
    val strategy: String? = null,
)

@Serializable
internal data class DnsServerDto(
    val type: String,
    val tag: String,
    val server: String? = null,
    @SerialName("server_port") val serverPort: Int? = null,
    val path: String? = null,
    val detour: String? = null,
)

@Serializable
internal data class DnsRuleDto(
    val domain: List<String>? = null,
    @SerialName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerialName("domain_keyword") val domainKeyword: List<String>? = null,
    @SerialName("rule_set") val ruleSet: List<String>? = null,
    val action: String,
    val server: String? = null,
)

@Serializable
internal data class TunInboundDto(
    val type: String = "tun",
    val tag: String,
    val address: List<String>,
    val mtu: Int,
    @SerialName("auto_route") val autoRoute: Boolean = true,
    val stack: String = "gvisor",
    @SerialName("route_exclude_address") val routeExcludeAddress: List<String>? = null,
)

@Serializable
internal data class SelectorDto(
    val type: String = "selector",
    val tag: String,
    val outbounds: List<String>,
    val default: String,
    @SerialName("interrupt_exist_connections") val interruptExistConnections: Boolean = true,
)

@Serializable
internal data class DirectDto(
    val type: String = "direct",
    val tag: String,
)

@Serializable
internal data class BlockDto(
    val type: String = "block",
    val tag: String,
)

@Serializable
internal data class RouteDto(
    val rules: List<RouteRuleDto>? = null,
    @SerialName("rule_set") val ruleSet: List<RuleSetDto>? = null,
    val final: String? = null,
    @SerialName("auto_detect_interface") val autoDetectInterface: Boolean = true,
    @SerialName("default_domain_resolver") val defaultDomainResolver: DomainResolverDto,
)

@Serializable
internal data class RouteRuleDto(
    val protocol: String? = null,
    @SerialName("package_name") val packageName: List<String>? = null,
    val domain: List<String>? = null,
    @SerialName("domain_suffix") val domainSuffix: List<String>? = null,
    @SerialName("domain_keyword") val domainKeyword: List<String>? = null,
    @SerialName("ip_cidr") val ipCidr: List<String>? = null,
    @SerialName("rule_set") val ruleSet: List<String>? = null,
    val action: String,
    val outbound: String? = null,
)

@Serializable
internal data class RuleSetDto(
    val type: String = "local",
    val tag: String,
    val format: String,
    val path: String,
)

@Serializable
internal data class DomainResolverDto(
    val server: String,
    val strategy: String,
)

@Serializable
internal data class ExperimentalDto(
    @SerialName("cache_file") val cacheFile: CacheFileDto,
)

@Serializable
internal data class CacheFileDto(val enabled: Boolean = true)
