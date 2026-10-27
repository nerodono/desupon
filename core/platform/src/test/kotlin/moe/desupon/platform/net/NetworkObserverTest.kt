@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.desupon.platform.net

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class NetworkObserverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val callbacks get() = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.size

    @Test
    fun watchersKeepTheCallbackRegisteredAcrossTheServiceStopping() = runTest {
        val observer = NetworkObserver(context)
        val watching = backgroundScope.launch { observer.watch().first { false } }
        runCurrent()
        assertEquals(1, callbacks)

        observer.start()
        observer.stop()
        assertEquals(1, callbacks, "the service stopping must not cut a watcher off")

        watching.cancel()
        runCurrent()
        assertEquals(0, callbacks)

        observer.start()
        assertEquals(1, callbacks)
        observer.stop()
        assertEquals(0, callbacks)
    }

    @Test
    fun unblockingANetworkThatWasBlockedIsReported() = runTest {
        val observer = NetworkObserver(context)
        var unblocked = 0
        observer.unblocked.onEach { unblocked++ }.launchIn(backgroundScope)
        observer.start()
        runCurrent()
        val callback = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(100)
        val cell = ShadowNetwork.newInstance(101)

        callback.onBlockedStatusChanged(wifi, false)
        runCurrent()
        assertEquals(0, unblocked, "a network that was never blocked")

        callback.onBlockedStatusChanged(wifi, true)
        callback.onBlockedStatusChanged(cell, false)
        runCurrent()
        assertEquals(0, unblocked, "another network")

        callback.onBlockedStatusChanged(wifi, false)
        runCurrent()
        assertEquals(1, unblocked)
        callback.onBlockedStatusChanged(wifi, false)
        runCurrent()
        assertEquals(1, unblocked, "reported once per block")
        observer.stop()
    }

    @Test
    fun awaitCurrentGivesUpWithoutANetwork() = runTest {
        val observer = NetworkObserver(context)
        assertEquals(null, observer.awaitCurrent(10))
        assertEquals(0, callbacks)
    }
}
