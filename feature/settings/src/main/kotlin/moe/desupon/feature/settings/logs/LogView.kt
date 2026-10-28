package moe.desupon.feature.settings.logs

import moe.desupon.data.settings.LogVerbosity
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.log.LogLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The most verbose core level kept at [this] setting; "warnings" keeps the core's start notices too. */
fun LogVerbosity.coreLevel(): CoreLogLevel = when (this) {
    LogVerbosity.ERRORS -> CoreLogLevel.ERROR
    LogVerbosity.WARNINGS -> CoreLogLevel.NOTICE
    LogVerbosity.INFO -> CoreLogLevel.INFO
    LogVerbosity.DEBUG -> CoreLogLevel.DEBUG
}

/** Which kept lines the viewer shows, matching the verbosity setting's steps; session marks always show. */
enum class LogShow(val maxLevel: CoreLogLevel) {
    ALL(CoreLogLevel.TRACE),
    WARNINGS(CoreLogLevel.NOTICE),
    ERRORS(CoreLogLevel.ERROR),
}

/** The viewer follows new lines until the user drags; a scroll that ends at the bottom resumes it. */
internal object TailFollow {
    fun afterScroll(following: Boolean, hasItems: Boolean, atEnd: Boolean): Boolean = if (hasItems) atEnd else following
}

internal object LogFilter {
    fun apply(lines: List<LogLine>, query: String, show: LogShow): List<LogLine> {
        val q = query.trim()
        if (q.isEmpty() && show == LogShow.ALL) return lines
        return lines.filter { line ->
            when (line) {
                is LogLine.Session -> true
                is LogLine.Entry -> line.level.core <= show.maxLevel.core && (q.isEmpty() || line.text.contains(q, ignoreCase = true))
            }
        }
    }
}

/** Plain text of shown lines for copying and sharing; the newest lines win when it would exceed [maxChars]. */
internal object LogExport {
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun text(
        lines: List<LogLine>,
        session: (started: Boolean) -> String,
        truncated: (total: Int) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
        maxChars: Int = MAX_CHARS,
    ): String {
        val out = ArrayDeque<String>()
        var size = 0
        for (line in lines.asReversed()) {
            val at = time.format(Instant.ofEpochMilli(line.atMs).atZone(zone))
            val text = when (line) {
                is LogLine.Entry -> "$at ${line.level.letter()} ${line.text}"
                is LogLine.Session -> "$at -- ${session(line.started)} --"
            }
            if (size + text.length + 1 > maxChars) {
                out.addFirst(truncated(lines.size))
                break
            }
            out.addFirst(text)
            size += text.length + 1
        }
        return out.joinToString("\n")
    }

    private fun CoreLogLevel.letter(): Char = name.first()

    /** The text travels as UTF-16 and twice (extra and ClipData) through a 1 MB binder buffer shared with other calls. */
    const val MAX_CHARS = 50_000
}
