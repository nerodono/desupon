package moe.desupon.parser

import moe.desupon.model.Secret
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.subscription.UserInfo

enum class BodyFormat {
    EMPTY,
    SING_BOX_JSON,
    XRAY_JSON,
    SIP008,
    CLASH_YAML,
    LINK_LIST,

    /** A bare wg-quick / AmneziaWG `.conf`. */
    WG_CONF,
    UNKNOWN,
}

enum class SkipReason {
    /** Scheme or proxy type this parser does not know. */
    UNKNOWN_SCHEME,

    /** Known scheme deliberately not imported: TURN profiles (`freeturn://`), ShadowsocksR, plain web links, non-WG Amnezia profiles. */
    UNSUPPORTED_SCHEME,

    /** Provider-encrypted entry, e.g. `happ://crypt…`. */
    ENCRYPTED,

    /** Recognized scheme or type whose content could not be read. */
    MALFORMED,
}

/** An entry that produced no node; carries no entry content, since links hold credentials. */
data class SkippedEntry(
    /** 0-based position of the entry in its body (line, array item or proxy). */
    val index: Int,
    val scheme: String?,
    val reason: SkipReason,
    /** For [SkipReason.MALFORMED]: what was wrong, naming a field and never its value; null when unknown. */
    val detail: String? = null,
)

/** Subscription metadata from HTTP headers, falling back to `#key: value` directives in the body. */
data class SubscriptionMeta(
    val title: String?,
    val userInfo: UserInfo?,
    val updateIntervalH: Int?,
    val supportUrl: String?,
    val webPageUrl: String?,
    val announce: String?,
    /** `Subscription-Alt-Endpoints` and `fallback-url` mirrors; they embed the subscription token. */
    val altEndpoints: List<Secret>,
) {
    companion object {
        val EMPTY = SubscriptionMeta(null, null, null, null, null, null, emptyList())
    }
}

data class ParsedSubscription(
    val meta: SubscriptionMeta,
    val format: BodyFormat,
    /** The body was base64-wrapped around [format]. */
    val base64: Boolean,
    /** Deduplicated by [NodeId], first occurrence wins. */
    val nodes: List<Node>,
    val skipped: List<SkippedEntry>,
    /** Entries dropped because an earlier entry had the same [NodeId]. */
    val duplicates: Int,
    /** Why each [moe.desupon.model.node.Capability.UNSUPPORTED] node cannot run on the bundled core. */
    val unsupportedReasons: Map<NodeId, String>,
)

/** Values for [Node.labels] derived from node names. */
object NodeLabels {
    const val ONLY_MOBILE = "ONLY_MOBILE"
    const val ONLY_WIFI = "ONLY_WIFI"
    const val LTE = "LTE"

    /** Marked as working under mobile-operator whitelists. */
    const val WHITELIST = "WHITELIST"
}
