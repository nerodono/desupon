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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.ChainRowUi
import moe.desupon.feature.subscriptions.chain.chainBoxBackground
import moe.desupon.feature.subscriptions.chain.chainBoxItems
import moe.desupon.feature.subscriptions.chain.rememberChainDragState
import moe.desupon.feature.subscriptions.chain.rememberChainReorderState
import moe.desupon.feature.subscriptions.configs.ConfigEditorScreen
import moe.desupon.feature.subscriptions.configs.ConfigEditorViewModel
import moe.desupon.feature.subscriptions.editor.SubscriptionEditorScreen
import moe.desupon.feature.subscriptions.editor.SubscriptionEditorViewModel
import moe.desupon.feature.subscriptions.sets.SetActions
import moe.desupon.feature.subscriptions.sets.SetCard
import moe.desupon.feature.subscriptions.sets.SetEditorDialog
import moe.desupon.feature.subscriptions.sets.SetsTab
import moe.desupon.feature.subscriptions.sets.SetsViewModel
import moe.desupon.feature.subscriptions.ui.AddDialog
import moe.desupon.feature.subscriptions.ui.CardActions
import moe.desupon.feature.subscriptions.ui.ConfigActions
import moe.desupon.feature.subscriptions.ui.ConfigCard
import moe.desupon.feature.subscriptions.ui.ConfirmDialog
import moe.desupon.feature.subscriptions.ui.DeleteDialog
import moe.desupon.feature.subscriptions.ui.QrShareDialog
import moe.desupon.feature.subscriptions.ui.SubsIcons
import moe.desupon.feature.subscriptions.ui.SubscriptionCard
import moe.desupon.feature.subscriptions.ui.copySensitive
import moe.desupon.feature.subscriptions.ui.displayTitle
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.ui.theme.DesuponGlyphs
import moe.desupon.ui.theme.desuStringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Subscriptions, configs and sets tabs plus their editors.
 * To land on a tab from elsewhere, call [SubscriptionImportRequests.openTab] before navigating here.
 */
