package moe.desupon.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.ConfigImportResult
import moe.desupon.data.repo.SubscriptionRepository
import org.koin.compose.viewmodel.koinViewModel

internal sealed interface SimpleImportState {
    data object Hidden : SimpleImportState

    /** [configs] servers to add, or a subscription when zero. */
    data class Ask(val request: AddRequest, val configs: Int) : SimpleImportState

    /** The shared text held neither a subscription nor a server. */
    data class NothingFound(val request: AddRequest) : SimpleImportState

    data object Working : SimpleImportState

    data class Done(val ok: Boolean) : SimpleImportState
}

/** Shared text in simple mode: one question, one tap, one result, with no screens to find. */
class SimpleImportViewModel internal constructor(
    private val imports: SubscriptionImportRequests,
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<SimpleImportState>(SimpleImportState.Hidden)
    internal val state: StateFlow<SimpleImportState> = _state.asStateFlow()

    /**
     * Picks up shared text while the prompt is shown. A question left unanswered when it leaves the screen is dropped
     * from the prompt but stays pending, so the full interface opens it instead.
     */
    internal suspend fun watch() {
        try {
            imports.pending.filterNotNull().collect { request ->
                if (request.text.isBlank()) {
                    imports.consume(request)
                    return@collect
                }
                if (_state.value != SimpleImportState.Hidden) return@collect
                val preview = try {
                    subscriptions.preview(request.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                _state.value = when {
                    preview == null -> SimpleImportState.NothingFound(request)
                    preview.configs > 0 -> SimpleImportState.Ask(request, preview.configs)
                    preview.urls > 0 -> SimpleImportState.Ask(request, configs = 0)
                    else -> SimpleImportState.NothingFound(request)
                }
            }
        } finally {
            if (_state.value is SimpleImportState.Ask || _state.value is SimpleImportState.NothingFound) _state.value = SimpleImportState.Hidden
        }
    }

    fun confirm() {
        val ask = _state.value as? SimpleImportState.Ask ?: return
        imports.consume(ask.request)
        _state.value = SimpleImportState.Working
        viewModelScope.launch {
            val ok = try {
                if (ask.configs > 0) {
                    when (val r = subscriptions.importConfigs(ask.request.text)) {
                        is ConfigImportResult.Imported -> r.ids.isNotEmpty() || r.duplicates > 0 || r.subscriptions.isNotEmpty()
                        is ConfigImportResult.Listed -> true
                        else -> false
                    }
                } else {
                    when (subscriptions.addSubscriptions(ask.request.text)) {
                        is AddResult.Added, is AddResult.AlreadyExists -> true
                        else -> false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _state.value = SimpleImportState.Done(ok)
        }
    }

    fun dismiss() {
        when (val s = _state.value) {
            is SimpleImportState.Ask -> imports.consume(s.request)
            is SimpleImportState.NothingFound -> imports.consume(s.request)
            SimpleImportState.Working -> return
            else -> Unit
        }
        _state.value = SimpleImportState.Hidden
    }
}

/** Put on simple mode's screen; shows nothing until something is shared to the app. */
@Composable
fun SimpleImportPrompt(viewModel: SimpleImportViewModel = koinViewModel()) {
    LaunchedEffect(viewModel) { viewModel.watch() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        SimpleImportState.Hidden -> Unit
        is SimpleImportState.Ask -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = {
                Text(
                    if (s.configs > 0) {
                        pluralStringResource(R.plurals.simple_import_configs_title, s.configs, s.configs)
                    } else {
                        stringResource(R.string.simple_import_subscription_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = { Text(stringResource(R.string.simple_import_text), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = viewModel::confirm) { Text(stringResource(R.string.simple_import_add)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.simple_import_cancel)) } },
        )
        is SimpleImportState.NothingFound -> ResultDialog(stringResource(R.string.simple_import_nothing), viewModel::dismiss)
        SimpleImportState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(stringResource(R.string.simple_import_working), style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {},
        )
        is SimpleImportState.Done -> ResultDialog(
            stringResource(if (s.ok) R.string.simple_import_done else R.string.simple_import_failed),
            viewModel::dismiss,
        )
    }
}

@Composable
private fun ResultDialog(text: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        text = { Column { Text(text, style = MaterialTheme.typography.bodyLarge) } },
        confirmButton = { Button(onClick = onOk) { Text(stringResource(R.string.simple_import_ok)) } },
    )
}
