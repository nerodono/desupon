package moe.desupon.feature.settings.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import moe.desupon.data.settings.LogVerbosity
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.platform.log.CoreLogBuffer
import moe.desupon.platform.log.LogLine

data class CoreLogUiState(
    val lines: List<LogLine> = emptyList(),
    /** Lines kept before filtering. */
    val total: Int = 0,
    val show: LogShow = LogShow.ALL,
    val verbosity: LogVerbosity = LogVerbosity.WARNINGS,
)

class CoreLogViewModel(private val buffer: CoreLogBuffer, settings: SettingsRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val show = MutableStateFlow(LogShow.ALL)

    // A busy core logs hundreds of lines a second; versions conflate while this waits, so the list redraws a few times a second.
    private val kept = buffer.version
        .onEach { delay(REDRAW_MS) }
        .map { buffer.snapshot() }

    val state: StateFlow<CoreLogUiState> = combine(kept, query, show, settings.logVerbosity) { all, q, s, v ->
        CoreLogUiState(LogFilter.apply(all, q, s), all.size, s, v)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), buffer.snapshot().let { CoreLogUiState(it, it.size) })

    fun setQuery(value: String) {
        query.value = value
    }

    fun setShow(value: LogShow) {
        show.value = value
    }

    fun clear() = buffer.clear()

    private companion object {
        const val REDRAW_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
