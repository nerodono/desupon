package moe.desupon.model.node

enum class Protocol {
    VLESS,
    VMESS,
    TROJAN,
    SHADOWSOCKS,
    HYSTERIA,
    HYSTERIA2,
    TUIC,
    JUICITY,
    ANYTLS,
    SOCKS,
    HTTP,
    WIREGUARD,
    AMNEZIAWG,
    FREETURN,
}

enum class Capability { SUPPORTED, UNSUPPORTED }

/** Failover tiers: foreign nodes reached directly, nodes reachable under RU whitelists, FreeTurn over VK. */
enum class Tier { DIRECT, RU, TURN }
