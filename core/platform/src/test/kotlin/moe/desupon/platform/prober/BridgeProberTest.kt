package moe.desupon.platform.prober

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.desupon.brain.port.ProbeRequest
import moe.desupon.model.Secret
import moe.desupon.model.probe.ProbeResult
import moe.desupon.platform.libbox.CoreRuntime
import moe.desupon.platform.net.NetworkObserver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BridgeProberTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val observer = NetworkObserver(context)
    private val nodes = """{"outbounds":[{"type":"shadowsocks","tag":"n-0000000000000001"},{"type":"direct","tag":"direct"}]}"""

    private fun prober(call: PoolProbeCall) = BridgeProber(context, CoreRuntime(context, observer), observer, Dispatchers.IO, call)

    private fun request(dns: String) = ProbeRequest(Secret(nodes), timeoutMs = 1_000, concurrency = 2, dnsCsv = dns, network = null)

    @Test
    fun cancelledPoolProbeReturnsWithoutWaitingForTheNativeCall() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val p = prober { _, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            "[]"
        }
        val job = launch(Dispatchers.Default) { p.probePool(request("192.0.2.53")) { true } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(1_000) { job.cancelAndJoin() }
        assertEquals(1L, release.count, "cancel waited for the native probe")
        release.countDown()
    }

    @Test
    fun withoutUnderlyingDnsEveryNodeFailsAndNothingRuns() = runBlocking<Unit> {
        var calls = 0
        val p = prober { _, _ ->
            calls++
            "[]"
        }
        val results = p.probePool(request("")) { true }
        assertEquals(0, calls)
        val fail = assertIs<ProbeResult.Fail>(results.getValue("n-0000000000000001"))
        assertTrue("DNS" in fail.reason)
        assertNull(results["direct"])
    }

    @Test
    fun underlyingDnsReachesTheNativeProbe() = runBlocking<Unit> {
        var dns: String? = null
        val p = prober { input, _ ->
            dns = input.dnsCsv
            """[{"tag":"n-0000000000000001","ok":true,"stage":"get204","code":204,"rtt_ms":42}]"""
        }
        val results = p.probePool(request("192.0.2.53,2001:db8::53")) { true }
        assertEquals("192.0.2.53,2001:db8::53", dns)
        assertIs<ProbeResult.Ok>(results.getValue("n-0000000000000001"))
    }

    @Test
    fun ipv4OnlyAndPerNodeTimeoutsReachTheNativeProbe() = runBlocking<Unit> {
        var seen: PoolProbeInput? = null
        val p = prober { input, _ ->
            seen = input
            "[]"
        }
        p.probePool(request("192.0.2.53").copy(ipv4Only = true, timeouts = mapOf("n-0000000000000001" to 1_800))) { true }
        val input = seen!!
        assertTrue(input.ipv4Only)
        assertEquals(1_800, org.json.JSONObject(input.timeoutsJson).getInt("n-0000000000000001"))

        p.probePool(request("192.0.2.53")) { true }
        assertEquals(false, seen!!.ipv4Only)
        assertEquals("", seen!!.timeoutsJson)
    }
}
