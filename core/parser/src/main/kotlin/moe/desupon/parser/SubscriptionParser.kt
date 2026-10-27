package moe.desupon.parser

import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.internal.BodyDecoder
import moe.desupon.parser.internal.Entry
import moe.desupon.parser.internal.Normalizer
import moe.desupon.parser.internal.str

object SubscriptionParser {
    /**
     * Parses a subscription response or pasted text. Never throws: unreadable entries land in [ParsedSubscription.skipped].
     * [headers] are the HTTP response headers (any case); [sourceHost] is the last-resort title.
     */
    fun parse(
        body: String,
        subId: SubId,
        headers: Map<String, String> = emptyMap(),
        sourceHost: String? = null,
    ): ParsedSubscription {
        val decoded = BodyDecoder.decode(body)
        val meta = SubscriptionHeaders.parse(headers, decoded.directives, sourceHost)
        val nodes = LinkedHashMap<NodeId, Node>()
        val reasons = HashMap<NodeId, String>()
        val skipped = ArrayList<SkippedEntry>()
        var duplicates = 0
        decoded.entries.forEachIndexed { index, e ->
            when (e) {
                is Entry.Skip -> skipped += SkippedEntry(index, e.scheme, e.reason)
                is Entry.Nodes -> {
                    for (s in e.skips) skipped += SkippedEntry(index, s.scheme, s.reason)
                    for (c in e.candidates) {
                        val r = try {
                            Normalizer.toNode(c, subId)
                        } catch (_: Exception) {
                            skipped += SkippedEntry(index, c.outbound.str("type"), SkipReason.MALFORMED)
                            continue
                        }
                        if (r.node.id in nodes) {
                            duplicates++
                            continue
                        }
                        nodes[r.node.id] = r.node
                        r.unsupported?.let { reasons[r.node.id] = it }
                    }
                }
            }
        }
        return ParsedSubscription(
            meta = meta,
            format = decoded.format,
            base64 = decoded.base64,
            nodes = nodes.values.toList(),
            skipped = skipped,
            duplicates = duplicates,
            unsupportedReasons = reasons,
        )
    }

    fun parse(
        body: ByteArray,
        subId: SubId,
        headers: Map<String, String> = emptyMap(),
        sourceHost: String? = null,
    ): ParsedSubscription = parse(String(body, Charsets.UTF_8), subId, headers, sourceHost)
}

object NodePool {
    /** Unions per-subscription node lists by [NodeId]: [Node.subs] merge, other fields come from the first occurrence. */
    fun merge(lists: Iterable<List<Node>>): List<Node> {
        val out = LinkedHashMap<NodeId, Node>()
        for (list in lists) for (n in list) {
            val prev = out[n.id]
            out[n.id] = if (prev == null) n else prev.copy(subs = prev.subs + n.subs)
        }
        return out.values.toList()
    }

    fun merge(vararg lists: List<Node>): List<Node> = merge(lists.asIterable())
}
