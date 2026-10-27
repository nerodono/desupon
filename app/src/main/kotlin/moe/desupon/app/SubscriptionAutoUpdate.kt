package moe.desupon.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.desupon.data.fetch.FetchTrigger
import moe.desupon.data.repo.SubscriptionRepository

/** Each time the app comes to the foreground, refreshes subscriptions; the repository skips ones not due and throttles the rest. */
class SubscriptionAutoUpdate(
    private val subscriptions: SubscriptionRepository,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    private var running: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        if (running?.isActive == true) return
        running = scope.launch {
            try {
                subscriptions.updateAll(FetchTrigger.AUTO)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
