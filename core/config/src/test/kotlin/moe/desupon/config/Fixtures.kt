package moe.desupon.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.desupon.model.Secret
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeKey
import moe.desupon.model.node.Protocol
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind

/** Synthetic nodes as the parser hands them over, pitfalls included; every credential here is fake. */
object Fixtures {
    const val UUID = "00000000-0000-4000-8000-00000000c0de"
    const val WG_PRIVATE = "ERERERERERERERERERERERERERERERERERERERERERE="
    const val WG_PUBLIC = "IiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiI="
    const val WG_PSK = "MzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzM="
    const val AWG_HPK = "REREREREREREREREREREREREREREREREREREREREREQ="
    const val REALITY_PUBLIC = "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVU"
    const val VLESS_ENC_KEY = "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY"
    const val SS_PASSWORD = "WlpaWlpaWlpaWlpaWlpaWg=="
    const val PASSWORD = "synthetic-password-7f3a"
    const val OBFS_PASSWORD = "synthetic-obfs-9b2c"
    const val JUICITY_PIN = "c3ludGhldGljLWp1aWNpdHktY2hhaW4taGFzaC0wMDE="

    val SECRETS = listOf(UUID, WG_PRIVATE, WG_PUBLIC, WG_PSK, AWG_HPK, REALITY_PUBLIC, VLESS_ENC_KEY, SS_PASSWORD, PASSWORD, OBFS_PASSWORD)

    fun node(
        protocol: Protocol,
        host: String,
        port: Int,
        json: String,
        endpoint: Boolean = false,
        udpOnly: Boolean = false,
        capability: Capability = Capability.SUPPORTED,
    ): Node {
        val key = NodeKey(protocol, host, port, credential = Secret(protocol.name + host))
        return Node(
            id = key.id,
            subs = emptySet(),
            protocol = protocol,
            displayName = "$protocol $host",
            country = "NL",
            capability = capability,
            outboundJson = Secret(json.trimIndent()),
            isEndpoint = endpoint,
            udpOnly = udpOnly,
        )
    }

    /** XHTTP as subscriptions give it: Host inside headers, no x_padding_bytes. */
    val vlessXhttp = node(
        Protocol.VLESS, "xh.example.com", 443,
        """
        {"type":"vless","server":"xh.example.com","server_port":443,"uuid":"$UUID",
         "tls":{"enabled":true,"server_name":"xh.example.com","alpn":["h2","http/1.1","h3"],
                "utls":{"enabled":true,"fingerprint":"random"}},
         "transport":{"type":"xhttp","mode":"stream-one","path":"/xh",
                      "headers":{"User-Agent":"Mozilla/5.0 (synthetic)","Host":"cdn.example.com"}}}
        """,
    )

    /** First ALPN h3 makes XHTTP ride QUIC, where uTLS fails at dial time. */
    val vlessXhttpH3 = node(
        Protocol.VLESS, "xh3.example.com", 443,
        """
        {"type":"vless","server":"xh3.example.com","server_port":443,"uuid":"$UUID",
         "tls":{"enabled":true,"server_name":"xh3.example.com","alpn":["h3","h2"],
                "utls":{"enabled":true,"fingerprint":"random"}},
         "transport":{"type":"xhttp","mode":"stream-one","host":"xh3.example.com","path":"/q","x_padding_bytes":"200-800"}}
        """,
        udpOnly = true,
    )

    val vlessReality = node(
        Protocol.VLESS, "203.0.113.20", 443,
        """
        {"type":"vless","tag":"from-parser","server":"203.0.113.20","server_port":443,"uuid":"$UUID",
         "flow":"xtls-rprx-vision-udp443",
         "tls":{"enabled":true,"server_name":"www.example.org",
                "utls":{"enabled":true,"fingerprint":"firefox"},
                "reality":{"enabled":true,"public_key":"$REALITY_PUBLIC","short_id":"0123abcd"}}}
        """,
    )

    val vlessEncryption = node(
        Protocol.VLESS, "203.0.113.21", 443,
        """
        {"type":"vless","server":"203.0.113.21","server_port":443,"uuid":"$UUID","flow":"xtls-rprx-vision",
         "encryption":"mlkem768x25519plus.native.0rtt.$VLESS_ENC_KEY"}
        """,
    )

