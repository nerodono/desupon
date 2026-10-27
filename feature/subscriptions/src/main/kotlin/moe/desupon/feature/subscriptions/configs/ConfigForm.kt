package moe.desupon.feature.subscriptions.configs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.node.Capability
import moe.desupon.parser.ConfigParser
import moe.desupon.parser.ConfigRead
import java.util.Base64

/** Protocols the structured form edits; any other config stays in text mode. */
internal enum class FormProtocol(val type: String) {
    VLESS("vless"),
    VMESS("vmess"),
    TROJAN("trojan"),
    SHADOWSOCKS("shadowsocks"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
}

internal enum class FieldKind { TEXT, NUMBER, CHOICE, SWITCH }

/** One form input; its value is a string, `"true"` or empty for switches. */
internal enum class Field(val kind: FieldKind) {
    SERVER(FieldKind.TEXT),
    PORT(FieldKind.NUMBER),
    UUID(FieldKind.TEXT),
    PASSWORD(FieldKind.TEXT),
    FLOW(FieldKind.CHOICE),
    VMESS_SECURITY(FieldKind.CHOICE),
    ALTER_ID(FieldKind.NUMBER),
    METHOD(FieldKind.CHOICE),
    PLUGIN(FieldKind.CHOICE),
    PLUGIN_OPTS(FieldKind.TEXT),
    SECURITY(FieldKind.CHOICE),
    SNI(FieldKind.TEXT),
    FINGERPRINT(FieldKind.CHOICE),
    ALPN(FieldKind.TEXT),
    INSECURE(FieldKind.SWITCH),
    REALITY_KEY(FieldKind.TEXT),
    REALITY_SID(FieldKind.TEXT),
    TRANSPORT(FieldKind.CHOICE),
    PATH(FieldKind.TEXT),
    HOST(FieldKind.TEXT),
    SERVICE_NAME(FieldKind.TEXT),
    XHTTP_MODE(FieldKind.CHOICE),
    HOP_PORTS(FieldKind.TEXT),
    HOP_INTERVAL(FieldKind.TEXT),
    UP_MBPS(FieldKind.NUMBER),
    DOWN_MBPS(FieldKind.NUMBER),
    OBFS(FieldKind.CHOICE),
    OBFS_PASSWORD(FieldKind.TEXT),
    GECKO_MIN(FieldKind.NUMBER),
    GECKO_MAX(FieldKind.NUMBER),
    CONGESTION(FieldKind.CHOICE),
    UDP_RELAY(FieldKind.CHOICE),
    ZERO_RTT(FieldKind.SWITCH),
}

/**
 * Structured editing of one outbound in sing-box terms, the shape the parser produces.
 * [base] is the outbound as opened: keys the form does not show are written back unchanged.
 */
internal data class ConfigForm(
    val protocol: FormProtocol,
    val name: String,
    val base: JsonObject,
    val values: Map<Field, String>,
    val errors: Map<Field, UiText> = emptyMap(),
) {
    operator fun get(field: Field): String = values[field].orEmpty()

    fun name(value: String) = copy(name = value)

    fun set(field: Field, value: String) = copy(values = values + (field to value), errors = errors - field)

    fun switch(field: Field, on: Boolean) = set(field, if (on) TRUE else "")

    fun isOn(field: Field): Boolean = this[field] == TRUE

    /** The inputs shown for the current choices, in order. */
    fun fields(): List<Field> = buildList {
        add(Field.SERVER)
        add(Field.PORT)
        when (protocol) {
            FormProtocol.VLESS -> {
                add(Field.UUID)
                add(Field.FLOW)
                addAll(securityFields())
                addAll(transportFields())
            }
            FormProtocol.VMESS -> {
                add(Field.UUID)
                add(Field.VMESS_SECURITY)
                add(Field.ALTER_ID)
                addAll(securityFields())
                addAll(transportFields())
            }
            FormProtocol.TROJAN -> {
                add(Field.PASSWORD)
                addAll(securityFields())
                addAll(transportFields())
            }
            FormProtocol.SHADOWSOCKS -> {
                add(Field.METHOD)
                add(Field.PASSWORD)
                add(Field.PLUGIN)
                if (this@ConfigForm[Field.PLUGIN].isNotEmpty()) add(Field.PLUGIN_OPTS)
            }
            FormProtocol.HYSTERIA2 -> {
                add(Field.PASSWORD)
                add(Field.HOP_PORTS)
                if (this@ConfigForm[Field.HOP_PORTS].isNotBlank()) add(Field.HOP_INTERVAL)
                add(Field.UP_MBPS)
                add(Field.DOWN_MBPS)
                add(Field.OBFS)
                if (this@ConfigForm[Field.OBFS].isNotEmpty()) add(Field.OBFS_PASSWORD)
                if (this@ConfigForm[Field.OBFS] == GECKO) {
                    add(Field.GECKO_MIN)
                    add(Field.GECKO_MAX)
                }
                addAll(listOf(Field.SNI, Field.ALPN, Field.INSECURE))
            }
            FormProtocol.TUIC -> {
                add(Field.UUID)
                add(Field.PASSWORD)
                add(Field.CONGESTION)
                add(Field.UDP_RELAY)
                add(Field.ZERO_RTT)
                addAll(listOf(Field.SNI, Field.ALPN, Field.INSECURE))
            }
        }
    }

    private fun securityFields(): List<Field> = when (this[Field.SECURITY]) {
        TLS -> listOf(Field.SECURITY, Field.SNI, Field.FINGERPRINT, Field.ALPN, Field.INSECURE)
        // The parser pins REALITY to the chrome fingerprint, so there is nothing to choose.
        REALITY -> listOf(Field.SECURITY, Field.SNI, Field.REALITY_KEY, Field.REALITY_SID)
        else -> listOf(Field.SECURITY)
    }

    private fun transportFields(): List<Field> = when (this[Field.TRANSPORT]) {
        "ws", "httpupgrade", "http" -> listOf(Field.TRANSPORT, Field.PATH, Field.HOST)
        "grpc" -> listOf(Field.TRANSPORT, Field.SERVICE_NAME)
        "xhttp" -> listOf(Field.TRANSPORT, Field.PATH, Field.HOST, Field.XHTTP_MODE)
        else -> listOf(Field.TRANSPORT)
    }

    /** Values a choice offers; a value read from the config that is not a preset stays selectable. */
    fun options(field: Field): List<String> {
        val presets = when (field) {
            Field.FLOW -> listOf("", VISION)
            Field.VMESS_SECURITY -> listOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-cfb")
            Field.METHOD -> SS_METHODS
            Field.PLUGIN -> listOf("", "obfs-local", "v2ray-plugin")
            Field.SECURITY -> listOf(NONE, TLS, REALITY)
            Field.FINGERPRINT -> listOf("", "chrome", "firefox", "safari", "edge", "ios", "android", "random", "randomized")
            Field.TRANSPORT -> TRANSPORTS
            Field.XHTTP_MODE -> listOf("auto", "packet-up", "stream-up", "stream-one")
            Field.OBFS -> listOf("", "salamander", GECKO)
            Field.CONGESTION -> listOf("", "cubic", "new_reno", "bbr")
            Field.UDP_RELAY -> listOf("", "native", "quic")
            else -> emptyList()
        }
        val current = this[field]
        return if (current in presets) presets else presets + current
    }

    /** Checks every shown field; [errors] lists what to fix, empty when the form can be saved. */
    fun validate(): ConfigForm {
        val out = LinkedHashMap<Field, UiText>()
        val shown = fields()
        for (f in shown) FieldRules.check(this, f)?.let { out[f] = it }
        if (Field.FLOW in shown && this[Field.FLOW] == VISION && (this[Field.SECURITY] == NONE || this[Field.TRANSPORT] != TCP)) {
            out.putIfAbsent(Field.FLOW, UiText.Res(R.string.subs_form_error_vision))
        }
        return copy(errors = out)
    }

    fun toText(): String = ConfigParser.outboundText(toOutbound(), name)

    /** [base] with every shown field written back; fields of other choices are left as they were. */
    fun toOutbound(): JsonObject {
        val o = LinkedHashMap<String, JsonElement>(base)
        o["type"] = JsonPrimitive(protocol.type)
        o.text("server", this[Field.SERVER])
        o.number("server_port", this[Field.PORT])
        when (protocol) {
            FormProtocol.VLESS -> {
                o.text("uuid", this[Field.UUID])
                o.text("flow", this[Field.FLOW])
                writeSecurity(o)
                writeTransport(o)
            }
            FormProtocol.VMESS -> {
                o.text("uuid", this[Field.UUID])
                o.text("security", this[Field.VMESS_SECURITY].ifEmpty { "auto" })
                o.number("alter_id", this[Field.ALTER_ID].takeUnless { it.trim() == "0" }.orEmpty())
                writeSecurity(o)
                writeTransport(o)
            }
            FormProtocol.TROJAN -> {
                o.raw("password", this[Field.PASSWORD])
                writeSecurity(o)
                writeTransport(o)
            }
            FormProtocol.SHADOWSOCKS -> {
                o.text("method", this[Field.METHOD])
                o.raw("password", this[Field.PASSWORD])
                o.text("plugin", this[Field.PLUGIN])
                o.text("plugin_opts", this[Field.PLUGIN_OPTS].takeIf { this[Field.PLUGIN].isNotEmpty() }.orEmpty())
            }
            FormProtocol.HYSTERIA2 -> {
                o.raw("password", this[Field.PASSWORD])
                val hops = Ports.ranges(this[Field.HOP_PORTS]).orEmpty()
                o.list("server_ports", hops)
                if (hops.isNotEmpty()) o.text("hop_interval", this[Field.HOP_INTERVAL])
                o.number("up_mbps", this[Field.UP_MBPS])
                o.number("down_mbps", this[Field.DOWN_MBPS])
                writeObfs(o)
                writeQuicTls(o)
            }
            FormProtocol.TUIC -> {
                o.text("uuid", this[Field.UUID])
                o.raw("password", this[Field.PASSWORD])
                o.text("congestion_control", this[Field.CONGESTION])
                o.text("udp_relay_mode", this[Field.UDP_RELAY])
                o.flag("zero_rtt_handshake", isOn(Field.ZERO_RTT))
                writeQuicTls(o)
            }
        }
        return JsonObject(o)
    }

    private fun writeSecurity(o: MutableMap<String, JsonElement>) {
        val old = base.obj("tls")
        when (this[Field.SECURITY]) {
            TLS -> {
                val t = LinkedHashMap<String, JsonElement>(old ?: emptyMap())
                t.remove("reality")
                t["enabled"] = JsonPrimitive(true)
                t.text("server_name", this[Field.SNI])
                t.flag("insecure", isOn(Field.INSECURE))
                t.list("alpn", splitList(this[Field.ALPN]))
                t.utls(old, this[Field.FINGERPRINT])
                o["tls"] = JsonObject(t)
            }
            REALITY -> {
                val t = LinkedHashMap<String, JsonElement>(old ?: emptyMap())
                t.remove("insecure")
                t["enabled"] = JsonPrimitive(true)
                t.text("server_name", this[Field.SNI])
                t.utls(old, "chrome")
                val r = LinkedHashMap<String, JsonElement>(old?.obj("reality") ?: emptyMap())
                r["enabled"] = JsonPrimitive(true)
                r.text("public_key", this[Field.REALITY_KEY])
                r.text("short_id", this[Field.REALITY_SID])
                t["reality"] = JsonObject(r)
                o["tls"] = JsonObject(t)
            }
            else -> o.remove("tls")
        }
    }

    private fun writeTransport(o: MutableMap<String, JsonElement>) {
        val type = this[Field.TRANSPORT]
        if (type == TCP || type.isEmpty()) {
            o.remove("transport")
            return
        }
        val old = base.obj("transport")?.takeIf { it.text("type") == type }
        val t = LinkedHashMap<String, JsonElement>(old ?: emptyMap())
        t["type"] = JsonPrimitive(type)
        when (type) {
            "ws" -> {
                t.text("path", this[Field.PATH])
                val headers = LinkedHashMap<String, JsonElement>(old?.obj("headers") ?: emptyMap())
                headers.keys.filter { it.equals("host", ignoreCase = true) }.forEach(headers::remove)
                headers.text("Host", this[Field.HOST])
                if (headers.isEmpty()) t.remove("headers") else t["headers"] = JsonObject(headers)
            }
            "grpc" -> t.text("service_name", this[Field.SERVICE_NAME])
            "httpupgrade" -> {
                t.text("path", this[Field.PATH])
                t.text("host", this[Field.HOST])
            }
            "http" -> {
                t.text("path", this[Field.PATH])
                t.list("host", splitList(this[Field.HOST]))
            }
            "xhttp" -> {
                t.text("path", this[Field.PATH])
                t.text("host", this[Field.HOST])
                t.text("mode", this[Field.XHTTP_MODE].ifEmpty { "auto" })
            }
        }
        o["transport"] = JsonObject(t)
    }

    private fun writeObfs(o: MutableMap<String, JsonElement>) {
        val type = this[Field.OBFS]
        if (type.isEmpty()) {
            o.remove("obfs")
            return
        }
        val old = base.obj("obfs")?.takeIf { it.text("type") == type }
        val b = LinkedHashMap<String, JsonElement>(old ?: emptyMap())
        b["type"] = JsonPrimitive(type)
        b.raw("password", this[Field.OBFS_PASSWORD])
        b.number("min_packet_size", this[Field.GECKO_MIN].takeIf { type == GECKO }.orEmpty())
        b.number("max_packet_size", this[Field.GECKO_MAX].takeIf { type == GECKO }.orEmpty())
        o["obfs"] = JsonObject(b)
    }

    private fun writeQuicTls(o: MutableMap<String, JsonElement>) {
        val t = LinkedHashMap<String, JsonElement>(base.obj("tls") ?: emptyMap())
        t["enabled"] = JsonPrimitive(true)
        t.text("server_name", this[Field.SNI])
        t.flag("insecure", isOn(Field.INSECURE))
        t.list("alpn", splitList(this[Field.ALPN]))
        o["tls"] = JsonObject(t)
    }

    companion object {
        const val TRUE = "true"
        const val NONE = "none"
        const val TLS = "tls"
        const val REALITY = "reality"
        const val TCP = "tcp"
        const val GECKO = "gecko"
        const val VISION = "xtls-rprx-vision"
        val TRANSPORTS = listOf(TCP, "ws", "grpc", "httpupgrade", "http", "xhttp")
        val SS_METHODS = listOf(
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
            "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "none",
        )

        /** Null when [read] is not a single runnable node of a [FormProtocol] whose settings the form can all show. */
        fun from(read: ConfigRead): ConfigForm? {
            val node = read.nodes.singleOrNull()?.takeIf { it.capability == Capability.SUPPORTED } ?: return null
            val o = runCatching { Json.parseToJsonElement(node.outboundJson.raw()).jsonObject }.getOrNull() ?: return null
            if ("chain" in o || "detour" in o) return null
            val protocol = FormProtocol.entries.firstOrNull { it.type == o.text("type") } ?: return null
            val values = read(protocol, o) ?: return null
            val name = node.displayName.takeUnless { it == Endpoint.of(node.outboundJson.raw()) }.orEmpty()
            return ConfigForm(protocol, name, o, values)
        }

        fun blank(protocol: FormProtocol): ConfigForm {
            val values = buildMap {
                put(Field.PORT, "443")
                when (protocol) {
                    FormProtocol.VLESS -> {
                        put(Field.SECURITY, REALITY)
                        put(Field.FINGERPRINT, "chrome")
                        put(Field.FLOW, VISION)
                        put(Field.TRANSPORT, TCP)
                    }
                    FormProtocol.VMESS -> {
                        put(Field.VMESS_SECURITY, "auto")
                        put(Field.SECURITY, NONE)
                        put(Field.TRANSPORT, TCP)
                    }
                    FormProtocol.TROJAN -> {
                        put(Field.SECURITY, TLS)
                        put(Field.TRANSPORT, TCP)
                    }
                    FormProtocol.SHADOWSOCKS -> {
                        put(Field.PORT, "8388")
                        put(Field.METHOD, "2022-blake3-aes-128-gcm")
                    }
                    FormProtocol.HYSTERIA2, FormProtocol.TUIC -> Unit
                }
            }
            return ConfigForm(protocol, "", JsonObject(emptyMap()), values)
        }

        private fun read(protocol: FormProtocol, o: JsonObject): Map<Field, String>? {
            val v = LinkedHashMap<Field, String>()
            v[Field.SERVER] = o.text("server").orEmpty()
            v[Field.PORT] = o.text("server_port").orEmpty()
            val tls = o.obj("tls")?.takeIf { it.text("enabled") == "true" }
            when (protocol) {
                FormProtocol.VLESS, FormProtocol.VMESS, FormProtocol.TROJAN -> {
                    if (protocol == FormProtocol.TROJAN) v[Field.PASSWORD] = o.raw("password") else v[Field.UUID] = o.text("uuid").orEmpty()
                    if (protocol == FormProtocol.VLESS) v[Field.FLOW] = o.text("flow").orEmpty()
                    if (protocol == FormProtocol.VMESS) {
                        v[Field.VMESS_SECURITY] = o.text("security") ?: "auto"
                        v[Field.ALTER_ID] = o.text("alter_id").orEmpty()
                    }
                    readSecurity(tls, v)
                    if (!readTransport(o.obj("transport"), v)) return null
                }
                FormProtocol.SHADOWSOCKS -> {
                    v[Field.METHOD] = o.text("method").orEmpty()
                    v[Field.PASSWORD] = o.raw("password")
                    v[Field.PLUGIN] = o.text("plugin").orEmpty()
                    v[Field.PLUGIN_OPTS] = o.text("plugin_opts").orEmpty()
                }
                FormProtocol.HYSTERIA2 -> {
                    v[Field.PASSWORD] = o.raw("password")
                    v[Field.HOP_PORTS] = Ports.show(o.strings("server_ports"))
                    v[Field.HOP_INTERVAL] = o.text("hop_interval").orEmpty()
                    v[Field.UP_MBPS] = o.text("up_mbps").orEmpty()
                    v[Field.DOWN_MBPS] = o.text("down_mbps").orEmpty()
                    val obfs = o.obj("obfs")
                    v[Field.OBFS] = obfs?.text("type").orEmpty()
                    if (v[Field.OBFS] !in listOf("", "salamander", GECKO)) return null
                    v[Field.OBFS_PASSWORD] = obfs?.raw("password").orEmpty()
                    v[Field.GECKO_MIN] = obfs?.text("min_packet_size").orEmpty()
                    v[Field.GECKO_MAX] = obfs?.text("max_packet_size").orEmpty()
                    readQuicTls(tls, v)
                }
                FormProtocol.TUIC -> {
                    v[Field.UUID] = o.text("uuid").orEmpty()
                    v[Field.PASSWORD] = o.raw("password")
                    v[Field.CONGESTION] = o.text("congestion_control").orEmpty()
                    v[Field.UDP_RELAY] = o.text("udp_relay_mode").orEmpty()
                    v[Field.ZERO_RTT] = if (o.text("zero_rtt_handshake") == "true") TRUE else ""
                    readQuicTls(tls, v)
                }
            }
            return v
        }

        private fun readSecurity(tls: JsonObject?, v: MutableMap<Field, String>) {
            val reality = tls?.obj("reality")?.takeIf { it.text("enabled") == "true" }
            v[Field.SECURITY] = when {
                tls == null -> NONE
                reality != null -> REALITY
                else -> TLS
            }
            v[Field.SNI] = tls?.text("server_name").orEmpty()
            v[Field.INSECURE] = if (tls?.text("insecure") == "true") TRUE else ""
            v[Field.ALPN] = tls?.strings("alpn").orEmpty().joinToString(", ")
            v[Field.FINGERPRINT] = tls?.obj("utls")?.takeIf { it.text("enabled") == "true" }?.text("fingerprint").orEmpty()
            v[Field.REALITY_KEY] = reality?.text("public_key").orEmpty()
            v[Field.REALITY_SID] = reality?.text("short_id").orEmpty()
        }

        private fun readQuicTls(tls: JsonObject?, v: MutableMap<Field, String>) {
            v[Field.SNI] = tls?.text("server_name").orEmpty()
            v[Field.INSECURE] = if (tls?.text("insecure") == "true") TRUE else ""
            v[Field.ALPN] = tls?.strings("alpn").orEmpty().joinToString(", ")
        }

        /** False for a transport the form cannot show, so the config stays in text mode. */
        private fun readTransport(t: JsonObject?, v: MutableMap<Field, String>): Boolean {
            val type = t?.text("type") ?: TCP
            if (type !in TRANSPORTS) return false
            v[Field.TRANSPORT] = type
            v[Field.PATH] = t?.text("path").orEmpty()
            v[Field.SERVICE_NAME] = t?.text("service_name").orEmpty()
            v[Field.XHTTP_MODE] = t?.text("mode").orEmpty()
            v[Field.HOST] = when (type) {
                "ws" -> t?.obj("headers")?.let { h -> h.entries.firstOrNull { it.key.equals("host", ignoreCase = true) }?.value?.scalar() }.orEmpty()
                "http" -> t?.strings("host").orEmpty().joinToString(", ")
                else -> t?.text("host").orEmpty()
            }
            return true
        }
    }
}

/** Hysteria2 hop ports: the form shows `20000-30000, 40000`, sing-box takes `["20000:30000", "40000:40000"]`. */
internal object Ports {
    /** Null when [text] is not a list of ports and ranges; empty for blank text. */
    fun ranges(text: String): List<String>? {
        val parts = text.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { p ->
            val ends = p.replace(':', '-').split('-').map { it.trim().toIntOrNull()?.takeIf { n -> n in 1..65535 } ?: return null }
            when (ends.size) {
                1 -> "${ends[0]}:${ends[0]}"
                2 -> if (ends[0] <= ends[1]) "${ends[0]}:${ends[1]}" else return null
                else -> return null
            }
        }
    }

