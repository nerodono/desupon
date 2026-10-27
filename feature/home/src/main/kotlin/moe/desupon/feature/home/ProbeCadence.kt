package moe.desupon.feature.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/**
 * Probes at once and again after each returned delay, until cancelled; every [restarts] emission probes at once and restarts the wait.
 * [probe] returns the wait so the next request lands when the throttle lets it through instead of one period late.
 */
internal suspend fun probeCadence(restarts: Flow<Unit>, probe: suspend () -> Long) {
    merge(flowOf(Unit), restarts).collectLatest {
        while (true) delay(probe())
    }
}
