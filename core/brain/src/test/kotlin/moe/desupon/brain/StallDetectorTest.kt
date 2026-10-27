package moe.desupon.brain

import moe.desupon.model.status.ConnInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class StallDetectorTest {
    private fun conn(id: String, up: Long, down: Long, chain: List<String> = listOf("proxy", "n-1")) =
        ConnInfo(id, "tcp", "203.0.113.1:443", null, chain.last(), chain, up, down, 0, false)

    private fun run(samples: List<List<ConnInfo>>, stepMs: Long = 1_000): List<Long> {
        val d = StallDetector("proxy", windowMs = 10_000, minUplinkBytes = 2_048)
        return samples.mapIndexedNotNull { i, s -> (i * stepMs).takeIf { d.onSample(s, it) } }
    }

    @Test
    fun `uplink without downlink over the window fires once per window`() {
        val samples = (0..25).map { listOf(conn("a", up = 1_000L * (it + 1), down = 500)) }
        assertEquals(listOf(11_000L, 21_000L), run(samples))
    }

    @Test
    fun `downlink progress resets the stall`() {
        val samples = (0..25).map { i -> listOf(conn("a", up = 1_000L * (i + 1), down = if (i == 8) 900 else if (i > 8) 900 else 500)) }
        assertEquals(listOf(19_000L), run(samples))
    }

    @Test
    fun `direct connections cannot mask or trigger a stall`() {
        val samples = (0..25).map { i ->
            listOf(
                conn("d", up = 1_000L * (i + 1), down = 0, chain = listOf("direct")),
                conn("p", up = 100, down = 100L * (i + 1)),
            )
        }
        assertEquals(emptyList(), run(samples))
        val masked = (0..25).map { i ->
            listOf(
                conn("d", up = 100, down = 50_000L * (i + 1), chain = listOf("direct")),
                conn("p", up = 1_000L * (i + 1), down = 0),
            )
        }
        assertEquals(listOf(10_000L, 20_000L), run(masked))
    }

    @Test
    fun `idle proxy is not a stall`() {
        val samples = (0..25).map { listOf(conn("a", up = 100, down = 100)) }
        assertEquals(emptyList(), run(samples))
    }
}
