package moe.desupon.feature.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.chainBoxBackground
import moe.desupon.feature.subscriptions.chain.chainBoxItems
import moe.desupon.feature.subscriptions.chain.rememberChainDragState
import moe.desupon.feature.subscriptions.chain.rememberChainReorderState
import moe.desupon.feature.subscriptions.editor.SubscriptionEditorScreen
import moe.desupon.feature.subscriptions.editor.SubscriptionEditorViewModel
import moe.desupon.feature.subscriptions.sets.SetActions
import moe.desupon.feature.subscriptions.sets.SetCard
import moe.desupon.feature.subscriptions.sets.SetEditorDialog
import moe.desupon.feature.subscriptions.sets.SetsTab
import moe.desupon.feature.subscriptions.sets.SetsViewModel
import moe.desupon.feature.subscriptions.ui.AddSubscriptionDialog
import moe.desupon.feature.subscriptions.ui.CardActions
import moe.desupon.feature.subscriptions.ui.ConfirmDialog
import moe.desupon.feature.subscriptions.ui.DeleteDialog
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.SubscriptionCard
import moe.desupon.feature.subscriptions.ui.displayTitle
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.DesuponGlyphs
import moe.desupon.ui.theme.desuStringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Subscriptions and sets tabs plus the subscription editor.
 * To land on a tab from elsewhere, call [SubscriptionImportRequests.openTab] before navigating here.
 */
