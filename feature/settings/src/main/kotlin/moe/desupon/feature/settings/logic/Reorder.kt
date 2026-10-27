package moe.desupon.feature.settings.logic

object Reorder {
    /** [list] with the item at [from] moved to [to]; unchanged when either index is out of range. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Moves [item] one step towards the head ([delta] < 0) or tail ([delta] > 0). */
    fun <T> shift(list: List<T>, item: T, delta: Int): List<T> {
        val from = list.indexOf(item)
        if (from < 0) return list
        return move(list, from, from + delta)
    }
}
