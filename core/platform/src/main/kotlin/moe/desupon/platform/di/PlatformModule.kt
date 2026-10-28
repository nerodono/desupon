package moe.desupon.platform.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import moe.desupon.brain.port.HostProbe
import moe.desupon.brain.port.LibboxControl
import moe.desupon.brain.port.NetModeDetector
import moe.desupon.brain.port.Prober
import moe.desupon.brain.port.SocketProtector
import moe.desupon.platform.libbox.CoreRuntime
import moe.desupon.platform.libbox.LibboxCommand
import moe.desupon.platform.log.CoreLogBuffer
import moe.desupon.platform.log.CoreLogThreshold
import moe.desupon.platform.net.HostListNetModeDetector
import moe.desupon.platform.net.NetModeHostsStore
import moe.desupon.platform.net.NetworkObserver
import moe.desupon.platform.net.SocketHostProbe
import moe.desupon.platform.prober.BridgeProber
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val platformModule = module {
    single { NetworkObserver(androidContext()) }
    single { CoreRuntime(androidContext(), get()) }
    single { LibboxCommand(androidContext(), get()) } bind LibboxControl::class
    single {
        CoreLogBuffer(
            source = get<LibboxCommand>().logs,
            sessions = get<CoreRuntime>()::onAttachChanged,
            threshold = getOrNull<CoreLogThreshold>()?.levels() ?: flowOf(CoreLogBuffer.DEFAULT_THRESHOLD),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            setSourceLevel = get<CoreRuntime>()::setLogLevel,
        )
    }
    single<Prober> { BridgeProber(androidContext(), get(), get()) }
    single { NetModeHostsStore(androidContext()) }
    single<HostProbe> {
        val runtime = get<CoreRuntime>()
        SocketHostProbe(protector = { runtime.host?.let { host -> SocketProtector(host::protect) } })
    }
    single<NetModeDetector> { HostListNetModeDetector(get<NetModeHostsStore>()::load, get()) }
}
