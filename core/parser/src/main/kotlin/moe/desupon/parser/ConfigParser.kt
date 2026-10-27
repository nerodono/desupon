package moe.desupon.parser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.internal.B64
import moe.desupon.parser.internal.BodyDecoder
import moe.desupon.parser.internal.Entry
import moe.desupon.parser.internal.ShareUrl
import moe.desupon.parser.internal.lines2
import moe.desupon.parser.internal.stripBom

/** Why a config produced no node. */
sealed interface ConfigError {
    data object Empty : ConfigError

    /** Neither a share link nor proxy JSON this parser knows. */
    data object NotAConfig : ConfigError

    /** [count] links or outbounds where a config holds one. */
    data class Several(val count: Int) : ConfigError

    data class UnknownScheme(val scheme: String?) : ConfigError

    /** A known scheme deliberately not imported, see [SkipReason.UNSUPPORTED_SCHEME]. */
    data class NotImported(val scheme: String?) : ConfigError

    data object Encrypted : ConfigError

    /** [detail] names the field at fault, never its value. */
    data class Malformed(val scheme: String?, val detail: String?) : ConfigError
}

/** One config read on its own; [error] is set exactly when [nodes] is empty. */
data class ConfigRead(
    val nodes: List<Node>,
    val unsupportedReasons: Map<NodeId, String>,
    val error: ConfigError?,
)

/** One config cut out of imported text; [text] reads back to [nodes]. */
data class ConfigSource(
    val text: String,
    val nodes: List<Node>,
    val unsupportedReasons: Map<NodeId, String>,
)

data class ConfigSplit(
    val configs: List<ConfigSource>,
    val skipped: List<SkippedEntry>,
    /** Entries whose nodes all came earlier in the same text. */
    val duplicates: Int,
)

/**
 * Configs: single server profiles kept as the user's own text, a share link or one sing-box outbound object,
 * as opposed to a subscription body that a provider replaces wholesale.
 */
object ConfigParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Reads [text] as exactly one config; one link may still yield several nodes (an Amnezia profile, an xray balancer). */
    fun read(text: String, subId: SubId): ConfigRead {
        val t = stripBom(text).trim()
        if (t.isEmpty()) return failed(ConfigError.Empty)
        val decoded = BodyDecoder.decode(t)
        if (decoded.format == BodyFormat.UNKNOWN || decoded.format == BodyFormat.EMPTY) return failed(ConfigError.NotAConfig)
        // Non-proxy outbounds (direct, dns, groups) decode to empty entries.
        val entries = decoded.entries.filterNot { it is Entry.Nodes && it.candidates.isEmpty() && it.skips.isEmpty() }
        if (entries.isEmpty()) return failed(ConfigError.NotAConfig)
        if (entries.size > 1) return failed(ConfigError.Several(entries.size))
        val collected = EntryNodes.collect(entries, subId)
        if (collected.nodes.isNotEmpty()) return ConfigRead(collected.nodes, collected.reasons, null)
        return failed(collected.skipped.firstOrNull()?.let(::error) ?: ConfigError.NotAConfig)
    }

    /**
     * Cuts imported text into configs: each line of a link list stays as written; nodes from any other format
     * (sing-box, xray, Clash, SIP008, wg-quick) become one outbound JSON each, see [outboundText].
     */
    fun split(text: String, subId: SubId): ConfigSplit {
        val t = stripBom(text).trim()
        val decoded = BodyDecoder.decode(t)
        val seen = HashSet<NodeId>()
        val configs = ArrayList<ConfigSource>()
        val skipped = ArrayList<SkippedEntry>()
        var duplicates = 0

        fun keep(source: String, read: ConfigRead) {
            if (read.nodes.none { it.id !in seen }) {
                duplicates++
                return
            }
            read.nodes.forEach { seen += it.id }
            configs += ConfigSource(source, read.nodes, read.unsupportedReasons)
        }

        if (decoded.format == BodyFormat.LINK_LIST) {
            val body = if (decoded.base64) B64.decodeUtf8(t) ?: t else t
            var index = 0
            for (raw in body.lines2()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
                val read = read(line, subId)
                if (read.nodes.isEmpty()) {
                    skipped += skippedOf(index, ShareUrl.schemeOf(line), read.error)
                } else {
                    keep(line, read)
                }
                index++
            }
        } else {
            val collected = EntryNodes.collect(decoded.entries, subId)
            skipped += collected.skipped
            duplicates += collected.duplicates
            for (node in collected.nodes) {
                val source = outboundText(node)
                val read = read(source, subId)
                val scheme = node.protocol.name.lowercase()
                when {
                    read.nodes.isEmpty() -> skipped += skippedOf(-1, scheme, read.error)
                    // The source format knew the node cannot run; its outbound JSON alone would pass as runnable.
                    node.id in collected.reasons && read.unsupportedReasons.isEmpty() ->
                        skipped += SkippedEntry(-1, scheme, SkipReason.UNSUPPORTED_SCHEME)
                    else -> keep(source, read)
                }
            }
        }
        return ConfigSplit(configs, skipped, duplicates)
    }

    /** [node]'s outbound as editable sing-box JSON, its display name as the tag so a re-read keeps it. */
    fun outboundText(node: Node): String {
        val o = pretty.parseToJsonElement(node.outboundJson.raw()).jsonObject
        return outboundText(o, node.displayName)
    }

    /** [outbound] with [name] as its tag, `type` and `tag` first; a blank [name] drops the tag. */
    fun outboundText(outbound: JsonObject, name: String?): String {
        val ordered = LinkedHashMap<String, JsonElement>()
        outbound["type"]?.let { ordered["type"] = it }
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered["tag"] = JsonPrimitive(it) }
        for ((k, v) in outbound) if (k != "type" && k != "tag") ordered[k] = v
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(ordered))
    }

    fun error(skip: SkippedEntry): ConfigError = when (skip.reason) {
        SkipReason.UNKNOWN_SCHEME -> ConfigError.UnknownScheme(skip.scheme)
        SkipReason.UNSUPPORTED_SCHEME -> ConfigError.NotImported(skip.scheme)
        SkipReason.ENCRYPTED -> ConfigError.Encrypted
        SkipReason.MALFORMED -> ConfigError.Malformed(skip.scheme, skip.detail)
    }

    private fun skippedOf(index: Int, scheme: String?, error: ConfigError?): SkippedEntry = when (error) {
        is ConfigError.UnknownScheme -> SkippedEntry(index, scheme, SkipReason.UNKNOWN_SCHEME)
        is ConfigError.NotImported -> SkippedEntry(index, scheme, SkipReason.UNSUPPORTED_SCHEME)
        ConfigError.Encrypted -> SkippedEntry(index, scheme, SkipReason.ENCRYPTED)
        is ConfigError.Malformed -> SkippedEntry(index, scheme, SkipReason.MALFORMED, error.detail)
        else -> SkippedEntry(index, scheme, SkipReason.MALFORMED)
    }

    private fun failed(error: ConfigError) = ConfigRead(emptyList(), emptyMap(), error)
}
