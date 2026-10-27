package moe.desupon.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.ConfigImportResult
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.feature.subscriptions.chain.ChainList
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.apply
import moe.desupon.feature.subscriptions.configs.Endpoint
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionKind

internal data class SubscriptionItem(
    val id: SubId,
    /** Null when neither the user nor the provider named it. */
    val title: String?,
    val isLocal: Boolean,
    val nodeCount: Int,
    val unsupportedCount: Int,
    /** Entries the last successful update could not read (unknown, encrypted or broken); null before the first one. */
    val unreadableCount: Int?,
    val traffic: Traffic?,
    val expiry: Expiry?,
    val expiringSoon: Boolean,
    val error: UiText?,
    val userAgent: String?,
    val updating: Boolean,
    /** User mirrors after the primary address. */
    val mirrorCount: Int,
    /** Names of the user sets containing it, in set order. */
    val setNames: List<String>,
    /** The address is plain http. */
    val cleartext: Boolean,
)

/** A config, or a list pasted in earlier versions, on the configs tab. */
internal data class ConfigItem(
    val id: SubId,
    val title: String?,
    val isList: Boolean,
    /** Of the first node; null without nodes. */
    val protocol: String?,
    val endpoint: String?,
    val flag: String?,
    val nodeCount: Int,
    val unsupportedCount: Int,
    /** Why the only node cannot run, in the parser's words. */
    val unsupportedReason: String?,
    val setNames: List<String>,
)

internal data class SubscriptionsUiState(
    val loading: Boolean = true,
    val items: List<SubscriptionItem> = emptyList(),
    val updatingAll: Boolean = false,
    /** Priority chain of [SetId.ALL]: subscriptions and configs, one order for both tabs. */
    val chain: List<SubId> = emptyList(),
    /** Chain step in use, only while [SetId.ALL] is the active set. */
    val activeStep: ChainStep? = null,
    val configs: List<ConfigItem> = emptyList(),
    /** Configs picked for deletion on the configs tab; null outside selection. */
    val selected: Set<SubId>? = null,
)

internal data class ConfigShare(val text: String, val qr: Boolean)