    val vmessWs = node(
        Protocol.VMESS, "vm.example.com", 443,
        """
        {"type":"vmess","server":"vm.example.com","server_port":443,"uuid":"$UUID","security":"auto",
         "tls":{"enabled":true,"server_name":"vm.example.com"},
         "transport":{"type":"ws","path":"/ws","headers":{"Host":"vm.example.com"}}}
        """,
    )

    val trojanGrpc = node(
        Protocol.TROJAN, "tj.example.com", 443,
        """
        {"type":"trojan","server":"tj.example.com","server_port":443,"password":"$PASSWORD",
         "tls":{"enabled":true,"server_name":"tj.example.com","utls":{"enabled":true,"fingerprint":"chrome"}},
         "transport":{"type":"grpc","service_name":"svc"}}
        """,
    )

    val shadowsocks = node(
        Protocol.SHADOWSOCKS, "198.51.100.7", 8388,
        """
        {"type":"shadowsocks","server":"198.51.100.7","server_port":8388,"method":"2022-blake3-aes-128-gcm","password":"$SS_PASSWORD",
         "domain_strategy":"prefer_ipv6"}
        """,
    )

    val hysteria = node(
        Protocol.HYSTERIA, "hy1.example.com", 8443,
        """
        {"type":"hysteria","server":"hy1.example.com","server_port":8443,"up_mbps":50,"down_mbps":200,
         "auth_str":"$PASSWORD","obfs":"$OBFS_PASSWORD",
         "tls":{"enabled":true,"server_name":"hy1.example.com","alpn":["hysteria"],"utls":{"enabled":true,"fingerprint":"chrome"}}}
        """,
        udpOnly = true,
    )

    val hysteria2 = node(
        Protocol.HYSTERIA2, "hy2.example.com", 8443,
        """
        {"type":"hysteria2","server":"hy2.example.com","server_port":8443,
         "server_ports":["20000:30000"],"hop_interval":"30s",
         "password":"$PASSWORD","obfs":{"type":"salamander","password":"$OBFS_PASSWORD"},
         "tls":{"enabled":true,"server_name":"hy2.example.com","alpn":["h3"],"utls":{"enabled":true,"fingerprint":"chrome"}}}
        """,
        udpOnly = true,
    )

    val tuic = node(
        Protocol.TUIC, "tuic.example.com", 443,
        """
        {"type":"tuic","server":"tuic.example.com","server_port":443,"uuid":"$UUID","password":"$PASSWORD",
         "congestion_control":"bbr","tls":{"enabled":true,"server_name":"tuic.example.com","alpn":["h3"]}}
        """,
        udpOnly = true,
    )

    val juicity = node(
        Protocol.JUICITY, "juicity.example.com", 443,
        """
        {"type":"juicity","server":"juicity.example.com","server_port":443,"uuid":"$UUID","password":"$PASSWORD",
         "congestion_control":"bbr","pinned_certchain_sha256":"$JUICITY_PIN",
         "tls":{"enabled":true,"server_name":"juicity.example.com","utls":{"enabled":true,"fingerprint":"chrome"}}}
        """,
        udpOnly = true,
    )

    val anytls = node(
        Protocol.ANYTLS, "any.example.com", 443,
        """
        {"type":"anytls","server":"any.example.com","server_port":443,"password":"$PASSWORD",
         "tls":{"enabled":true,"server_name":"any.example.com"}}
        """,
    )

    val socks = node(
        Protocol.SOCKS, "198.51.100.8", 1080,
        """{"type":"socks","server":"198.51.100.8","server_port":1080,"version":"5","username":"u","password":"$PASSWORD"}""",
    )

    val http = node(
        Protocol.HTTP, "198.51.100.9", 3128,
        """{"type":"http","server":"198.51.100.9","server_port":3128,"username":"u","password":"$PASSWORD"}""",
    )

    /** Plain WireGuard with fields the fork rejects (reserved) or that drain battery (keepalive). */
    val wireguard = node(
        Protocol.WIREGUARD, "198.51.100.10", 51820,
        """
        {"type":"wireguard","mtu":1408,"address":["10.66.0.2/32"],"private_key":"$WG_PRIVATE","listen_port":51820,
         "peers":[{"address":"198.51.100.10","port":51820,"public_key":"$WG_PUBLIC","pre_shared_key":"$WG_PSK",
                   "allowed_ips":["0.0.0.0/0","::/0"],"persistent_keepalive_interval":25,"reserved":[1,2,3]}]}
        """,
        endpoint = true,
        udpOnly = true,
    )

