/*
 * Portions Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package moe.desupon.platform.iface

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.desupon.core.libbox.ExchangeContext
import moe.desupon.core.libbox.LocalDNSTransport
import moe.desupon.platform.net.NetworkObserver
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The core's `local` DNS: the system resolver of the underlying network, never the tunnel. */
@Suppress("DEPRECATION")
internal class LocalResolver(private val observer: NetworkObserver) : LocalDNSTransport {
    override fun raw(): Boolean = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = observer.current.value?.network ?: error("missing default interface")
        runBlocking {
            suspendCancellableCoroutine { cont ->
                val once = AtomicBoolean()
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    if (once.compareAndSet(false, true)) cont.resumeWithException(CancellationException())
                }
                DnsResolver.getInstance().rawQuery(
                    network,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                            if (once.compareAndSet(false, true)) cont.resume(Unit)
                        }

                        override fun onError(error: DnsResolver.DnsException) = finish(ctx, cont, once, error)
                    },
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val underlying = observer.current.value?.network ?: error("missing default interface")
        runBlocking {
            suspendCancellableCoroutine { cont ->
                val once = AtomicBoolean()
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    if (once.compareAndSet(false, true)) cont.resumeWithException(CancellationException())
                }
                val callback = object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                        if (once.compareAndSet(false, true)) cont.resume(Unit)
                    }

                    override fun onError(error: DnsResolver.DnsException) = finish(ctx, cont, once, error)
                }
                val type = when {
                    network.endsWith("4") -> DnsResolver.TYPE_A
                    network.endsWith("6") -> DnsResolver.TYPE_AAAA
                    else -> null
                }
                val executor = Dispatchers.IO.asExecutor()
                if (type != null) {
                    DnsResolver.getInstance().query(underlying, domain, type, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
                } else {
                    DnsResolver.getInstance().query(underlying, domain, DnsResolver.FLAG_NO_RETRY, executor, signal, callback)
                }
            }
        }
    }

    private fun finish(
        ctx: ExchangeContext,
        cont: kotlinx.coroutines.CancellableContinuation<Unit>,
        once: AtomicBoolean,
        error: DnsResolver.DnsException,
    ) {
        if (!once.compareAndSet(false, true)) return
        val cause = error.cause
        if (cause is ErrnoException) {
            ctx.errnoCode(cause.errno)
            cont.resume(Unit)
        } else {
            cont.resumeWithException(error)
        }
    }
}
