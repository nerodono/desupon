package moe.desupon.model.node

import moe.desupon.model.Secret
import moe.desupon.model.subscription.SubId

data class Node(
    val id: NodeId,
    /** Subscriptions that list this node; one node may come from several. */
    val subs: Set<SubId>,
    val protocol: Protocol,
    val displayName: String,
    /** ISO 3166-1 alpha-2, upper case. */
    val country: String?,
    val capability: Capability,
    /**
     * Normalized sing-box outbound (endpoint when [isEndpoint]) object without `tag`; the generator assigns [NodeId.tag].
     * A chained node lists the outbounds it dials through, nearest first, under [CHAIN_KEY]; the generator links them with `detour`.
     */
    val outboundJson: Secret,
    val isEndpoint: Boolean,
    /** QUIC/WireGuard based: TCP-level probes do not apply. */
    val udpOnly: Boolean,
    /** Free-form markers from the subscription, e.g. ONLY_MOBILE, LTE. */
    val labels: Set<String> = emptySet(),
) {
    companion object {
        const val CHAIN_KEY = "chain"
    }
}
