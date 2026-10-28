package moe.desupon.platform.libbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.desupon.core.libbox.CommandClientHandler
import moe.desupon.model.routing.AppSplit
import moe.desupon.platform.log.CoreException
import moe.desupon.platform.log.CoreLogEntry
import moe.desupon.platform.log.CoreLogLevel
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

        override fun resetNetwork() {
            calls += "resetNetwork"
        }

        override fun resetOutbound(tag: String) {
            calls += "resetOutbound:$tag"
        }

        override fun closeService() {
            calls += "closeService"
        }

        val handlers: MutableList<Pair<Boolean, CommandClientHandler>> = Collections.synchronizedList(mutableListOf())

        override fun newClient(handler: CommandClientHandler, logs: Boolean): CoreClient {
            handlers += logs to handler
            val tag = if (logs) "log:" else ""
            return object : CoreClient {
                override fun connect() {
                    calls += "${tag}connect"
                    connectGate.await(5, TimeUnit.SECONDS)
                    calls += "${tag}connected"
                }

                override fun disconnect() {
                    calls += "${tag}disconnect"
                }
            }
        }

        fun streamCalls() = calls.toList().filterNot { it.startsWith("log:") }

        fun logClients() = handlers.toList().filter { it.first }.map { it.second }
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
        assertEquals(listOf("reload", "connect", "connected"), core.streamCalls())
    }

    @Test
    fun theLogStreamOpensOnceAndOutlivesReloadsAndStop() = runBlocking<Unit> {
        val cmd = command()
        cmd.reload("{}", AppSplit())
        cmd.reload("{}", AppSplit())
        cmd.stop()
        delay(100)
        assertEquals(1, core.logClients().size, "one log client per server")
        assertEquals(2, core.handlers.count { !it.first }, "a stream client per reload")
        assertFalse("log:disconnect" in core.calls, "the log stream stays until the server detaches")
        val counts = core.streamCalls().groupingBy { it }.eachCount()
        assertEquals(mapOf("reload" to 2, "connect" to 2, "connected" to 2, "disconnect" to 2, "closeService" to 1), counts)
    }

    @Test
    fun aFailedLogStreamRedialsWithAFreshClientAndTheOldOneGoesQuiet() = runBlocking<Unit> {
        val cmd = command()
        cmd.reload("{}", AppSplit())
        val first = core.logClients().single()
        first.disconnected("log stream recv: rpc error: code = Unavailable desc = error reading from server: EOF")
        delay(1_000)
        assertEquals(2, core.logClients().size)
        first.disconnected("again")
        delay(1_000)
        assertEquals(2, core.logClients().size, "a replaced client's callbacks are ignored")
    }

    @Test
    fun staleStreamEndsAreNotLogged() = runBlocking<Unit> {
        val cmd = command()
        val seen = Collections.synchronizedList(mutableListOf<CoreLogEntry>())
        val collector = launch(Dispatchers.IO) { cmd.logs.collect { seen += it } }
        delay(50)
        cmd.reload("{}", AppSplit())
        val old = core.handlers.last { !it.first }.second
        cmd.reload("{}", AppSplit())
        old.disconnected("status stream recv: rpc error: code = Canceled desc = context canceled")
        val current = core.handlers.last { !it.first }.second
        current.disconnected("group stream recv: unexpected failure")
        current.disconnected("status stream recv: rpc error: code = Canceled desc = context canceled")
        delay(1_000)
        core.handlers.last { !it.first }.second.disconnected("status stream recv: EOF")
        delay(100)
        collector.cancel()
        assertEquals(listOf(CoreLogLevel.WARN, CoreLogLevel.DEBUG), seen.map { it.level }, "only current clients are noted, routine ends at debug")
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
    fun controlCallsGoToTheServerWhileTheClientReconnects() = runBlocking<Unit> {
        core.connectGate = CountDownLatch(1)
        val cmd = command(dialWaitMs = 50)
        cmd.reload("{}", AppSplit())
        cmd.select("proxy", "n-0000000000000001")
        cmd.closeConnections()
        cmd.resetNetwork()
        cmd.resetOutbound("n-0000000000000001")
        assertFalse("connected" in core.calls, "control calls waited for the client")
        assertTrue("select:proxy/n-0000000000000001" in core.calls)
        assertTrue("closeConnections" in core.calls)
        assertTrue("resetNetwork" in core.calls)
        assertTrue("resetOutbound:n-0000000000000001" in core.calls)
        core.connectGate.countDown()
    }

    @Test
    fun failedResetThrowsCoreException() = runBlocking<Unit> {
        val failing = object : CoreGateway by core {
            override fun resetNetwork(): Unit = error("core: service not started")
        }
        val cmd = LibboxCommand(failing, "moe.desupon.app", Dispatchers.IO)
        assertFailsWith<CoreException> { cmd.resetNetwork() }
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