    fun show(ranges: List<String>): String = ranges.joinToString(", ") { r ->
        val ends = r.split(':')
        val a = ends[0]
        val b = ends.getOrElse(1) { a }
        if (a == b) a else "$a-$b"
    }
}

/** Field checks; messages name what is wrong, never repeat the value. */
internal object FieldRules {
    private val UUID = Regex("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}")
    private val HEX = Regex("[0-9a-fA-F]*")
    private val DURATION_PART = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h)")

    fun check(form: ConfigForm, field: Field): UiText? {
        val value = form[field].trim()
        return when (field) {
            Field.SERVER -> when {
                value.isEmpty() -> required()
                value.any { it.isWhitespace() } || "://" in value || '/' in value -> UiText.Res(R.string.subs_form_error_server)
                else -> null
            }
            Field.PORT -> if (value.toIntOrNull() !in 1..65535) UiText.Res(R.string.subs_form_error_port) else null
            Field.UUID -> when {
                value.isEmpty() -> required()
                !UUID.matches(value) -> UiText.Res(R.string.subs_form_error_uuid)
                else -> null
            }
            Field.PASSWORD -> when {
                form[field].isEmpty() -> required()
                form.protocol == FormProtocol.SHADOWSOCKS -> ss2022(form[Field.METHOD], form[field])
                else -> null
            }
            Field.METHOD -> if (value.isEmpty()) required() else null
            Field.REALITY_KEY -> when {
                value.isEmpty() -> required()
                Base64Key.decode(value)?.size != 32 -> UiText.Res(R.string.subs_form_error_reality_key)
                else -> null
            }
            Field.REALITY_SID -> if (value.length > 16 || value.length % 2 != 0 || !HEX.matches(value)) UiText.Res(R.string.subs_form_error_short_id) else null
            Field.SNI -> if (form[Field.SECURITY] == ConfigForm.REALITY && value.isEmpty()) required() else null
            Field.ALTER_ID -> if (value.isNotEmpty() && (value.toIntOrNull() ?: -1) < 0) UiText.Res(R.string.subs_form_error_alter_id) else null
            Field.UP_MBPS, Field.DOWN_MBPS -> if (value.isNotEmpty() && (value.toIntOrNull() ?: 0) <= 0) UiText.Res(R.string.subs_form_error_number) else null
            Field.GECKO_MIN, Field.GECKO_MAX -> gecko(form, field, value)
            Field.HOP_PORTS -> if (Ports.ranges(value) == null) UiText.Res(R.string.subs_form_error_ports) else null
            Field.HOP_INTERVAL -> if (value.isNotEmpty() && (seconds(value) ?: 0.0) < 5.0) UiText.Res(R.string.subs_form_error_hop_interval) else null
            Field.OBFS_PASSWORD -> if (form[field].isEmpty()) required() else null
            Field.PATH -> if (value.isNotEmpty() && !value.startsWith("/")) UiText.Res(R.string.subs_form_error_path) else null
            else -> null
        }
    }

