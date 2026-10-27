package moe.desupon.parser.internal

import kotlinx.serialization.json.JsonObject

/** What a dialer reference (`detour`, xray `dialerProxy`, mihomo `dialer-proxy`) points at within one body. */
internal sealed interface Hop {
    /** A proxy outbound; [via] is its own dialer reference. */
    data class Proxy(val candidate: Candidate, val via: String?) : Hop

    /** Plain direct dialing; [modify] rewrites the outbound that dials through it. */
    data class Direct(val via: String? = null, val modify: ((JsonObject, Issues) -> JsonObject)? = null) : Hop

    data class Unsupported(val why: String) : Hop
}

internal object Chain {
    const val MAX_HOPS = 3

    /**
     * Follows [via] from [node] through [lookup] into [Candidate.chain]; a missing target or a loop is malformed.
     * [self] is the node's own reference, so pointing back at it counts as a loop.
     */
    fun resolve(node: Candidate, self: String?, via: String?, lookup: (String) -> Hop?): Candidate {
        if (via == null) return node
        val issues = Issues()
        node.unsupported?.let(issues::unsupported)
        var out = node.outbound
        val hops = ArrayList<Candidate>()
        val seen = HashSet<String>()
        self?.let(seen::add)
        var ref: String? = via
        var depth = 0
        while (ref != null) {
            if (!seen.add(ref)) malformed("dialer proxy loop")
            val hop = lookup(ref) ?: malformed("dialer proxy target missing")
            ref = null
            when (hop) {
                is Hop.Proxy -> {
                    if (++depth > MAX_HOPS) {
                        issues.unsupported("dialer proxy chain longer than $MAX_HOPS")
                    } else {
                        hops += hop.candidate
                    }
                    ref = hop.via
                }
                is Hop.Direct -> {
                    if (hop.via != null) issues.unsupported("dialer proxy chain past a direct outbound")
                    hop.modify?.let { m ->
                        if (hops.isEmpty()) {
                            out = m(out, issues)
                        } else {
                            hops[hops.lastIndex] = hops.last().let { it.copy(outbound = m(it.outbound, issues)) }
                        }
                    }
                }
                is Hop.Unsupported -> issues.unsupported(hop.why)
            }
        }
        return node.copy(outbound = out, unsupported = issues.reason, chain = hops)
    }
}
