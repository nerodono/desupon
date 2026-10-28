package moe.desupon.feature.settings.logs

import moe.desupon.data.settings.LogVerbosity
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.log.LogLine
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogViewTest {
    private val lines = listOf(
        LogLine.Session(1, 0, started = true),
        LogLine.Entry(2, 1_000, CoreLogLevel.NOTICE, "sing-box started (0.12s)"),
        LogLine.Entry(3, 2_000, CoreLogLevel.WARN, "outbound/juicity[n-1]: handshake timeout"),
        LogLine.Entry(4, 3_000, CoreLogLevel.ERROR, "inbound/tun[tun-in]: connection reset"),
        LogLine.Entry(5, 4_000, CoreLogLevel.DEBUG, "dns: exchange example.com. IN A"),
        LogLine.Session(6, 5_000, started = false),
    )

    @Test
    fun verbosityMapsToTheMostVerboseKeptLevel() {
        assertEquals(CoreLogLevel.ERROR, LogVerbosity.ERRORS.coreLevel())
        assertEquals(CoreLogLevel.NOTICE, LogVerbosity.WARNINGS.coreLevel(), "start notices come with warnings")
        assertEquals(CoreLogLevel.INFO, LogVerbosity.INFO.coreLevel())
        assertEquals(CoreLogLevel.DEBUG, LogVerbosity.DEBUG.coreLevel())
        assertEquals(LogVerbosity.entries.sortedBy { it.coreLevel().core }, LogVerbosity.entries, "entries go from least to most")
    }

    @Test
    fun showFiltersByLevelAndKeepsSessionMarks() {
        assertEquals(lines, LogFilter.apply(lines, "", LogShow.ALL))
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), LogFilter.apply(lines, "", LogShow.WARNINGS).map { it.seq }, "start notices come with warnings")
        assertEquals(listOf(1L, 4L, 6L), LogFilter.apply(lines, "", LogShow.ERRORS).map { it.seq })
    }

    @Test
    fun queryMatchesTextIgnoringCase() {
        assertEquals(listOf(1L, 3L, 6L), LogFilter.apply(lines, "  JUICITY ", LogShow.ALL).map { it.seq })
        assertEquals(listOf(1L, 6L), LogFilter.apply(lines, "juicity", LogShow.ERRORS).map { it.seq })
    }

    @Test
    fun exportWritesTimeLevelAndText() {
        val text = LogExport.text(lines.take(3), { if (it) "VPN on" else "VPN off" }, { "cut $it" }, ZoneOffset.UTC)
        assertEquals(
            listOf(
                "00:00:00.000 -- VPN on --",
                "00:00:01.000 N sing-box started (0.12s)",
                "00:00:02.000 W outbound/juicity[n-1]: handshake timeout",
            ),
            text.lines(),
        )
    }

    @Test
    fun exportKeepsTheNewestLinesThatFit() {
        val text = LogExport.text(lines, { "s" }, { "cut $it" }, ZoneOffset.UTC, maxChars = 80)
        val out = text.lines()
        assertEquals("cut 6", out.first())
        assertEquals("00:00:05.000 -- s --", out.last())
        assertEquals(3, out.size, out.joinToString("\n"))
    }

    @Test
    fun aFullBufferExportStaysWithinTheCap() {
        val many = (1..2_000L).map { LogLine.Entry(it, it, CoreLogLevel.WARN, "outbound/hysteria2[n-${"0".repeat(16)}]: " + "x".repeat(100)) }
        val text = LogExport.text(many, { "s" }, { "cut $it" }, ZoneOffset.UTC)
        assertTrue(text.length <= LogExport.MAX_CHARS, "${text.length}")
        assertTrue(text.startsWith("cut 2000"))
        assertTrue(text.endsWith("x".repeat(100)), "the newest line is kept")
    }

    @Test
    fun followingStopsOrResumesWhereAScrollEnds() {
        assertTrue(TailFollow.afterScroll(following = false, hasItems = true, atEnd = true))
        assertFalse(TailFollow.afterScroll(following = true, hasItems = true, atEnd = false))
        assertFalse(TailFollow.afterScroll(following = false, hasItems = false, atEnd = true), "an empty layout says nothing about the user")
        assertTrue(TailFollow.afterScroll(following = true, hasItems = false, atEnd = true))
    }
}
