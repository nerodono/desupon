package moe.desupon.platform.net

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun awaitCurrentGivesUpWithoutANetwork() = runTest {
        val observer = NetworkObserver(context)
        assertEquals(null, observer.awaitCurrent(10))
        assertEquals(0, callbacks)
    }
}
