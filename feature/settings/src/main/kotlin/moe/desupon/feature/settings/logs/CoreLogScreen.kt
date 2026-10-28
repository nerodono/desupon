package moe.desupon.feature.settings.logs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.desupon.data.settings.LogVerbosity
import moe.desupon.feature.settings.R
import moe.desupon.feature.settings.ui.SettingsGlyphs
import moe.desupon.platform.log.CoreLogLevel
import moe.desupon.platform.log.LogLine
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The core log kept by [moe.desupon.platform.log.CoreLogBuffer]; follows new lines until the user scrolls up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreLogScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CoreLogViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Kept here so typing never waits for the view model; restored text is handed back to it.
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { viewModel.setQuery(query) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copied = stringResource(R.string.config_copied)
    val failed = stringResource(R.string.logs_export_failed)
    val started = stringResource(R.string.logs_session_started)
    val stopped = stringResource(R.string.logs_session_stopped)
    val truncated = stringResource(R.string.logs_truncated)
    val subject = stringResource(R.string.logs_share_subject)
    val export = { LogExport.text(state.lines, { if (it) started else stopped }, { truncated.format(it) }) }

    val list = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(list) {
        // On the drag itself: waiting for the scroll to end would let the next line's jump cancel a fling.
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(list) {
        var scrolling = false
        snapshotFlow { list.isScrollInProgress }.collect { now ->
            if (scrolling && !now) follow = TailFollow.afterScroll(follow, list.layoutInfo.totalItemsCount > 0, !list.canScrollForward)
            scrolling = now
        }
    }
    val last = state.lines.lastOrNull()?.seq
    LaunchedEffect(last, follow) {
        if (follow && state.lines.isNotEmpty()) list.scrollToItem(state.lines.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val any = state.lines.isNotEmpty()
                    IconButton(onClick = { if (!context.shareText(export(), subject)) scope.launch { snackbar.showSnackbar(failed) } }, enabled = any) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_copy)) },
                                enabled = any,
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val done = try {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", export())))
                                            true
                                        } catch (_: RuntimeException) {
                                            false
                                        }
                                        snackbar.showSnackbar(if (done) copied else failed)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_clear)) },
                                enabled = state.total > 0,
                                onClick = {
                                    menu = false
                                    viewModel.clear()
                                    follow = true
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!follow && state.lines.isNotEmpty()) {
                SmallFloatingActionButton(onClick = {
                    follow = true
                    scope.launch { list.scrollToItem(state.lines.lastIndex) }
                }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.logs_to_end_cd))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Filters(query, state.show, onQuery = { query = it }, onShow = viewModel::setShow)
            Box(Modifier.fillMaxSize()) {
                when {
                    state.total == 0 -> Empty(
                        title = stringResource(R.string.logs_empty),
                        hint = stringResource(R.string.logs_empty_hint, verbosityLabel(state.verbosity)),
                    )
                    state.lines.isEmpty() -> Empty(title = stringResource(R.string.logs_nothing_found), hint = null)
                    else -> SelectionContainer {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            items(state.lines, key = { it.seq }) { line ->
                                when (line) {
                                    is LogLine.Entry -> EntryRow(line)
                                    is LogLine.Session -> SessionRow(line, if (line.started) started else stopped)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(query: String, show: LogShow, onQuery: (String) -> Unit, onShow: (LogShow) -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.logs_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.logs_search_clear_cd))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogShow.entries.forEach { s ->
                FilterChip(selected = s == show, onClick = { onShow(s) }, label = { Text(stringResource(s.label())) })
            }
        }
    }
}

private fun LogShow.label(): Int = when (this) {
    LogShow.ALL -> R.string.logs_show_all
    LogShow.WARNINGS -> R.string.logs_show_warnings
    LogShow.ERRORS -> R.string.logs_show_errors
}

@Composable
internal fun verbosityLabel(v: LogVerbosity): String = stringResource(
    when (v) {
        LogVerbosity.ERRORS -> R.string.settings_logs_level_errors
        LogVerbosity.WARNINGS -> R.string.settings_logs_level_warnings
        LogVerbosity.INFO -> R.string.settings_logs_level_info
        LogVerbosity.DEBUG -> R.string.settings_logs_level_debug
    },
)

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(atMs: Long): String = CLOCK.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

@Composable
private fun EntryRow(line: LogLine.Entry) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(clock(line.atMs), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line.text, style = mono, color = levelColor(line.level), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun levelColor(level: CoreLogLevel): Color = when {
    level.core <= CoreLogLevel.ERROR.core -> MaterialTheme.colorScheme.error
    level == CoreLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    level.core <= CoreLogLevel.INFO.core -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SessionRow(line: LogLine.Session, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "${clock(line.atMs)}  $label",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun Empty(title: String, hint: String?) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                SettingsGlyphs.Logs,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** False when the system refused the hand-off, e.g. no app takes text or the transaction was too large. */
private fun Context.shareText(text: String, subject: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    return try {
        startActivity(Intent.createChooser(send, null))
        true
    } catch (_: RuntimeException) {
        false
    }
}
