package moe.desupon.platform.net

import androidx.test.core.app.ApplicationProvider
import moe.desupon.brain.detect.NetModeRules
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.HostProbe
import moe.desupon.model.network.NetworkHandle
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NetModeTest {
    @Test
    fun bundledListLoadsAndOverrideWins() {
        val store = NetModeHostsStore(ApplicationProvider.getApplicationContext())
        val bundled = store.load()
        assertTrue(bundled.ru.size >= NetModeRules.MIN_RU_OK)
        assertTrue(bundled.foreign.size >= NetModeRules.MIN_FOREIGN_FAIL)
        assertEquals(443, bundled.port)

        val custom = NetModeHosts(listOf("ru.example"), listOf("intl.example"), 8443)
        store.save(custom)
        assertEquals(custom, store.load())
        store.reset()
        assertEquals(bundled, store.load())
    }

    @Test
    fun roundTripsJson() {
        val hosts = NetModeHosts(listOf("a.example", "b.example"), listOf("c.example"))
        assertEquals(hosts, NetModeHosts.parse(hosts.toJson()))
    }
}

class HostListNetModeDetectorTest {
    @Test
    fun probesTheCurrentListOnItsPortAndNetwork() = runTest {
        var hosts = NetModeHosts(listOf("ru1", "ru2", "ru3"), listOf("f1", "f2"), 8443)
        val seen = mutableListOf<Triple<String, Int, NetworkHandle>>()
        val probe = HostProbe { host, port, network, _ ->
            synchronized(seen) { seen += Triple(host, port, network) }
            host.startsWith("ru")
        }
        val detector = HostListNetModeDetector({ hosts }, probe, io = StandardTestDispatcher(testScheduler))
        val key = NetworkKey(Transport.WIFI, "k")

        assertEquals(NetworkMode.WHITELIST, detector.detect(key, NetworkHandle(7)))
        assertEquals(setOf("ru1", "ru2", "ru3", "f1", "f2"), seen.map { it.first }.toSet())
        assertTrue(seen.all { it.second == 8443 && it.third == NetworkHandle(7) })

        hosts = hosts.copy(foreign = listOf("ru-mirror.example", "f2"))
        assertEquals(NetworkMode.OPEN, detector.detect(key, NetworkHandle(7)))
    }
}