    val amneziawg = node(
        Protocol.AMNEZIAWG, "198.51.100.11", 10481,
        """
        {"type":"wireguard","mtu":1399,"address":["10.8.0.2/32"],"private_key":"$WG_PRIVATE",
         "peers":[{"address":"198.51.100.11","port":10481,"public_key":"$WG_PUBLIC","pre_shared_key":"$WG_PSK",
                   "allowed_ips":["0.0.0.0/0","::/0"],"persistent_keepalive_interval":25}],
         "amnezia":{"jc":3,"jmin":87,"jmax":159,"s1":38,"s2":22,"s3":23,"s4":21,
           "h1":"100000000-100999999","h2":"200000000-200999999","h3":"300000000-300999999","h4":"400000000-400999999",
           "i1":"<r 256>","header_protection_key":"$AWG_HPK","content_padding_addition":"0-16",
           "rekey_after_time":"100-140","rekey_timeout":"4-6","reject_after_time":"170-190",
           "keepalive_timeout":"9-11","max_handshake_attempts":"18-22","random_trailers":true,"disable_cookies":true}}
        """,
        endpoint = true,
        udpOnly = true,
    )

    val all = listOf(
        vlessXhttp, vlessXhttpH3, vlessReality, vlessEncryption, vmessWs, trojanGrpc, shadowsocks,
        hysteria, hysteria2, tuic, juicity, anytls, socks, http, wireguard, amneziawg,
    )

    val awgSmallPadding = node(
        Protocol.AMNEZIAWG, "198.51.100.12", 10482,
        amneziawg.outboundJson.raw().replace("\"s4\":21", "\"s4\":10").replace("198.51.100.11", "198.51.100.12"),
        endpoint = true,
        udpOnly = true,
    )

    val freeturn = node(Protocol.FREETURN, "198.51.100.13", 56000, """{"type":"freeturn","peer":"198.51.100.13:56000"}""")

    val unsupported = node(
        Protocol.VLESS, "198.51.100.14", 443,
        """{"type":"vless","server":"198.51.100.14","server_port":443,"uuid":"$UUID"}""",
        capability = Capability.UNSUPPORTED,
    )

    val malformed = node(Protocol.TROJAN, "198.51.100.15", 443, """{"type":"trojan","password":"$PASSWORD",""")

    fun rule(order: Int, kind: SplitRuleKind, value: String, action: SplitAction, enabled: Boolean = true) =
        SplitRule(id = order.toLong(), order = order, enabled = enabled, kind = kind, value = value, action = action)

    /** Deliberately out of order; [SplitRule.order] decides. */
    val rules = listOf(
        rule(30, SplitRuleKind.DOMAIN_KEYWORD, "yandex", SplitAction.DIRECT),
        rule(10, SplitRuleKind.DOMAIN, "ads.example.com", SplitAction.BLOCK),
        rule(20, SplitRuleKind.DOMAIN_SUFFIX, "RU", SplitAction.DIRECT),
        rule(15, SplitRuleKind.DOMAIN, "news.example.ru", SplitAction.PROXY),
        rule(40, SplitRuleKind.IP_CIDR, "77.88.0.0/18", SplitAction.DIRECT),
        rule(41, SplitRuleKind.IP_CIDR, "2a02:6b8::/32", SplitAction.DIRECT),
        rule(50, SplitRuleKind.RULE_SET, "geosite-ru", SplitAction.DIRECT),
        rule(51, SplitRuleKind.RULE_SET, "geoip-ru", SplitAction.DIRECT),
        rule(60, SplitRuleKind.DOMAIN, "off.example.com", SplitAction.BLOCK, enabled = false),
    )

    val ruleSets = mapOf(
        "geosite-ru" to LocalRuleSet("/data/user/0/moe.desupon.app/files/rules/geosite-ru.srs"),
        "geoip-ru" to LocalRuleSet("/data/user/0/moe.desupon.app/files/rules/geoip-ru.srs", domainBased = false),
    )

    fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }

    fun JsonObject.byTag(tag: String): JsonObject =
        (objects("outbounds") + objects("endpoints")).single { it["tag"]?.jsonPrimitive?.content == tag }

    fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }
}