    private fun required() = UiText.Res(R.string.subs_form_error_required)

    private fun gecko(form: ConfigForm, field: Field, value: String): UiText? {
        if (value.isEmpty()) return null
        val n = value.toIntOrNull()
        if (n == null || n !in 1..GECKO_MAX_PACKET) return UiText.Res(R.string.subs_form_error_packet_size, listOf(GECKO_MAX_PACKET))
        val min = form[Field.GECKO_MIN].trim().toIntOrNull()
        val max = form[Field.GECKO_MAX].trim().toIntOrNull()
        return if (field == Field.GECKO_MAX && min != null && max != null && min > max) UiText.Res(R.string.subs_form_error_packet_range) else null
    }

    /** Shadowsocks 2022 takes base64 keys of the method's size, `iPSK:uPSK` for multi-user servers. */
    private fun ss2022(method: String, password: String): UiText? {
        if (!method.startsWith("2022-")) return null
        val size = if (method == "2022-blake3-aes-128-gcm") 16 else 32
        val ok = password.split(':').all { Base64Key.decode(it.trim())?.size == size }
        return if (ok) null else UiText.Res(R.string.subs_form_error_ss2022_key, listOf(size))
    }

    /** A Go duration such as `30s` or `1m30s`, in seconds; null when malformed. */
    fun seconds(text: String): Double? {
        var rest = text
        var total = 0.0
        while (rest.isNotEmpty()) {
            val m = DURATION_PART.matchAt(rest, 0) ?: return null
            val n = m.groupValues[1].toDouble()
            total += n * when (m.groupValues[2]) {
                "h" -> 3600.0
                "m" -> 60.0
                "s" -> 1.0
                "ms" -> 1e-3
                "us", "µs" -> 1e-6
                else -> 1e-9
            }
            rest = rest.substring(m.value.length)
        }
        return total
    }