@Composable
fun SubscriptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: SubscriptionsViewModel = koinViewModel(),
    setsViewModel: SetsViewModel = koinViewModel(),
    editorViewModel: SubscriptionEditorViewModel = koinViewModel(),
    configViewModel: ConfigEditorViewModel = koinViewModel(),
) {
    val editing by editorViewModel.openId.collectAsStateWithLifecycle()
    val configTarget by configViewModel.target.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.tabRequests.collect {
            if (editorViewModel.draft.value?.dirty != true) editorViewModel.close()
            if (configViewModel.draft.value?.dirty != true) configViewModel.close()
        }
    }
    LaunchedEffect(configViewModel) { configViewModel.closed.collect(viewModel::notify) }

    val id = editing
    when {
        configTarget != null -> ConfigEditorScreen(
            viewModel = configViewModel,
            onDelete = {
                configViewModel.close()
                viewModel.delete(it, config = true)
            },
            modifier = modifier,
        )
        id != null -> SubscriptionEditorScreen(
            viewModel = editorViewModel,
            id = id,
            onDelete = {
                editorViewModel.close()
                viewModel.delete(it)
            },
            modifier = modifier,
        )
        else -> Tabs(
            viewModel,
            setsViewModel,
            onOpen = editorViewModel::open,
            onOpenConfig = { item -> if (item.isList) editorViewModel.open(item.id) else configViewModel.open(item.id) },
            modifier = modifier,
        )
    }

    draft?.let { d ->
        AddDialog(
            draft = d,
            onTextChange = viewModel::editAdd,
            onError = viewModel::addError,
            onSubmit = viewModel::submitAdd,
            onSwitch = viewModel::switchAdd,
            onManual = {
                viewModel.dismissAdd()
                configViewModel.openNew()
            },
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
    onOpenConfig: (ConfigItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val sets by setsViewModel.state.collectAsStateWithLifecycle()
    val setDraft by setsViewModel.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    var deleteItem by remember { mutableStateOf<SubscriptionItem?>(null) }
    var deleteConfig by remember { mutableStateOf<ConfigItem?>(null) }
    var deleteSet by remember { mutableStateOf<SetCard?>(null) }
    var qrText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(setsViewModel) { setsViewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { share ->
            if (share.qr) {
                qrText = share.text
            } else {
                copySensitive(context, share.text)
                snackbar.showSnackbar(resources.getString(R.string.subs_config_copied))
            }
        }
    }

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
                    onClick = { viewModel.openAdd(AddTarget.SUBSCRIPTION) },
                    icon = { Icon(SubsIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.subs_add)) },
                )
                SubscriptionsTab.CONFIGS -> ExtendedFloatingActionButton(
                    onClick = { viewModel.openAdd(AddTarget.CONFIG) },
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
            SubscriptionsTab.SUBSCRIPTIONS -> when {
                state.loading -> Loading(padding)
                state.items.isEmpty() -> EmptyState(
                    title = desuStringResource(R.string.subs_empty_title, R.string.subs_empty_title_desu),
                    hint = stringResource(R.string.subs_empty_hint),
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    val byId = remember(state.items) { state.items.associateBy { it.id } }
                    PriorityList(state.chain, state.items.map { it.id }, state.activeStep, configs = false, listPadding, viewModel::chain) { sub, row, current ->
                        val item = byId[sub] ?: return@PriorityList
                        val actions = CardActions(
                            onOpen = { onOpen(item.id) },
                            onUpdate = { viewModel.update(item.id) },
                            onDelete = { deleteItem = item },
                            onChain = viewModel::chain,
                        )
                        SubscriptionCard(item = item, actions = actions, chain = row, current = current)
                    }
                }
            }
            SubscriptionsTab.CONFIGS -> when {
                state.loading -> Loading(padding)
                state.configs.isEmpty() -> EmptyState(
                    title = stringResource(R.string.subs_configs_empty_title),
                    hint = stringResource(R.string.subs_configs_empty_hint),
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    val byId = remember(state.configs) { state.configs.associateBy { it.id } }
                    PriorityList(state.configChain, state.configs.map { it.id }, state.activeStep, configs = true, listPadding, viewModel::configChain) { sub, row, current ->
                        val item = byId[sub] ?: return@PriorityList
                        val actions = ConfigActions(
                            onOpen = { onOpenConfig(item) },
                            onCopy = { viewModel.copyConfig(item.id) },
                            onQr = { viewModel.showConfigQr(item.id) },
                            onDelete = { deleteConfig = item },
                            onChain = viewModel::configChain,
                        )
                        ConfigCard(item = item, actions = actions, chain = row, current = current)
                    }
                }
            }
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

    deleteConfig?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.subs_config_delete_title),
            text = stringResource(R.string.subs_config_delete_text, item.displayTitle()),
            confirm = stringResource(R.string.subs_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(item.id, config = true)
                deleteConfig = null
            },
            onDismiss = { deleteConfig = null },
        )
    }

    qrText?.let { text ->
        QrShareDialog(
            url = text,
            onDismiss = { qrText = null },
            title = stringResource(R.string.subs_config_qr_title),
            warning = stringResource(R.string.subs_config_share_warning),
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
    SubscriptionsTab.CONFIGS -> R.string.subs_tab_configs
    SubscriptionsTab.SETS -> R.string.subs_tab_sets
}

@Composable
private fun Loading(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** [members] in one list: the priority box of [SetId.ALL] first, then the rest; [card] renders one member. */
@Composable
private fun PriorityList(
    chain: List<SubId>,
    members: List<SubId>,
    step: ChainStep?,
    configs: Boolean,
    contentPadding: PaddingValues,
    onChain: (ChainOp) -> Unit,
    card: @Composable (SubId, ChainRowUi, Boolean) -> Unit,
) {
    val drag = rememberChainDragState(chain, members)
    val listState = rememberLazyListState()
    val reorder = rememberChainReorderState(listState, drag)
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .chainBoxBackground(listState, drag, fill = colors.secondaryContainer.copy(alpha = 0.35f), border = colors.outlineVariant),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chainBoxItems(drag, reorder, haptics, restCurrent = step == ChainStep.Rest, onDrop = onChain, configs = configs) { row ->
            val current = row.step != null && step is ChainStep.Sub && step.id == row.id
            card(row.id, row, current)
        }
    }
}

@Composable
private fun UpdateAllButton(state: SubscriptionsUiState, onClick: () -> Unit) {
    val description = stringResource(R.string.subs_update_all_cd)
    TextButton(
        onClick = onClick,
        enabled = !state.updatingAll && state.items.isNotEmpty(),
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
private fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
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
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
