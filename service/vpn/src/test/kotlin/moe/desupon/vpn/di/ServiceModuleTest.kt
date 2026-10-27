package moe.desupon.vpn.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import moe.desupon.brain.probe.ProbeRequestResult
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.brain.port.ConnState
import moe.desupon.brain.port.SocketProtector
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.di.dataModule
import moe.desupon.data.location.LocationListSource
import moe.desupon.data.fetch.BypassNetwork
import moe.desupon.data.geo.GeoDataUpdater
import moe.desupon.platform.di.platformModule
import moe.desupon.vpn.lifecycle.ServiceVpnControl
import moe.desupon.vpn.service.BrainFactory
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class ServiceModuleTest {
    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `brain builds from the data and platform graph`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val koin = startKoin {
            androidContext(context)
            modules(dataModule, platformModule, serviceModule)
        }.koin
        assertSame(koin.get<ServiceVpnControl>(), koin.get<VpnControl>())

        val scope = CoroutineScope(Job())
        val brain = koin.get<BrainFactory>().create(scope, SocketProtector { true }, flowOf(emptyMap()))
        assertEquals(ConnState.Idle, brain.state.value)
        scope.cancel()
        assertFalse(koin.get<GeoDataUpdater>().running.value)
    }

    @Test
    fun `selection, probes and the location list reach the data layer`() = runTest {
        val koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(dataModule, platformModule, serviceModule)
        }.koin
        val vpn = koin.get<VpnControl>()
        assertTrue(vpn.setLocation(LocationChoice.Country("nl")))
        assertEquals(LocationChoice.Country("NL"), vpn.location.first())
        assertFalse(vpn.setActiveSet(SetId(5)))
        assertEquals(SetId.ALL, vpn.activeSetId.first())
        assertEquals(ProbeRequestResult.NoNodes, vpn.requestProbe(forced = true))
        val list = koin.get<LocationListSource>().data.first()
        assertTrue(list.countries.isEmpty())
        assertEquals(LocationChoice.Country("NL"), list.choice)
    }

    @Test
    fun `bypass network is off while the core is detached`() {
        val koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(dataModule, platformModule, serviceModule)
        }.koin
        assertNull(koin.get<BypassNetwork>().current())
    }
}
