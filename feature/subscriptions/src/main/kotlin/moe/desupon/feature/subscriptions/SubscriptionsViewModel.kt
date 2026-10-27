package moe.desupon.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.VpnControl
import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.selection.SetId
import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.feature.subscriptions.chain.apply
import moe.desupon.model.subscription.SubId

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

internal data class SubscriptionsUiState(
    val loading: Boolean = true,
    val items: List<SubscriptionItem> = emptyList(),
    val updatingAll: Boolean = false,
    /** Priority chain of [SetId.ALL], limited to [items]. */
    val chain: List<SubId> = emptyList(),
    /** Chain step in use, only while [SetId.ALL] is the active set. */
    val activeStep: ChainStep? = null,
)

internal data class AddDraft(
    val text: String = "",
    val submitting: Boolean = false,
    val error: UiText? = null,
)

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

    /** Add dialog content; null while the dialog is closed. */
    internal val draft: StateFlow<AddDraft?> = _draft.asStateFlow()
    internal val messages: Flow<UiText> = _messages.receiveAsFlow()
    internal val tab: StateFlow<SubscriptionsTab> = _tab.asStateFlow()

    /** Tabs requested from outside the screen, already applied to [tab]. */
    internal val tabRequests: Flow<SubscriptionsTab> = imports.pendingTab.filterNotNull().onEach { t ->
        _tab.value = t
        imports.consumeTab(t)
    }

    private val allStep: Flow<ChainStep?> = combine(selection.activeSetId, vpn?.activeStep ?: flowOf(null)) { set, step -> step.takeIf { set.isAll } }

    private val list: Flow<SubscriptionsUiState> = combine(
        subscriptions.subscriptions,
        nodeRepo.nodes,
        selection.subscriptionSets,
        busy,
        updatingAll,
    ) { entries, nodes, sets, busyIds, all ->
        val unsupported = unsupportedCounts(nodes)
        val userSets = sets.filterNot { it.isAll }
        val now = clock.nowMs()
        val items = entries.map { it.toItem(unsupported[it.info.id] ?: 0, it.info.id in busyIds, now, userSets.filter { s -> s.set.id in it.sets }.map { s -> s.set.name }) }
        val ids = items.mapTo(HashSet()) { it.id }
        SubscriptionsUiState(
            loading = false,
            items = items,
            updatingAll = all,
            chain = sets.firstOrNull { it.isAll }?.set?.chain.orEmpty().filter { it in ids },
        )
    }

    internal val state: StateFlow<SubscriptionsUiState> = combine(list, allStep) { s, step -> s.copy(activeStep = step) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SubscriptionsUiState())

    init {
        viewModelScope.launch {
            imports.pending.filterNotNull().collect { text ->
                if (_draft.value?.submitting == true) return@collect
                _draft.value = AddDraft(text = text)
                _tab.value = SubscriptionsTab.SUBSCRIPTIONS
                imports.consume(text)
            }
        }
    }

    fun openAdd(text: String = "") {
        if (_draft.value?.submitting == true) return
        _draft.value = AddDraft(text = text)
    }

    fun editAdd(text: String) {
        _draft.update { it?.copy(text = text, error = null) }
    }

    internal fun addError(error: UiText) {
        _draft.update { it?.copy(error = error) }
    }

    fun dismissAdd() {
        if (_draft.value?.submitting != true) _draft.value = null
    }

    fun submitAdd() {
        val current = _draft.value ?: return
        if (current.submitting || current.text.isBlank()) return
        _draft.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val result = runCatching { subscriptions.addFromText(current.text) }.getOrElse {
                _draft.update { d -> d?.copy(submitting = false, error = UiText.Res(R.string.subs_error_unknown)) }
                return@launch
            }
            when (result) {
                AddResult.NothingFound, is AddResult.AlreadyExists ->
                    _draft.update { it?.copy(submitting = false, error = Messages.add(result)) }
                is AddResult.Added, is AddResult.Imported -> {
                    _draft.value = null
                    _messages.send(Messages.add(result))
                }
            }
        }
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

    fun delete(id: SubId) {
        viewModelScope.launch {
            subscriptions.delete(id)
            _messages.send(UiText.Res(R.string.subs_deleted))
        }
    }

    /** Edits the priority chain of every subscription. */
    internal fun chain(op: ChainOp) {
        viewModelScope.launch { selection.apply(SetId.ALL, op) }
    }

    fun selectTab(tab: SubscriptionsTab) {
        _tab.value = tab
    }

    private fun unsupportedCounts(nodes: List<Node>): Map<SubId, Int> {
        val out = HashMap<SubId, Int>()
        for (n in nodes) if (n.capability == Capability.UNSUPPORTED) for (s in n.subs) out[s] = (out[s] ?: 0) + 1
        return out
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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
