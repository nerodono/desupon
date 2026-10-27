package moe.desupon.platform.libbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.desupon.core.libbox.CommandClientHandler
import moe.desupon.model.routing.AppSplit
import moe.desupon.platform.log.CoreException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibboxCommandTest {
    private class FakeGateway : CoreGateway {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var connectGate = CountDownLatch(0)
        var failReload = false

        override fun onAttachChanged(listener: (Boolean) -> Unit) = listener(true)

        override fun startOrReload(configJson: String, overrides: PackageOverrides) {
            calls += "reload"
            if (failReload) error("core refused")
        }

        override fun select(group: String, tag: String) {
            calls += "select:$group/$tag"
        }

        override fun closeConnections() {
            calls += "closeConnections"
        }

        override fun closeService() {
            calls += "closeService"
        }

        override fun newClient(handler: CommandClientHandler): CoreClient = object : CoreClient {
            override fun connect() {
                calls += "connect"
                connectGate.await(5, TimeUnit.SECONDS)
                calls += "connected"
            }

            override fun disconnect() {
                calls += "disconnect"
            }
        }
    }

    private val core = FakeGateway()

    private fun command(dialWaitMs: Long = 3_000) = LibboxCommand(core, "moe.desupon.app", Dispatchers.IO, dialWaitMs)

    @Test
    fun reloadReturnsOnlyOnceTheClientDialed() = runBlocking<Unit> {
        core.connectGate = CountDownLatch(1)
        val cmd = command()
        val reload = async { cmd.reload("{}", AppSplit()) }
        delay(200)
        assertFalse(reload.isCompleted, "reload returned before the client dialed")
        core.connectGate.countDown()
        reload.await()
        assertEquals(listOf("reload", "connect", "connected"), core.calls.toList())
    }

    @Test
    fun reloadGivesUpWaitingForASlowDial() = runBlocking<Unit> {
        core.connectGate = CountDownLatch(1)
        val cmd = command(dialWaitMs = 100)
        cmd.reload("{}", AppSplit())
        assertFalse("connected" in core.calls)
        core.connectGate.countDown()
    }

    @Test
    fun selectAndCloseGoToTheServerWhileTheClientReconnects() = runBlocking<Unit> {
        core.connectGate = CountDownLatch(1)
        val cmd = command(dialWaitMs = 50)
        cmd.reload("{}", AppSplit())
        cmd.select("proxy", "n-0000000000000001")
        cmd.closeConnections()
        assertFalse("connected" in core.calls, "control calls waited for the client")
        assertTrue("select:proxy/n-0000000000000001" in core.calls)
        assertTrue("closeConnections" in core.calls)
        core.connectGate.countDown()
    }

    @Test
    fun failedReloadThrowsCoreExceptionAndDialsNothing() = runBlocking<Unit> {
        core.failReload = true
        val cmd = command()
        assertFailsWith<CoreException> { cmd.reload("{}", AppSplit()) }
        assertEquals(listOf("reload"), core.calls.toList())
    }

    @Test
    fun stopDisconnectsTheClientAndClosesTheService() = runBlocking<Unit> {
        val cmd = command()
        cmd.reload("{}", AppSplit())
        cmd.stop()
        delay(100)
        assertTrue(core.calls.containsAll(listOf("closeService", "disconnect")))
    }
}
