package moe.desupon.vpn.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import moe.desupon.brain.SelectorBrain
import moe.desupon.brain.plan.DefaultTierPlanner
import moe.desupon.brain.port.BrainEvent
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.SelectionPort
import moe.desupon.brain.port.SocketProtector
import moe.desupon.brain.port.TierPlanner
import moe.desupon.brain.port.VpnControl
import moe.desupon.brain.probe.OffTunnelPoolProbe
import moe.desupon.brain.probe.PoolProbes
import moe.desupon.brain.probe.ProbeActivity
import moe.desupon.config.ConfigGenerator
import moe.desupon.data.di.AndroidClock
import moe.desupon.data.fetch.BypassNetwork
import moe.desupon.data.geo.GeoDataBridge
import moe.desupon.data.geo.MeteredNetwork
import moe.desupon.data.location.LocationListSource
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.ProbeStatRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.platform.geo.CoreGeoData
import moe.desupon.platform.libbox.CoreRuntime
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.vpn.lifecycle.LastConfig
import moe.desupon.vpn.lifecycle.RecordingLibbox
import moe.desupon.vpn.lifecycle.ServiceVpnControl
import moe.desupon.vpn.lifecycle.VpnStateHolder
import moe.desupon.vpn.service.BrainFactory
import moe.desupon.vpn.split.InstalledApps
import moe.desupon.vpn.split.InstalledOnlyLibbox
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

private const val NETWORK_WAIT_MS = 3_000L

private fun Scope.clock(): Clock = getOrNull() ?: AndroidClock

/** Needs dataModule and platformModule; Clock, TierPlanner and ConfigGenerator fall back to defaults when the graph has none. */
val serviceModule = module {
    single { VpnStateHolder() }
    single { ServiceVpnControl(androidContext(), get(), get(), get()) } bind VpnControl::class
    single { InstalledApps(androidContext()) }
    single { LastConfig() }
    single<BypassNetwork> {
        val runtime = get<CoreRuntime>()
        val observer = get<NetworkObserver>()
        BypassNetwork { observer.current.value?.network?.takeIf { runtime.host != null } }
    }
    single<MeteredNetwork> {
        val observer = get<NetworkObserver>()
        MeteredNetwork { observer.awaitCurrent(NETWORK_WAIT_MS)?.metered }
    }
    single<GeoDataBridge> {
        object : GeoDataBridge {
            override fun index(datPath: String, kind: String) = CoreGeoData.index(datPath, kind)

            override fun convert(datPath: String, kind: String, namesJson: String, outDir: String) =
                CoreGeoData.convert(datPath, kind, namesJson, outDir)
        }
    }
    single { ProbeActivity(clock()) }
    single {
        val runtime = get<CoreRuntime>()
        val observer = get<NetworkObserver>()
        val offTunnel = OffTunnelPoolProbe(
            prober = get(),
            configGen = getOrNull<ConfigGenerator>() ?: ConfigGenerator(),
            nodes = get(),
            selection = get<SelectionPort>(),
            settings = get(),
            stats = get(),
            network = { observer.awaitCurrent(NETWORK_WAIT_MS)?.let { BrainEvent.NetworkChanged(it.key, it.handle, it.dnsServers, it.ipv6) } },
            // Our own tunnel may come up mid-probe; outside it there is nothing to protect from.
            protector = SocketProtector { fd -> runtime.host?.protect(fd) ?: true },
            activity = get(),
            clock = clock(),
        )
        PoolProbes(
            offTunnel::run,
            get(),
            clock(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            transport = { observer.current.value?.transport },
        )
    }
    single {
        LocationListSource(
            nodes = get<NodeRepository>().nodes,
            subscriptions = get<SubscriptionRepository>().subscriptions,
            selection = get<SelectionRepository>().selection,
            stats = get<ProbeStatRepository>()::observe,
            network = get<NetworkObserver>().watch().map { it?.key },
            probing = get<PoolProbes>().probing,
        )
    }
    single<BrainFactory> {
        val koin = getKoin()
        val ownPackage = androidContext().packageName
        BrainFactory { scope, protector, ruleSets ->
            val clock = koin.getOrNull<Clock>() ?: AndroidClock
            val apps = koin.get<InstalledApps>()
            SelectorBrain(
                libbox = InstalledOnlyLibbox(RecordingLibbox(koin.get<LibboxControl>(), koin.get()), ownPackage, apps::isInstalled, apps::installedPackages),
                prober = koin.get(),
                protector = protector,
                planner = koin.getOrNull<TierPlanner>() ?: DefaultTierPlanner(clock),
                detector = koin.get(),
                nodeRepo = koin.get(),
                splitRules = koin.get(),
                settings = koin.get(),
                memory = koin.get(),
                statRepo = koin.get(),
                learning = koin.get(),
                configGen = koin.getOrNull<ConfigGenerator>() ?: ConfigGenerator(),
                clock = clock,
                scope = scope,
                ruleSets = ruleSets,
                selfPackage = ownPackage,
                selectionPort = koin.get<SelectionPort>(),
                activity = koin.get(),
            )
        }
    }
}
