package moe.desupon.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** Stealth and core-compatibility invariants; messages name locations and fields, never values. */
internal object ConfigValidator {
    private val GROUP_TYPES = setOf("selector", "urltest", "fallback")
    private val BUILTIN_TYPES = setOf("direct", "block", "dns")
    private val FORBIDDEN_EXPERIMENTAL = listOf("clash_api", "v2ray_api", "debug")
    private val PER_APP_KEYS = listOf("include_package", "exclude_package")
    private val SAFE_TAG = Regex("n-[0-9a-f]{16}(-[1-9])?")
    private val HOP_TAG = Regex("n-[0-9a-f]{16}-[1-9]")
    private val SAFE_TYPE = Regex("[a-z0-9_-]{1,24}")
    private val FIXED_TAGS = setOf(
        ConfigGenerator.TAG_PROXY,
        ConfigGenerator.TAG_DIRECT,
        ConfigGenerator.TAG_TUN,
        ConfigGenerator.TAG_BLOCK,
    )

    fun validate(json: String, kind: ConfigKind?): List<String> {
        val root = parseObject(json) ?: return listOf("config is not a JSON object")
        val k = kind ?: if (root.array("inbounds").isNotEmpty()) ConfigKind.MAIN else ConfigKind.PROBE
        val out = mutableListOf<String>()
        if (root.containsKey("services")) out += "services section present"
        if (root.obj("log")?.containsKey("output") == true) out += "log.output set"
        checkExperimental(root, k, out)
        checkInbounds(root, k, out)
        val outboundTags = checkNodes(root, out)
        val dnsTags = checkDns(root, outboundTags, out)
        checkRoute(root, k, outboundTags, dnsTags, out)
        return out
    }

    private fun checkExperimental(root: JsonObject, kind: ConfigKind, out: MutableList<String>) {
        val experimental = root.obj("experimental")
        if (kind == ConfigKind.PROBE) {
            if (experimental != null) out += "experimental section in probe config"
            return
        }
        FORBIDDEN_EXPERIMENTAL.filter { experimental?.containsKey(it) == true }.forEach { out += "experimental.$it present" }
        if (experimental?.obj("cache_file")?.bool("enabled") != true) out += "cache_file not enabled"
    }

    private fun checkInbounds(root: JsonObject, kind: ConfigKind, out: MutableList<String>) {
        val inbounds = root.array("inbounds")
        if (kind == ConfigKind.PROBE) {
            if (root.containsKey("inbounds")) out += "inbounds in probe config"
            return
        }
        val tun = inbounds.singleOrNull() as? JsonObject
        if (tun == null || tun.string("type") != "tun") {
            out += "the only inbound must be tun"
            return
        }
        if (tun.string("stack") != "gvisor") out += "tun stack must be gvisor"
        val mtu = tun.int("mtu")
        if (mtu == null) {
            out += "tun mtu not explicit"
        } else if (mtu !in ConfigGenerator.MIN_MTU..ConfigGenerator.MAX_MTU) {
            out += "tun mtu out of range"
        }
        if (tun.bool("auto_route") != true) out += "tun auto_route not enabled"
        PER_APP_KEYS.filter { tun.containsKey(it) }.forEach { out += "tun $it set: per-app split goes through OverrideOptions" }
        if (tun.obj("platform")?.containsKey("http_proxy") == true) out += "tun platform http_proxy set"
        if (tun.containsKey("listen") || tun.containsKey("listen_port")) out += "tun listen set"
        val addresses = stringList(tun["address"])
        if (addresses.isEmpty()) out += "tun address missing"
        if (addresses.any(::isDocumentedDefault)) out += "tun uses the documented default address"
    }

    private fun isDocumentedDefault(prefix: String): Boolean {
        val addr = prefix.substringBefore('/').lowercase(Locale.ROOT)
        if (addr.startsWith("fdfe:dcba:9876::")) return true
        if (!addr.startsWith("172.19.0.")) return false
        return (addr.substringAfterLast('.').toIntOrNull() ?: return false) in 0..3
    }

    /** Returns every outbound and endpoint tag. */
    private fun checkNodes(root: JsonObject, out: MutableList<String>): Set<String> {
        val tags = mutableSetOf<String>()
        for ((section, endpoint) in listOf("outbounds" to false, "endpoints" to true)) {
            root.array(section).forEachIndexed { i, element ->
                val obj = element as? JsonObject
                val where = "$section[$i]"
                if (obj == null) {
                    out += "$where is not an object"
                    return@forEachIndexed
                }
                val label = "$where (${label(obj)})"
                val tag = obj.string("tag")
                if (tag.isNullOrEmpty()) out += "$label has no tag" else if (!tags.add(tag)) out += "$label duplicate tag"
                val type = obj.string("type")
                if (!endpoint && (type in GROUP_TYPES || type in BUILTIN_TYPES)) return@forEachIndexed
                NodeRules.violations(obj, endpoint).forEach { out += "$label: $it" }
            }
        }
        for (section in listOf("outbounds", "endpoints")) {
            root.array(section).filterIsInstance<JsonObject>().forEachIndexed { i, obj ->
                val label = "$section[$i] (${label(obj)})"
                if (obj.string("type") in GROUP_TYPES && stringList(obj["outbounds"]).any { it !in tags }) {
                    out += "$label references an unknown outbound"
                }
                if (obj.string("type") in GROUP_TYPES && stringList(obj["outbounds"]).any(HOP_TAG::matches)) {
                    out += "$label lists a chain hop"
                }
                obj.string("detour")?.let { if (it !in tags) out += "$label detour references an unknown outbound" }
            }
        }
        val byTag = listOf("outbounds", "endpoints").flatMap { root.array(it).filterIsInstance<JsonObject>() }
            .filter { it.string("tag") != null }.associateBy { it.string("tag")!! }
        for ((start, obj) in byTag) {
            val seen = mutableSetOf(start)
            var next = obj.string("detour")
            while (next != null && seen.add(next)) next = byTag[next]?.string("detour")
            if (next != null) out += "detour chain from ${label(obj)} loops"
        }
        return tags
    }