@Composable
fun SubscriptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = koinViewModel(),
    setsViewModel: SetsViewModel = koinViewModel(),
    editorViewModel: SubscriptionEditorViewModel = koinViewModel(),
) {
    val editing by editorViewModel.openId.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.tabRequests.collect { if (editorViewModel.draft.value?.dirty != true) editorViewModel.close() }
    }

    val id = editing
    if (id != null) {
        SubscriptionEditorScreen(
            viewModel = editorViewModel,
            id = id,
            onDelete = {
                editorViewModel.close()
                viewModel.delete(it)
            },
            modifier = modifier,
        )
    } else {
        Tabs(viewModel, setsViewModel, onOpen = editorViewModel::open, modifier = modifier)
    }

    draft?.let { d ->
        AddSubscriptionDialog(
            draft = d,
            onTextChange = viewModel::editAdd,
            onError = viewModel::addError,
            onSubmit = viewModel::submitAdd,
            onDismiss = viewModel::dismissAdd,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(
    viewModel: SubscriptionsViewModel,
    setsViewModel: SetsViewModel,
    onOpen: (SubId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val sets by setsViewModel.state.collectAsStateWithLifecycle()
    val setDraft by setsViewModel.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var deleteItem by remember { mutableStateOf<SubscriptionItem?>(null) }
    var deleteSet by remember { mutableStateOf<SetCard?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(setsViewModel) { setsViewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.subs_title)) },
                    actions = { if (tab == SubscriptionsTab.SUBSCRIPTIONS) UpdateAllButton(state, viewModel::updateAll) },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    SubscriptionsTab.entries.forEach { t ->
                        Tab(
                            selected = t == tab,
                            onClick = { viewModel.selectTab(t) },
                            text = { Text(stringResource(t.label())) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                SubscriptionsTab.SUBSCRIPTIONS -> ExtendedFloatingActionButton(
                    onClick = { viewModel.openAdd() },
                    icon = { Icon(SubsIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.subs_add)) },
                )
                SubscriptionsTab.SETS -> ExtendedFloatingActionButton(
                    onClick = setsViewModel::newSet,
                    icon = { Icon(SubsIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.subs_sets_new)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val listPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 88.dp,
        )
        when (tab) {
            SubscriptionsTab.SUBSCRIPTIONS -> SubscriptionList(
                state = state,
                contentPadding = listPadding,
                padding = padding,
                onChain = viewModel::chain,
                actions = { item ->
                    CardActions(
                        onOpen = { onOpen(item.id) },
                        onUpdate = { viewModel.update(item.id) },
                        onDelete = { deleteItem = item },
                        onChain = viewModel::chain,
                    )
                },
            )
            SubscriptionsTab.SETS -> SetsTab(
                state = sets,
                actions = SetActions(
                    onActivate = setsViewModel::activate,
                    onEdit = setsViewModel::edit,
                    onDelete = { deleteSet = it },
                    onMove = setsViewModel::move,
                ),
                contentPadding = listPadding,
            )
        }
    }

    deleteItem?.let { item ->
        DeleteDialog(
            title = item.displayTitle(),
            onConfirm = {
                viewModel.delete(item.id)
                deleteItem = null
            },
            onDismiss = { deleteItem = null },
        )
    }

    deleteSet?.let { set ->
        ConfirmDialog(
            title = stringResource(R.string.subs_sets_delete_title),
            text = stringResource(R.string.subs_sets_delete_text, set.name),
            confirm = stringResource(R.string.subs_delete),
            destructive = true,
            onConfirm = {
                setsViewModel.delete(set.id)
                deleteSet = null
            },
            onDismiss = { deleteSet = null },
        )
    }

    setDraft?.let { d ->
        SetEditorDialog(
            draft = d,
            subscriptions = sets.subscriptions,
            onName = { v -> setsViewModel.editDraft { it.name(v) } },
            onToggle = { s -> setsViewModel.editDraft { it.toggle(s) } },
            onChain = { op -> setsViewModel.editDraft { it.chain(op) } },
            onSave = setsViewModel::saveDraft,
            onDismiss = setsViewModel::dismissDraft,
        )
    }
}

private fun SubscriptionsTab.label(): Int = when (this) {
    SubscriptionsTab.SUBSCRIPTIONS -> R.string.subs_tab_subscriptions
    SubscriptionsTab.SETS -> R.string.subs_tab_sets
}

@Composable
private fun SubscriptionList(
    state: SubscriptionsUiState,
    contentPadding: PaddingValues,
    padding: PaddingValues,
    actions: (SubscriptionItem) -> CardActions,
    onChain: (ChainOp) -> Unit,
) {
    when {
        state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.items.isEmpty() -> EmptyState(Modifier.padding(padding))
        else -> PriorityList(state, contentPadding, actions, onChain)
    }
}

/** Every subscription in one list: the priority box of [SetId.ALL] first, then the rest. */
@Composable
private fun PriorityList(
    state: SubscriptionsUiState,
    contentPadding: PaddingValues,
    actions: (SubscriptionItem) -> CardActions,
    onChain: (ChainOp) -> Unit,
) {
    val drag = rememberChainDragState(state.chain, state.items.map { it.id })
    val listState = rememberLazyListState()
    val reorder = rememberChainReorderState(listState, drag)
    val haptics = LocalHapticFeedback.current
    val byId = remember(state.items) { state.items.associateBy { it.id } }
    val step = state.activeStep
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .chainBoxBackground(listState, drag, fill = colors.secondaryContainer.copy(alpha = 0.35f), border = colors.outlineVariant),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chainBoxItems(drag, reorder, haptics, restCurrent = step == ChainStep.Rest, onDrop = onChain) { row ->
            val item = byId[row.id] ?: return@chainBoxItems
            val current = row.step != null && step is ChainStep.Sub && step.id == row.id
            SubscriptionCard(item = item, actions = actions(item), chain = row, current = current)
        }
    }
}

@Composable
private fun UpdateAllButton(state: SubscriptionsUiState, onClick: () -> Unit) {
    val description = stringResource(R.string.subs_update_all_cd)
    TextButton(
        onClick = onClick,
        enabled = !state.updatingAll && state.items.any { !it.isLocal },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        if (state.updatingAll) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(DesuponGlyphs.WateringCan, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.subs_update_all))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            DesuponGlyphs.Sprout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(desuStringResource(R.string.subs_empty_title, R.string.subs_empty_title_desu), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.subs_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
