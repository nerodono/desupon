package moe.desupon.platform.di

import androidx.test.core.app.ApplicationProvider
import moe.desupon.brain.port.HostProbe
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.brain.port.Prober
import moe.desupon.platform.net.HostListNetModeDetector
import moe.desupon.platform.net.SocketHostProbe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class PlatformModuleTest {
    @After
    fun tearDown() = stopKoin()

    @Test
    fun graphBuildsTheProbingDetectorOverSocketHostProbe() {
        val koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(platformModule)
        }.koin
        assertIs<SocketHostProbe>(koin.get<HostProbe>())
        assertIs<HostListNetModeDetector>(koin.get<NetModeDetector>())
        koin.get<LibboxControl>()
        koin.get<Prober>()
    }
}
