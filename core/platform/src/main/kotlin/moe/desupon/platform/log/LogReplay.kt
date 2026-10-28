package moe.desupon.platform.log

/** Passes on each raw core line once across the ring snapshots that every (re)opened log stream starts with; not thread-safe. */
internal class LogReplay(private val ringSize: Int) {
    /** Mirror of the server's ring as delivered here, which may miss lines the server dropped for us. */
    private val ring = ArrayDeque<CoreLogEntry>()

    /** The snapshot's last lines while the first live batch may still repeat them. */
    private var snapshotTail: List<CoreLogEntry>? = null

    /** The first batch of a stream: the server's whole ring; returns the lines not passed on before. */
    fun snapshot(lines: List<CoreLogEntry>): List<CoreLogEntry> {
        val fresh = when {
            ring.isEmpty() -> lines
            else -> anchor(lines, contiguous = true) ?: anchor(lines, contiguous = false) ?: unseen(lines)
        }
        ring.clear()
        lines.takeLast(ringSize).forEach(ring::addLast)
        snapshotTail = lines.takeLast(TAIL)
        return fresh
    }

    /** A later batch of the same stream. */
    fun live(lines: List<CoreLogEntry>): List<CoreLogEntry> {
        // A line pushed before the snapshot can be emitted to the new subscription right after it.
        val repeated = snapshotTail?.let { tail -> overlap(tail, lines) } ?: 0
        snapshotTail = null
        val fresh = lines.subList(repeated, lines.size)
        fresh.forEach(::remember)
        return fresh
    }

    /** The server emptied its ring mid-stream; what follows is new. */
    fun cleared() {
        ring.clear()
        snapshotTail = null
    }

    /** What follows the latest place in [lines] where our last lines appear, in a row or with gaps the server dropped. */
    private fun anchor(lines: List<CoreLogEntry>, contiguous: Boolean): List<CoreLogEntry>? {
        val mine = ring.takeLast(ANCHOR)
        for (end in lines.indices.reversed()) {
            if (lines[end] != mine.last()) continue
            if (endsWith(lines, end, mine, contiguous)) return lines.subList(end + 1, lines.size)
        }
        return null
    }

    private fun endsWith(lines: List<CoreLogEntry>, end: Int, mine: List<CoreLogEntry>, contiguous: Boolean): Boolean {
        var j = mine.lastIndex
        var i = end
        val floor = if (contiguous) end - mine.lastIndex else end - mine.size * GAP_FACTOR
        while (j >= 0 && i >= maxOf(0, floor)) {
            if (lines[i] == mine[j]) {
                j--
            } else if (contiguous) {
                return false
            }
            i--
        }
        return j < 0
    }

    /** No anchor: the ring moved past everything we had, or our tail was lost; keep lines we have not seen. */
    private fun unseen(lines: List<CoreLogEntry>): List<CoreLogEntry> {
        val seen = HashMap<CoreLogEntry, Int>()
        ring.forEach { seen.merge(it, 1, Int::plus) }
        return lines.filter { line ->
            val n = seen[line] ?: 0
            if (n > 0) seen[line] = n - 1
            n == 0
        }
    }

    /** The longest prefix of [lines] that is a suffix of [tail]. */
    private fun overlap(tail: List<CoreLogEntry>, lines: List<CoreLogEntry>): Int {
        for (m in minOf(tail.size, lines.size) downTo 1) {
            if ((0 until m).all { lines[it] == tail[tail.size - m + it] }) return m
        }
        return 0
    }

    private fun remember(entry: CoreLogEntry) {
        ring.addLast(entry)
        if (ring.size > ringSize) ring.removeFirst()
    }

    private companion object {
        const val ANCHOR = 6
        const val TAIL = 8
        const val GAP_FACTOR = 4
    }
}
