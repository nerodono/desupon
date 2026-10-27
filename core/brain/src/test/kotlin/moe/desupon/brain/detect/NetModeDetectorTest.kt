@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.brain.detect

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.WIFI
import moe.desupon.brain.port.HostProbe
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetModeDetectorTest {
    @Test
    fun `classification table`() {
        data class Row(val ruOk: Int, val ruFail: Int, val fOk: Int, val fFail: Int, val mode: NetworkMode)
        val rows = listOf(
            Row(3, 0, 0, 2, NetworkMode.WHITELIST),
            Row(5, 0, 0, 3, NetworkMode.WHITELIST),
            Row(3, 2, 1, 2, NetworkMode.WHITELIST),
            // A single foreign failure is not a whitelist.
            Row(5, 0, 2, 1, NetworkMode.OPEN),
            // Two flaky hosts out of five foreign ones are not a whitelist either.
            Row(5, 0, 3, 2, NetworkMode.OPEN),
            // Too few RU hosts answering: some may be missing from this operator's list.
            Row(2, 3, 0, 3, NetworkMode.OPEN),
            Row(5, 0, 3, 0, NetworkMode.OPEN),
            Row(0, 5, 3, 0, NetworkMode.OPEN),
            Row(0, 5, 0, 3, NetworkMode.OFFLINE),
            Row(0, 0, 0, 0, NetworkMode.UNKNOWN),
        )
        for (r in rows) assertEquals(r.mode, NetModeRules.classify(r.ruOk, r.ruFail, r.fOk, r.fFail), r.toString())
    }

    private val ru = listOf("ru1", "ru2", "ru3", "ru4")
    private val foreign = listOf("f1", "f2", "f3")

    @Test
    fun `whitelist from probes, bound to the given network`() = runTest {
        val seen = mutableListOf<NetworkHandle>()
        val probe = HostProbe { host, port, network, _ ->
            seen += network
            assertEquals(443, port)
            host.startsWith("ru")
        }
        val mode = ProbingNetModeDetector(probe, ru, foreign).detect(WIFI, NetworkHandle(42))
        assertEquals(NetworkMode.WHITELIST, mode)
        assertEquals(7, seen.size)
        assertTrue(seen.all { it == NetworkHandle(42) })
    }

    @Test
    fun `hung and throwing probes count as failures within the timeout`() = runTest {
        val probe = HostProbe { host, _, _, _ ->
            when {
                host == "f1" -> error("reset")
                host.startsWith("f") -> {
                    delay(60_000)
                    true
                }
                else -> true
            }
        }
        val mode = ProbingNetModeDetector(probe, ru, foreign, timeoutMs = 2_000).detect(WIFI, NetworkHandle(1))
        assertEquals(NetworkMode.WHITELIST, mode)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `everything failing is offline, not a stop`() = runTest {
        val mode = ProbingNetModeDetector({ _, _, _, _ -> false }, ru, foreign).detect(WIFI, NetworkHandle(1))
        assertEquals(NetworkMode.OFFLINE, mode)
    }

    @Test
    fun `open network`() = runTest {
        val mode = ProbingNetModeDetector({ _, _, _, _ -> true }, ru, foreign).detect(WIFI, NetworkHandle(1))
        assertEquals(NetworkMode.OPEN, mode)
    }
}
