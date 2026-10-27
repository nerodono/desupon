package moe.desupon.vpn.lifecycle

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.port.LibboxControl
import moe.desupon.model.routing.AppSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LastConfigTest {
    private val raw = """{"outbounds":[{"type":"trojan","tag":"n-0123456789abcdef","server":"node.example.com","server_port":443,
        |"password":"pw-synthetic","transport":{"type":"ws","path":"/ws/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"}}]}""".trimMargin()

    private class Core(var fail: Boolean = false) : LibboxControl {
        override val groups = emptyFlow<Nothing>()
        override val status = emptyFlow<Nothing>()
        override val connections = emptyFlow<Nothing>()

        override suspend fun reload(configJson: String, apps: AppSplit) {
            if (fail) error("core refused")
        }

        override suspend fun select(group: String, tag: String) = Unit

        override suspend fun closeConnections() = Unit

        override suspend fun resetNetwork() = Unit

        override suspend fun resetOutbound(tag: String) = Unit

        override suspend fun stop() = Unit
    }

    @Test
    fun recordsOnlyAcceptedConfigsAndOnlyMasked() = runTest {
        val last = LastConfig()
        val core = Core(fail = true)
        val libbox = RecordingLibbox(core, last)
        runCatching { libbox.reload(raw, AppSplit()) }.onSuccess { fail("expected the core failure") }
        assertNull(last.masked.value)

        core.fail = false
        libbox.reload(raw, AppSplit())
        val shown = last.masked.value ?: fail("nothing recorded")
        assertFalse("pw-synthetic" in shown)
        assertFalse("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789" in shown)
        assertTrue("node.example.com" in shown)
        assertTrue("n-0123456789abcdef" in shown)
        assertEquals(LastConfig.mask(raw), shown)
    }
}
