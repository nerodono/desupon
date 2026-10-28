package moe.desupon.platform.log

/** Raw core lines of one command server, one [Session] per log stream; only the newest session passes lines to [emit], until [close]. */
internal class LogIntake(ringSize: Int, private val emit: (CoreLogEntry) -> Unit) {
    private val replay = LogReplay(ringSize)
    private var newest = 0
    private var closed = false

    fun session(): Session = synchronized(this) { Session(++newest) }

    fun close() {
        synchronized(this) { closed = true }
    }

    inner class Session internal constructor(private val id: Int) {
        private var snapshotSeen = false

        /** The stream's reset mark: before its first batch it announces the snapshot, later the server emptied its ring. */
        fun cleared() = synchronized(this@LogIntake) {
            if (isCurrent() && snapshotSeen) replay.cleared()
        }

        fun lines(raw: List<CoreLogEntry>) = synchronized(this@LogIntake) {
            if (!isCurrent()) return
            val fresh = if (snapshotSeen) {
                replay.live(raw)
            } else {
                snapshotSeen = true
                replay.snapshot(raw)
            }
            fresh.forEach(emit)
        }

        private fun isCurrent() = !closed && id == newest
    }
}