internal data class AddDraft(
    val text: String = "",
    val target: AddTarget = AddTarget.SUBSCRIPTION,
    val submitting: Boolean = false,
    val error: UiText? = null,
    /** The text belongs to the other dialog; the error offers to move it there. */
    val switchTo: AddTarget? = null,
    /** The text mixes subscriptions and configs; switching adds both. */
    val switchAll: Boolean = false,
    /** So many configs that the dialog asks whether to keep them as one list; null until asked. */
    val bulk: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModel internal constructor(
    private val subscriptions: SubscriptionRepository,
    private val nodeRepo: NodeRepository,
    private val selection: SelectionRepository,
    private val imports: SubscriptionImportRequests,
    private val clock: Clock,
    vpn: VpnControl? = null,
) : ViewModel() {
    private val busy = MutableStateFlow<Set<SubId>>(emptySet())
    private val updatingAll = MutableStateFlow(false)
    private val _tab = MutableStateFlow(SubscriptionsTab.SUBSCRIPTIONS)
    private val _draft = MutableStateFlow<AddDraft?>(null)
    private val _messages = Channel<UiText>(Channel.BUFFERED)
    private val _shares = Channel<ConfigShare>(Channel.BUFFERED)

    /** Add dialog content; null while the dialog is closed. */
    internal val draft: StateFlow<AddDraft?> = _draft.asStateFlow()
    internal val messages: Flow<UiText> = _messages.receiveAsFlow()

    /** A config's text to copy, or to show as a QR code when [ConfigShare.qr]. */
    internal val shares: Flow<ConfigShare> = _shares.receiveAsFlow()
    internal val tab: StateFlow<SubscriptionsTab> = _tab.asStateFlow()

    /** Tabs requested from outside the screen, already applied to [tab]. */
    internal val tabRequests: Flow<SubscriptionsTab> = imports.pendingTab.filterNotNull().onEach { t ->
        _tab.value = t
        imports.consumeTab(t)
    }

    private val allStep: Flow<ChainStep?> = combine(selection.activeSetId, vpn?.activeStep ?: flowOf(null)) { set, step -> step.takeIf { set.isAll } }

    private val reasons: Flow<Map<NodeId, String>> = nodeRepo.nodes.mapLatest { nodes ->
        if (nodes.any { it.capability == Capability.UNSUPPORTED }) nodeRepo.unsupportedReasons() else emptyMap()
    }

    private val list: Flow<SubscriptionsUiState> = combine(
        combine(subscriptions.subscriptions, nodeRepo.nodes, reasons, ::Triple),
        selection.subscriptionSets,
        busy,
        updatingAll,
    ) { (entries, nodes, reasons), sets, busyIds, all ->
        val nodesOf = HashMap<SubId, MutableList<Node>>()
        for (n in nodes) for (s in n.subs) nodesOf.getOrPut(s) { ArrayList() } += n
        val userSets = sets.filterNot { it.isAll }
        val setNames = { e: SubscriptionEntry -> userSets.filter { s -> s.set.id in e.sets }.map { s -> s.set.name } }
        val now = clock.nowMs()
        val (remote, local) = entries.partition { it.kind == SubscriptionKind.SUBSCRIPTION }
        val items = remote.map { e ->
            val unsupported = nodesOf[e.info.id].orEmpty().count { it.capability == Capability.UNSUPPORTED }
            e.toItem(unsupported, e.info.id in busyIds, now, setNames(e))
        }
        val configs = local.map { e -> e.toConfig(nodesOf[e.info.id].orEmpty(), reasons, setNames(e)) }
        val chain = sets.firstOrNull { it.isAll }?.set?.chain.orEmpty()
        val known = entries.mapTo(HashSet()) { it.info.id }
        SubscriptionsUiState(
            loading = false,
            items = items,
            updatingAll = all,
            chain = chain.filter { it in known },
            configs = configs,
        )
    }.flowOn(Dispatchers.Default)

    private val selected = MutableStateFlow<Set<SubId>?>(null)

    internal val state: StateFlow<SubscriptionsUiState> = combine(list, allStep, selected) { s, step, picked ->
        val ids = s.configs.mapTo(HashSet()) { it.id }
        s.copy(activeStep = step, selected = picked?.filterTo(HashSet()) { it in ids })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState())

    init {
        viewModelScope.launch {
            imports.pending.filterNotNull().collect { request ->
                if (_draft.value?.submitting == true) return@collect
                _draft.value = AddDraft(text = request.text, target = request.target)
                _tab.value = if (request.target == AddTarget.CONFIG) SubscriptionsTab.CONFIGS else SubscriptionsTab.SUBSCRIPTIONS
                imports.consume(request)
            }
        }
    }

    fun openAdd(target: AddTarget = AddTarget.SUBSCRIPTION, text: String = "") {
        if (_draft.value?.submitting == true) return
        _draft.value = AddDraft(text = text, target = target)
    }

    fun editAdd(text: String) {
        _draft.update { it?.copy(text = text, error = null, switchTo = null, bulk = null) }
    }

    internal fun addError(error: UiText) {
        _draft.update { it?.copy(error = error, switchTo = null, bulk = null) }
    }

    /** Moves the text to the dialog the error pointed at. */
    fun switchAdd() {
        val d = _draft.value ?: return
        val to = d.switchTo ?: return
        if (d.submitting) return
        _draft.value = d.copy(target = to, error = null, switchTo = null, bulk = null)
        _tab.value = if (to == AddTarget.CONFIG) SubscriptionsTab.CONFIGS else SubscriptionsTab.SUBSCRIPTIONS
    }

    fun dismissAdd() {
        if (_draft.value?.submitting != true) _draft.value = null
    }

    /**
     * Adds the dialog's text. Configs past [BULK_LIMIT] first ask whether to keep them as one list: [asList] is that
     * answer, null before it was given.
     */
    fun submitAdd(asList: Boolean? = null) {
        val current = _draft.value ?: return
        if (current.submitting || current.text.isBlank()) return
        _draft.value = current.copy(submitting = true, error = null, switchTo = null)
        viewModelScope.launch {
            val outcome = runCatching {
                when (current.target) {
                    AddTarget.SUBSCRIPTION -> subscriptionOutcome(subscriptions.addSubscriptions(current.text))
                    AddTarget.CONFIG -> {
                        val bulk = if (asList == null) subscriptions.preview(current.text).configs.takeIf { it > BULK_LIMIT } else null
                        if (bulk != null) {
                            AddOutcome.Ask(bulk)
                        } else {
                            configOutcome(subscriptions.importConfigs(current.text, asList = asList == true))
                        }
                    }
                }
            }.getOrElse { AddOutcome.Error(UiText.Res(R.string.subs_error_unknown)) }
            when (outcome) {
                is AddOutcome.Done -> {
                    _draft.value = null
                    _messages.send(outcome.message)
                }
                is AddOutcome.Error -> _draft.update {
                    it?.copy(submitting = false, error = outcome.message, switchTo = outcome.switchTo, switchAll = outcome.all)
                }
                is AddOutcome.Ask -> _draft.update { it?.copy(submitting = false, bulk = outcome.configs) }
            }
        }
    }

    private sealed interface AddOutcome {
        data class Done(val message: UiText) : AddOutcome

        data class Error(val message: UiText, val switchTo: AddTarget? = null, val all: Boolean = false) : AddOutcome

        data class Ask(val configs: Int) : AddOutcome
    }

    private fun subscriptionOutcome(result: AddResult): AddOutcome = when (result) {
        is AddResult.Added -> AddOutcome.Done(Messages.add(result))
        is AddResult.LooksLikeConfigs -> AddOutcome.Error(Messages.add(result), AddTarget.CONFIG, all = result.urls > 0)
        AddResult.NothingFound, is AddResult.AlreadyExists -> AddOutcome.Error(Messages.add(result))
    }

    private fun configOutcome(result: ConfigImportResult): AddOutcome = when (result) {
        is ConfigImportResult.Imported -> if (result.ids.isEmpty() && result.subscriptions.isEmpty()) {
            AddOutcome.Error(Messages.configs(result))
        } else {
            AddOutcome.Done(Messages.configs(result))
        }
        is ConfigImportResult.Listed -> AddOutcome.Done(Messages.configs(result))
        ConfigImportResult.SubscriptionUrls -> AddOutcome.Error(Messages.configs(result), AddTarget.SUBSCRIPTION)
        is ConfigImportResult.NothingFound -> AddOutcome.Error(Messages.configs(result))
    }

    fun update(id: SubId) {
        if (id in busy.value) return
        busy.update { it + id }
        viewModelScope.launch {
            try {
                val result = subscriptions.update(id)
                Messages.update(result)?.let { _messages.send(it) }
            } finally {
                busy.update { it - id }
            }
        }
    }

    /** «Полить сад»: refresh every remote subscription. */
    fun updateAll() {
        if (updatingAll.value) return
        updatingAll.value = true
        viewModelScope.launch {
            try {
                val results = subscriptions.updateAll()
                _messages.send(Messages.updateAll(results))
            } finally {
                updatingAll.value = false
            }
        }
    }

    /** The snackbar names what [id] was, a subscription, a pasted list or a config. */
    fun delete(id: SubId) {
        val s = state.value
        val message = when {
            s.items.any { it.id == id } -> R.string.subs_deleted
            s.configs.firstOrNull { it.id == id }?.isList == true -> R.string.subs_list_deleted
            s.configs.any { it.id == id } -> R.string.subs_config_deleted
            else -> R.string.subs_deleted
        }
        viewModelScope.launch {
            subscriptions.delete(id)
            _messages.send(UiText.Res(message))
        }
    }

    /** Starts picking configs for deletion, with [first] already picked. */
    fun startSelection(first: SubId? = null) {
        _tab.value = SubscriptionsTab.CONFIGS
        selected.value = setOfNotNull(first)
    }

    fun toggleSelected(id: SubId) {
        selected.update { picked -> picked?.let { if (id in it) it - id else it + id } }
    }

    fun endSelection() {
        selected.value = null
    }

    fun deleteSelected() {
        val ids = state.value.selected.orEmpty()
        selected.value = null
        if (ids.isEmpty()) return
        viewModelScope.launch {
            subscriptions.delete(ids)
            _messages.send(UiText.Plural(R.plurals.subs_configs_deleted, ids.size))
        }
    }

    /** Shows [text] in the snackbar, e.g. for an editor that just closed. */
    internal fun notify(text: UiText) {
        _messages.trySend(text)
    }

    fun copyConfig(id: SubId) = shareConfig(id, qr = false)

    fun showConfigQr(id: SubId) = shareConfig(id, qr = true)

    private fun shareConfig(id: SubId, qr: Boolean) {
        viewModelScope.launch { subscriptions.configSource(id)?.let { _shares.send(ConfigShare(it, qr)) } }
    }

    /** Edits the priority chain of [SetId.ALL], which both tabs show whole. */
    internal fun chain(op: ChainOp) {
        val s = state.value
        val shown = s.items.mapTo(HashSet()) { it.id }.apply { s.configs.forEach { add(it.id) } }
        viewModelScope.launch {
            ChainList.toFull(op, selection.chain(SetId.ALL), shown)?.let { selection.apply(SetId.ALL, it) }
        }
    }

    fun selectTab(tab: SubscriptionsTab) {
        _tab.value = tab
        if (tab != SubscriptionsTab.CONFIGS) selected.value = null
    }

    private fun SubscriptionEntry.toItem(unsupported: Int, updating: Boolean, nowMs: Long, setNames: List<String>): SubscriptionItem {
        val expiry = Format.expiry(info.userInfo, nowMs)
        return SubscriptionItem(
            id = info.id,
            title = info.title?.takeIf { it.isNotBlank() },
            isLocal = isLocal,
            nodeCount = nodeCount,
            unsupportedCount = unsupported,
            unreadableCount = skippedCount,
            traffic = Format.traffic(info.userInfo),
            expiry = expiry,
            expiringSoon = Format.expiringSoon(expiry),
            error = Messages.error(info.lastError),
            userAgent = userAgent,
            updating = updating,
            mirrorCount = mirrorCount,
            setNames = setNames,
            cleartext = !isLocal && Format.cleartext(info.url.raw()),
        )
    }

    private fun SubscriptionEntry.toConfig(nodes: List<Node>, reasons: Map<NodeId, String>, setNames: List<String>): ConfigItem {
        val first = nodes.firstOrNull()
        val unsupported = nodes.filter { it.capability == Capability.UNSUPPORTED }
        return ConfigItem(
            id = info.id,
            title = info.title?.takeIf { it.isNotBlank() },
            isList = kind == SubscriptionKind.LIST,
            protocol = first?.let { Format.protocol(it.protocol) },
            endpoint = first?.let { Endpoint.of(it.outboundJson.raw()) },
            flag = Format.flag(first?.country),
            nodeCount = nodes.size,
            unsupportedCount = unsupported.size,
            unsupportedReason = unsupported.singleOrNull()?.takeIf { nodes.size == 1 }?.let { reasons[it.id] },
            setNames = setNames,
        )
    }

    internal companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** More configs than this in one paste are offered as a single list. */
        const val BULK_LIMIT = 5
    }
}
