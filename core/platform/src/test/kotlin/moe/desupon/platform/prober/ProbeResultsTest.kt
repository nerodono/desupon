package moe.desupon.platform.prober

import moe.desupon.model.probe.ProbeResult
import moe.desupon.model.probe.ProbeStage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class ProbeResultsTest {
    @Test
    fun parsesCoreArray() {
        val json = """
            [
              {"tag":"n-ok","ok":true,"stage":"get204","code":204,"body_len":0,"rtt_ms":321},
              {"tag":"n-200","ok":false,"stage":"get204","code":200,"body_len":12,"rtt_ms":80,"error":"unexpected response 200 OK"},
              {"tag":"n-tls","ok":false,"stage":"tls","code":0,"body_len":0,"rtt_ms":0,"error":"remote error: tls: handshake failure"},
              {"tag":"n-tcp","ok":false,"stage":"transport","code":0,"body_len":0,"rtt_ms":0,"error":"dial tcp 192.0.2.1:443: i/o timeout"},
              {"tag":"n-wg","ok":false,"stage":"transport","error":"IpcSet: private_key=6f1c0e2a9b3d4c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6"},
              {"ok":true}
            ]
        """.trimIndent()
        val r = ProbeResults.parse(json)
        assertEquals(listOf("n-ok", "n-200", "n-tls", "n-tcp", "n-wg"), r.keys.toList())
        assertEquals(ProbeResult.Ok(ProbeStage.GET204, 321), r["n-ok"])
        assertEquals(ProbeResult.Fail(ProbeStage.GET204, "unexpected response 200 OK"), r["n-200"])
        assertEquals(ProbeStage.TLS, r.getValue("n-tls").stage)
        assertEquals(ProbeStage.TRANSPORT, r.getValue("n-tcp").stage)
        val wg = assertIs<ProbeResult.Fail>(r["n-wg"])
        assertFalse("6f1c0e2a" in wg.reason)
    }

    @Test
    fun parsesStagedTimings() {
        val json = """
            [
              {"tag":"n-warm","ok":true,"stage":"get204","code":204,"rtt_ms":58,"connect_ms":119,"first_ms":178,"warm":true},
              {"tag":"n-cold","ok":true,"stage":"get204","code":204,"rtt_ms":230,"connect_ms":169,"first_ms":230,"warm":false},
              {"tag":"n-old","ok":true,"stage":"get204","code":204,"rtt_ms":90}
            ]
        """.trimIndent()
        val r = ProbeResults.parse(json)
        assertEquals(ProbeResult.Ok(ProbeStage.GET204, 58, connectMs = 119, firstMs = 178, warm = true), r["n-warm"])
        assertEquals(ProbeResult.Ok(ProbeStage.GET204, 230, connectMs = 169, firstMs = 230, warm = false), r["n-cold"])
        assertEquals(ProbeResult.Ok(ProbeStage.GET204, 90, connectMs = 0, firstMs = 90, warm = false), r["n-old"])
    }

    @Test
    fun emptyErrorFallsBackToCode() {
        assertEquals(ProbeResult.Fail(ProbeStage.GET204, "unexpected response 503"), ProbeResults.of(false, "get204", 503, 0, ""))
        assertEquals(ProbeResult.Fail(ProbeStage.TRANSPORT, "failed"), ProbeResults.of(false, "weird", 0, 0, null))
    }

    @Test
    fun malformedOutputThrowsWithoutEcho() {
        val e = assertFailsWith<IllegalArgumentException> { ProbeResults.parse("password=hunter2") }
        assertFalse("hunter2" in e.message.orEmpty())
    }

    @Test
    fun probedTagsSkipGroupsAndDirect() {
        val config = """
            {"outbounds":[
              {"type":"vless","tag":"n-a"},{"type":"direct","tag":"direct"},
              {"type":"selector","tag":"proxy"},{"type":"urltest","tag":"auto"},{"type":"hysteria2","tag":"n-b"}],
             "endpoints":[{"type":"wireguard","tag":"n-c"}]}
        """.trimIndent()
        assertEquals(listOf("n-a", "n-b", "n-c"), ProbeResults.probedTags(config))
        assertEquals(emptyList(), ProbeResults.probedTags("not json"))
    }
}
