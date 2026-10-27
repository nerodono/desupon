package moe.desupon.feature.subscriptions.sets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId

internal data class SetCard(
    val id: SetId,
    /** Empty for [SetId.ALL]. */
    val name: String,
    val members: Set<SubId>,
    val chain: List<SubId>,
    /** [chain] resolved to subscriptions, for the summary line. */
    val chainMembers: List<MemberOption>,
    val subscriptionCount: Int,
    val supportedNodeCount: Int,
    val enabledNodeCount: Int,
    val active: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
) {
    val isAll: Boolean get() = id.isAll
}

internal data class MemberOption(val id: SubId, val title: String?, val isLocal: Boolean, val nodeCount: Int)

internal data class SetsUiState(
    val loading: Boolean = true,
    val sets: List<SetCard> = emptyList(),
    val subscriptions: List<MemberOption> = emptyList(),
)

class SetsViewModel internal constructor(
    private val selection: SelectionRepository,
    subscriptions: SubscriptionRepository,
) : ViewModel() {
    private val _draft = MutableStateFlow<SetDraft?>(null)
    private val _messages = Channel<UiText>(Channel.BUFFERED)

    internal val draft: StateFlow<SetDraft?> = _draft.asStateFlow()
    internal val messages: Flow<UiText> = _messages.receiveAsFlow()

    internal val state: StateFlow<SetsUiState> = combine(selection.subscriptionSets, subscriptions.subscriptions) { sets, subs ->
        val options = subs.map { MemberOption(it.info.id, it.info.title?.takeIf(String::isNotBlank), it.isLocal, it.nodeCount) }
        val byId = options.associateBy { it.id }
        val userCount = sets.count { !it.isAll }
        var userIndex = 0
        SetsUiState(
            loading = false,
            sets = sets.map { e ->
                val index = if (e.isAll) -1 else userIndex++
                SetCard(
                    id = e.set.id,
                    name = e.set.name,
                    members = e.set.members,
                    chain = e.set.chain,
                    chainMembers = e.set.chain.mapNotNull(byId::get),
                    subscriptionCount = e.subscriptionCount,
                    supportedNodeCount = e.supportedNodeCount,
                    enabledNodeCount = e.enabledNodeCount,
                    active = e.isActive,
                    canMoveUp = index > 0,
                    canMoveDown = index in 0 until userCount - 1,
                )
            },
            subscriptions = options,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SetsUiState())

    fun activate(id: SetId) {
        viewModelScope.launch { selection.setActiveSet(id) }
    }

    fun newSet() {
        _draft.value = SetDraft(id = null)
    }

    /** For [SetId.ALL] only its chain is editable. */
    fun edit(id: SetId) {
        val set = state.value.sets.firstOrNull { it.id == id } ?: return
        _draft.value = SetDraft(id = id, name = set.name, members = set.members, chain = set.chain.filter { it in set.members })
    }

    internal fun editDraft(change: (SetDraft) -> SetDraft) {
        _draft.update { d -> if (d == null || d.saving) d else change(d) }
    }

    fun dismissDraft() {
        if (_draft.value?.saving != true) _draft.value = null
    }

    fun saveDraft() {
        val d = _draft.value ?: return
        if (d.saving) return
        val others = state.value.sets.filter { !it.isAll && it.id != d.id }.map { it.name }
        d.validate(others)?.let { error ->
            _draft.value = d.copy(error = error)
            return
        }
        _draft.value = d.copy(saving = true)
        viewModelScope.launch {
            val known = state.value.subscriptions.mapTo(HashSet()) { it.id }
            val chain = d.chain.filter { it in d.members && it in known }
            val ok = when {
                d.id == null -> selection.createSet(d.name, d.members)?.let { id ->
                    // A retry after a failed chain write must not create the set twice.
                    _draft.update { it?.copy(id = id) }
                    selection.setChain(id, chain)
                } ?: false
                d.priorityOnly -> selection.setChain(SetId.ALL, chain)
                else -> selection.renameSet(d.id, d.name) && selection.setMembers(d.id, d.members) && selection.setChain(d.id, chain)
            }
            if (ok) {
                _draft.value = null
            } else {
                _draft.update { it?.copy(saving = false) }
                _messages.send(UiText.Res(R.string.subs_sets_save_failed))
            }
        }
    }

    fun delete(id: SetId) {
        if (id.isAll) return
        viewModelScope.launch {
            if (selection.deleteSet(id)) _messages.send(UiText.Res(R.string.subs_sets_deleted))
        }
    }

    fun move(id: SetId, delta: Int) {
        val ids = state.value.sets.filterNot { it.isAll }.map { it.id }
        val order = SetOrder.move(ids, id, delta) ?: return
        viewModelScope.launch { selection.reorderSets(order) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