    private const val GECKO_MAX_PACKET = 2048
}

/** Base64 in either alphabet, padded or not. */
internal object Base64Key {
    fun decode(text: String): ByteArray? {
        val s = text.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty() || s.length % 4 == 1) return null
        return runCatching { Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4)) }.getOrNull()
    }
}

private fun JsonElement?.scalar(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.text(key: String): String? = this[key].scalar()?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.raw(key: String): String = this[key].scalar().orEmpty()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { it.scalar()?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> splitList(v.scalar().orEmpty())
    else -> emptyList()
}

private fun splitList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun MutableMap<String, JsonElement>.text(key: String, value: String) {
    val v = value.trim()
    if (v.isEmpty()) remove(key) else this[key] = JsonPrimitive(v)
}

/** Secrets keep surrounding spaces, as the parser reads them. */
private fun MutableMap<String, JsonElement>.raw(key: String, value: String) {
    if (value.isEmpty()) remove(key) else this[key] = JsonPrimitive(value)
}

private fun MutableMap<String, JsonElement>.number(key: String, value: String) {
    val n = value.trim().toLongOrNull()
    if (n == null) remove(key) else this[key] = JsonPrimitive(n)
}

private fun MutableMap<String, JsonElement>.flag(key: String, on: Boolean) {
    if (on) this[key] = JsonPrimitive(true) else remove(key)
}

private fun MutableMap<String, JsonElement>.list(key: String, values: List<String>) {
    if (values.isEmpty()) remove(key) else this[key] = JsonArray(values.map(::JsonPrimitive))
}

private fun MutableMap<String, JsonElement>.utls(old: JsonObject?, fingerprint: String) {
    if (fingerprint.isEmpty()) {
        remove("utls")
        return
    }
    val u = LinkedHashMap<String, JsonElement>(old?.obj("utls") ?: emptyMap())
    u["enabled"] = JsonPrimitive(true)
    u["fingerprint"] = JsonPrimitive(fingerprint)
    this["utls"] = JsonObject(u)
}