    /** Returns every DNS server tag. */
    private fun checkDns(root: JsonObject, outboundTags: Set<String>, out: MutableList<String>): Set<String> {
        val dns = root.obj("dns")
        val servers = dns?.array("servers").orEmpty()
        val tags = mutableSetOf<String>()
        servers.forEachIndexed { i, element ->
            val server = element as? JsonObject
            if (server == null || server.string("type").isNullOrEmpty() || server.containsKey("address")) {
                out += "dns.servers[$i] uses the legacy format"
                return@forEachIndexed
            }
            server.string("tag")?.let(tags::add)
            server.string("detour")?.let { if (it !in outboundTags) out += "dns.servers[$i] detour references an unknown outbound" }
        }
        dns?.array("rules")?.forEachIndexed { i, rule ->
            val server = (rule as? JsonObject)?.string("server")
            if (server != null && server !in tags) out += "dns.rules[$i] references an unknown server"
        }
        dns?.string("final")?.let { if (it !in tags) out += "dns.final references an unknown server" }
        val resolverRefs = buildList {
            for (section in listOf("outbounds", "endpoints")) {
                root.array(section).filterIsInstance<JsonObject>().forEach { add(resolverServer(it["domain_resolver"])) }
            }
            add(resolverServer(root.obj("route")?.get("default_domain_resolver")))
        }.filterNotNull()
        if (resolverRefs.any { it !in tags }) out += "domain_resolver references an unknown DNS server"
        return tags
    }

    private fun checkRoute(root: JsonObject, kind: ConfigKind, outboundTags: Set<String>, dnsTags: Set<String>, out: MutableList<String>) {
        val route = root.obj("route")
        if (route?.bool("auto_detect_interface") != true) out += "route.auto_detect_interface not enabled"
        if (resolverServer(route?.get("default_domain_resolver")) == null) out += "route.default_domain_resolver missing"
        route?.array("rule_set")?.forEachIndexed { i, element ->
            val set = element as? JsonObject
            if (set == null || set.string("type") !in setOf("local", "inline") || set.containsKey("url")) {
                out += "route.rule_set[$i] is not local"
            }
        }
        val rules = route?.array("rules").orEmpty().map { it as? JsonObject }
        rules.forEachIndexed { i, rule ->
            val target = rule?.string("outbound")
            if (target != null && target !in outboundTags) out += "route.rules[$i] references an unknown outbound"
        }
        route?.string("final")?.let { if (it !in outboundTags) out += "route.final references an unknown outbound" }
        if (kind == ConfigKind.PROBE) return
        if (ConfigGenerator.DNS_LOCAL !in dnsTags) out += "dns has no local server"
        if (isPlaceholder(root)) return
        val selfPackage = (rules.getOrNull(2)?.get("package_name") as? JsonArray)?.singleOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf(RoutingBuilder.PACKAGE_NAME::matches)
        val head = selfPackage?.let { pkg -> RoutingBuilder.headRules(pkg).map { configJson.encodeToJsonElement(RouteRuleDto.serializer(), it) } }
        if (head == null || rules.size < head.size || head.indices.any { rules[it] != head[it] }) {
            out += "route.rules must start with sniff, hijack-dns, own package to proxy"
        }
        if (route?.string("final") != ConfigGenerator.TAG_PROXY) out += "route.final must be proxy"
    }

    /** The no-node variant of MAIN: nothing routed, every packet ends at a block outbound. */
    private fun isPlaceholder(root: JsonObject): Boolean {
        val route = root.obj("route") ?: return false
        val outbounds = root.array("outbounds")
        return route.string("final") == ConfigGenerator.TAG_BLOCK &&
            route.array("rules").isEmpty() &&
            root.array("endpoints").isEmpty() &&
            outbounds.isNotEmpty() &&
            outbounds.all { (it as? JsonObject)?.string("type") == "block" }
    }

    private fun resolverServer(element: JsonElement?): String? = when (element) {
        is JsonObject -> element.string("server")
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }

    private fun label(obj: JsonObject): String {
        val tag = obj.string("tag")
        if (tag != null && (tag in FIXED_TAGS || SAFE_TAG.matches(tag))) return tag
        return obj.string("type")?.takeIf { SAFE_TYPE.matches(it) } ?: "?"
    }

    private fun stringList(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        else -> emptyList()
    }
}
