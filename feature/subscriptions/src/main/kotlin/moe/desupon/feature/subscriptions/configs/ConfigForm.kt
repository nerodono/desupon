package moe.desupon.feature.subscriptions.configs

import kotlinx.serialization.json.JsonObject
import moe.desupon.parser.ConfigParser
import moe.desupon.parser.ConfigRead

/** Protocols the structured form edits; any other config stays in text mode. */
internal enum class FormProtocol(val type: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
}

/** Structured editing of one outbound; [base] keeps every key the form does not show. */
internal data class ConfigForm(val protocol: FormProtocol, val name: String, val base: JsonObject) {
    fun toText(): String = ConfigParser.outboundText(base, name)

    companion object {
        /** Null when [read] is not a single runnable node of a [FormProtocol]. */
        fun from(read: ConfigRead): ConfigForm? = null

        fun blank(protocol: FormProtocol): ConfigForm = ConfigForm(protocol, "", JsonObject(emptyMap()))
    }
}
