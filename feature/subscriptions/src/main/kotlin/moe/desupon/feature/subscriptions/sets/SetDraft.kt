package moe.desupon.feature.subscriptions.sets

import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId

internal enum class SetDraftError { NAME_BLANK, NAME_TAKEN }

/** Set editor content; [id] null creates a new set, [SetId.ALL] edits only its chain. */
internal data class SetDraft(
    val id: SetId?,
    val name: String = "",
    val members: Set<SubId> = emptySet(),
    /** Subset of [members]. */
    val chain: List<SubId> = emptyList(),
    val error: SetDraftError? = null,
    val saving: Boolean = false,
) {
    val priorityOnly: Boolean get() = id?.isAll == true

    fun name(value: String) = copy(name = value, error = null)

    /** Leaving the set also leaves its chain; a no-op for [SetId.ALL], whose members are fixed. */
    fun toggle(sub: SubId) = when {
        priorityOnly -> this
        sub in members -> copy(members = members - sub, chain = chain - sub)
        else -> copy(members = members + sub)
    }

    /** Unchanged when [op] does not apply or names a non-member. */
    fun chain(op: ChainOp): SetDraft {
        if (op.sub !in members) return this
        return op.applyTo(chain)?.let { copy(chain = it) } ?: this
    }

    /** [otherNames] are the names of every other user set; comparison ignores case and surrounding spaces. */
    fun validate(otherNames: Collection<String>): SetDraftError? {
        if (priorityOnly) return null
        val clean = name.trim()
        return when {
            clean.isEmpty() -> SetDraftError.NAME_BLANK
            otherNames.any { it.trim().equals(clean, ignoreCase = true) } -> SetDraftError.NAME_TAKEN
            else -> null
        }
    }
}

internal object SetOrder {
    /** User set ids with [id] moved by [delta], clamped; null when nothing moves. */
    fun move(ids: List<SetId>, id: SetId, delta: Int): List<SetId>? {
        val from = ids.indexOf(id)
        if (from < 0) return null
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (to == from) return null
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }
}
