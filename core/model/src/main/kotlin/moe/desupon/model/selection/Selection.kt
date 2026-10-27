package moe.desupon.model.selection

import moe.desupon.model.node.NodeId
import moe.desupon.model.subscription.SubId
import java.util.Locale

@JvmInline
value class SetId(val value: Long) {
    val isAll: Boolean get() = this == ALL

    companion object {
        /** The built-in set of every subscription; it has no row and cannot be renamed or deleted. */
        val ALL = SetId(0)
    }
}

/** Subscriptions taking part in automatic selection; [name] is empty for [SetId.ALL], whose title is the UI's. */
data class SubscriptionSet(
    val id: SetId,
    val name: String,
    /** Ascending among user sets; [SetId.ALL] always comes first. */
    val position: Int,
    /** Every subscription for [SetId.ALL]. */
    val members: Set<SubId>,
    /** Members tried before the rest, in order; distinct. */
    val chain: List<SubId> = emptyList(),
)

/** One step of a set's priority chain: the chain subscription at [index], or every other member pooled. */
sealed interface ChainStep {
    data class Sub(val index: Int, val id: SubId) : ChainStep

    data object Rest : ChainStep

    companion object {
        /** Index of the first chain subscription among [subs]; `chain.size` stands for [Rest]. */
        fun indexOf(chain: List<SubId>, subs: Set<SubId>): Int {
            val i = chain.indexOfFirst { it in subs }
            return if (i < 0) chain.size else i
        }

        fun of(chain: List<SubId>, subs: Set<SubId>): ChainStep =
            indexOf(chain, subs).let { i -> if (i < chain.size) Sub(i, chain[i]) else Rest }
    }
}

/** Where the connection goes; global, independent of the active set. */
sealed interface LocationChoice {
    /** Automatic selection over the active set. */
    data object Any : LocationChoice

    /** Automatic selection over the active set's nodes of [iso] only (ISO 3166-1 alpha-2, upper case). */
    data class Country(val iso: String) : LocationChoice

    /** Manual pin to one node of any subscription; never switched away from automatically. */
    data class Node(val id: NodeId) : LocationChoice

    companion object {
        private const val ANY = "any"
        private const val COUNTRY = "country:"
        private const val NODE = "node:"

        fun country(iso: String): Country = Country(iso.trim().uppercase(Locale.ROOT))

        fun encode(choice: LocationChoice): String = when (choice) {
            Any -> ANY
            is Country -> COUNTRY + choice.iso
            is Node -> NODE + choice.id.hash
        }

        /** Unknown or malformed values read as [Any]. */
        fun decode(value: String?): LocationChoice = when {
            value == null -> Any
            value.startsWith(COUNTRY) -> value.removePrefix(COUNTRY).takeIf { it.isNotBlank() }?.let(::country) ?: Any
            value.startsWith(NODE) -> value.removePrefix(NODE).takeIf { it.isNotBlank() }?.let { Node(NodeId(it)) } ?: Any
            else -> Any
        }
    }
}
