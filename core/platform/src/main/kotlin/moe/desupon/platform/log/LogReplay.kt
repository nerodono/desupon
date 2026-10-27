package moe.desupon.platform.log

/**
 * Drops lines the core sends twice. The command server keeps a ring of recent lines and, after a reset mark, sends the
 * whole ring again whenever the log stream reopens (every reload) or the ring is emptied (a fresh service start).
 * Not thread-safe.
 */
internal class LogReplay(private val ringSize: Int) {
    /** The server's ring as last known. */
    private val ring = ArrayDeque<CoreLogEntry>()
    private var replaying = false

    /** The next batch is the server's whole ring. */
    fun reset() {
        replaying = true
    }

    /** The lines of [batch] not passed on before, in order. */
    fun accept(batch: List<CoreLogEntry>): List<CoreLogEntry> {
        if (!replaying) {
            batch.forEach(::remember)
            return batch
        }
        replaying = false
        // The ring may have lost lines at its head since; what is left of ours starts the new snapshot.
        val known = overlap(batch)
        ring.clear()
        batch.forEach(::remember)
        return batch.subList(known, batch.size)
    }

    /** The longest tail of [ring] that [snapshot] starts with. */
    private fun overlap(snapshot: List<CoreLogEntry>): Int {
        for (length in minOf(ring.size, snapshot.size) downTo 1) {
            val from = ring.size - length
            if ((0 until length).all { ring[from + it] == snapshot[it] }) return length
        }
        return 0
    }

    private fun remember(entry: CoreLogEntry) {
        ring.addLast(entry)
        if (ring.size > ringSize) ring.removeFirst()
    }
}
