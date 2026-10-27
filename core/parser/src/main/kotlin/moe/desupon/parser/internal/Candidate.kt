package moe.desupon.parser.internal

import kotlinx.serialization.json.JsonObject

/** A parsed entry before normalization: sing-box outbound/endpoint JSON plus its source name. */
internal data class Candidate(
    val outbound: JsonObject,
    val name: String?,
    val unsupported: String? = null,
    /** Outbounds this one dials through, nearest first, without `detour` links. */
    val chain: List<Candidate> = emptyList(),
)

/** Outcome of one body entry (line, array item, proxy). */
internal sealed interface Entry {
    /** [skips] are members of a multi-node entry that produced no candidate. */
    data class Nodes(val candidates: List<Candidate>, val skips: List<Skip> = emptyList()) : Entry

    data class Skip(val scheme: String?, val reason: moe.desupon.parser.SkipReason) : Entry
}

/** Runs one entry's parser; any failure becomes a counted skip instead of aborting the body. */
internal inline fun entry(scheme: String?, block: () -> List<Candidate>): Entry =
    try {
        Entry.Nodes(block())
    } catch (_: NotANodeException) {
        Entry.Skip(scheme, moe.desupon.parser.SkipReason.UNSUPPORTED_SCHEME)
    } catch (_: Exception) {
        Entry.Skip(scheme, moe.desupon.parser.SkipReason.MALFORMED)
    }

/** Collects the first reason an entry cannot run on the bundled core. */
internal class Issues {
    var reason: String? = null
        private set

    fun unsupported(why: String) {
        if (reason == null) reason = why
    }
}
