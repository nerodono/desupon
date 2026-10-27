package moe.desupon.parser.internal

import moe.desupon.parser.BodyFormat
import moe.desupon.parser.LinkParser
import moe.desupon.parser.SkipReason
import moe.desupon.parser.SubscriptionHeaders
import moe.desupon.parser.format.ClashFormat
import moe.desupon.parser.format.SingBoxFormat
import moe.desupon.parser.format.Sip008Format
import moe.desupon.parser.format.XrayFormat
import moe.desupon.parser.link.WireGuard

internal object BodyDecoder {
    data class Decoded(
        val format: BodyFormat,
        val base64: Boolean,
        val entries: List<Entry>,
        val directives: Map<String, String>,
    )

    private val LINK_LINE = Regex("(?m)^\\s*[A-Za-z][A-Za-z0-9+.-]*://")
    private val INTERFACE = Regex("(?im)^\\s*\\[interface]\\s*$")

    /** Detection order: wg-quick INI, JSON, Clash YAML, link list, then base64 with one recursive pass. */
    fun decode(body: String, allowBase64: Boolean = true): Decoded {
        val t = stripBom(body).trim()
        if (t.isEmpty()) return Decoded(BodyFormat.EMPTY, false, emptyList(), emptyMap())

        if (WireGuard.looksLikeIni(t) && INTERFACE.containsMatchIn(t)) {
            return Decoded(BodyFormat.WG_CONF, false, listOf(entry("wireguard") { listOf(WireGuard.ini(t)) }), emptyMap())
        }
        if (t[0] == '{' || t[0] == '[') {
            parseJsonOrNull(t)?.let { root ->
                return when {
                    Sip008Format.isConfig(root) -> Decoded(BodyFormat.SIP008, false, Sip008Format.parse(root), emptyMap())
                    SingBoxFormat.isConfig(root) -> Decoded(BodyFormat.SING_BOX_JSON, false, SingBoxFormat.parse(root), emptyMap())
                    XrayFormat.isConfig(root) -> Decoded(BodyFormat.XRAY_JSON, false, XrayFormat.parse(root), emptyMap())
                    else -> Decoded(BodyFormat.UNKNOWN, false, emptyList(), emptyMap())
                }
            }
        }
        if (ClashFormat.looksLike(t)) {
            ClashFormat.parse(t)?.let { return Decoded(BodyFormat.CLASH_YAML, false, it, emptyMap()) }
        }
        if (LINK_LINE.containsMatchIn(t)) return linkList(t)
        if (allowBase64) {
            B64.decodeUtf8(t)?.let { inner ->
                val d = decode(inner, allowBase64 = false)
                if (d.format != BodyFormat.UNKNOWN && d.format != BodyFormat.EMPTY) return d.copy(base64 = true)
            }
        }
        return Decoded(BodyFormat.UNKNOWN, false, emptyList(), emptyMap())
    }

    private fun linkList(text: String): Decoded {
        val directives = LinkedHashMap<String, String>()
        val entries = ArrayList<Entry>()
        for (raw in text.lines2()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith("//")) {
                directive(line)?.let { (k, v) -> directives.putIfAbsent(k, v) }
                continue
            }
            entries += if (ShareUrl.schemeOf(line) == null) Entry.Skip(null, SkipReason.MALFORMED) else LinkParser.parse(line)
        }
        return Decoded(BodyFormat.LINK_LIST, false, entries, directives)
    }

    /** `#profile-title: …` (Happ style); other comments are ignored. */
    private fun directive(line: String): Pair<String, String>? {
        val body = line.trimStart('#', '/', '!').trim()
        val colon = body.indexOf(':')
        if (colon <= 0) return null
        val key = body.substring(0, colon).trim().lowercase()
        if (key !in SubscriptionHeaders.DIRECTIVE_KEYS) return null
        val value = body.substring(colon + 1).trim()
        return if (value.isEmpty()) null else key to value
    }
}
