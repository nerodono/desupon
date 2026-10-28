package moe.desupon.feature.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Probes while the location sheet is [visible], at once on opening and again after each returned delay.
 * Closing the sheet cancels the wait or pending request; opening it again starts a fresh cadence.
 * [probe] returns the wait so the next request lands when the throttle lets it through instead of one period late.
 */
internal suspend fun probeCadence(visible: Flow<Boolean>, probe: suspend () -> Long) {
    visible.distinctUntilChanged().collectLatest { open ->
        if (open) while (true) delay(probe())
    }
}
