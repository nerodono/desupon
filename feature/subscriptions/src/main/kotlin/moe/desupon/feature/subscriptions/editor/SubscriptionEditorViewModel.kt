package moe.desupon.feature.subscriptions.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.desupon.brain.port.Clock
import moe.desupon.data.repo.EditResult
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionDetails
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.data.repo.UpdateResult
import moe.desupon.feature.subscriptions.Expiry
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.Messages
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.Traffic
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UpdateInterval

internal data class EditorNode(
    val id: NodeId,
    val flag: String?,
    val name: String,
    val protocol: String,
    val supported: Boolean,
    val reason: String?,
    val enabled: Boolean,
)

internal data class SetOption(val id: SetId, val name: String)

/** Live, read-only part of the editor. */
internal data class EditorInfo(
    val id: SubId,
    val isLocal: Boolean,
    val providerTitle: String?,
    val traffic: Traffic?,
    val expiry: Expiry?,
    val expiringSoon: Boolean,
    val expireEpochSec: Long?,
    val lastOkAtMs: Long?,
    val lastAttemptAtMs: Long?,
    val error: UiText?,
    val supportUrl: String?,
    val webPageUrl: String?,
    val announce: String?,
    val globalUserAgent: String,
    val providerIntervalH: Int?,
    val effectiveInterval: UpdateInterval,
    val providerMirrorCount: Int,
    val userSets: List<SetOption>,
    val nodes: List<EditorNode>,
    val nowMs: Long,
    val updating: Boolean,
) {
    val enabledCount: Int get() = nodes.count { it.supported && it.enabled }
}

internal sealed interface EditorEvent {
    data class Message(val text: UiText) : EditorEvent

    data class CopyLink(val url: String) : EditorEvent

    data class ShowQr(val url: String) : EditorEvent
}

/** The full-screen subscription editor; one subscription at a time, opened with [open]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionEditorViewModel internal constructor(
    private val subscriptions: SubscriptionRepository,
    private val nodeRepo: NodeRepository,
    private val selection: SelectionRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _openId = MutableStateFlow<SubId?>(null)
    private val _draft = MutableStateFlow<EditorDraft?>(null)
    private val updating = MutableStateFlow(false)
    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    private var loadJob: Job? = null

    internal val openId: StateFlow<SubId?> = _openId.asStateFlow()

    /** Null while loading. */
    internal val draft: StateFlow<EditorDraft?> = _draft.asStateFlow()
    internal val events: Flow<EditorEvent> = _events.receiveAsFlow()

    internal val info: StateFlow<EditorInfo?> = _openId.flatMapLatest { id ->
        if (id == null) return@flatMapLatest flowOf(null)
        val live = combine(subscriptions.details(id), subscriptions.subscriptions, nodeRepo.nodes, selection.disabledNodes) { d, entries, nodes, disabled ->
            Live(d, entries.find { it.info.id == id }, nodes.filter { id in it.subs }, disabled)
        }
        combine(live, selection.subscriptionSets, updating) { l, sets, busy ->
            val details = l.details ?: return@combine null
            val entry = l.entry
            val reasons = if (l.nodes.any { it.capability == Capability.UNSUPPORTED }) nodeRepo.unsupportedReasons() else emptyMap()
            val now = clock.nowMs()
            val userInfo = entry?.info?.userInfo
            val expiry = Format.expiry(userInfo, now)
            EditorInfo(
                id = id,
                isLocal = details.isLocal,
                providerTitle = details.providerTitle,
                traffic = Format.traffic(userInfo),
                expiry = expiry,
                expiringSoon = Format.expiringSoon(expiry),
                expireEpochSec = userInfo?.takeIf { it.hasExpiry }?.expireEpochSec,
                lastOkAtMs = details.lastOkAtMs,
                lastAttemptAtMs = details.lastAttemptAtMs,
                error = Messages.error(details.lastError),
                supportUrl = entry?.info?.supportUrl?.takeIf(Links::openable),
                webPageUrl = entry?.info?.webPageUrl?.takeIf(Links::openable),
                announce = entry?.info?.announce?.takeIf { it.isNotBlank() },
                globalUserAgent = details.globalUserAgent,
                providerIntervalH = details.providerIntervalH,
                effectiveInterval = details.effectiveInterval,
                providerMirrorCount = details.providerMirrorCount,
                userSets = sets.filterNot { it.isAll }.map { SetOption(it.set.id, it.set.name) },
                nodes = l.nodes.map { n ->
                    val supported = n.capability == Capability.SUPPORTED
                    EditorNode(
                        id = n.id,
                        flag = Format.flag(n.country),
                        name = n.displayName,
                        protocol = Format.protocol(n.protocol),
                        supported = supported,
                        reason = reasons[n.id]?.takeIf { !supported },
                        enabled = n.id !in l.disabled,
                    )
                },
                nowMs = now,
                updating = busy,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Re-opening the subscription already shown keeps its unsaved edits. */
    fun open(id: SubId) {
        if (_openId.value == id) return
        _openId.value = id
        _draft.value = null
        load(id)
    }

    fun close() {
        loadJob?.cancel()
        _openId.value = null
        _draft.value = null
    }

    internal fun edit(change: (EditorDraft) -> EditorDraft) {
        _draft.update { d -> if (d == null || d.saving) d else change(d) }
    }

    fun save() {
        val id = _openId.value ?: return
        val current = _draft.value ?: return
        if (current.saving) return
        val checked = current.validate()
        if (checked != current) {
            _draft.value = checked
            return
        }
        _draft.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                var textUpdate: UpdateResult? = null
                val text = current.form.localText
                if (text != null && text.trimEnd() != current.initial.localText?.trimEnd()) {
                    val r = subscriptions.setLocalText(id, text)
                    if (r !is UpdateResult.Updated) {
                        _draft.update { it?.copy(saving = false, localTextError = Messages.localText(r)) }
                        return@launch
                    }
                    textUpdate = r
                }
                when (val result = subscriptions.save(id, current.form.toEdit(current.providerTitle))) {
                    is EditResult.Saved -> {
                        reload(id)
                        _events.send(EditorEvent.Message(Messages.saved(result.refetch ?: textUpdate)))
                    }
                    EditResult.NotFound -> close()
                    else -> _draft.update { it?.withResult(result, current.form.urls)?.copy(saving = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _draft.update { it?.copy(saving = false) }
                _events.send(EditorEvent.Message(UiText.Res(R.string.subs_editor_save_failed)))
            }
        }
    }

    /** Refreshes from the stored addresses, ignoring unsaved edits. */
    fun refresh() {
        val id = _openId.value ?: return
        if (updating.value) return
        updating.value = true
        viewModelScope.launch {
            try {
                Messages.update(subscriptions.update(id))?.let { _events.send(EditorEvent.Message(it)) }
            } finally {
                updating.value = false
            }
        }
    }

    fun copyLink() = share { EditorEvent.CopyLink(it) }

    fun showQr() = share { EditorEvent.ShowQr(it) }

    fun setNodeEnabled(id: NodeId, enabled: Boolean) {
        viewModelScope.launch { selection.setNodeEnabled(id, enabled) }
    }

    private fun share(event: (String) -> EditorEvent) {
        val id = _openId.value ?: return
        viewModelScope.launch {
            val url = subscriptions.shareUrl(id)
            _events.send(if (url.isNullOrEmpty()) EditorEvent.Message(UiText.Res(R.string.subs_editor_no_link)) else event(url))
        }
    }

    private fun load(id: SubId) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload(id) }
    }

    private suspend fun reload(id: SubId) {
        val details = subscriptions.details(id).first()
        if (_openId.value != id) return
        if (details == null) {
            close()
            return
        }
        val text = if (details.isLocal) subscriptions.localText(id) else null
        if (_openId.value == id) _draft.value = EditorDraft.from(details, text)
    }

    private data class Live(
        val details: SubscriptionDetails?,
        val entry: SubscriptionEntry?,
        val nodes: List<Node>,
        val disabled: Set<NodeId>,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal object Links {
    private val SCHEMES = setOf("http", "https", "tg", "mailto")

    /** Provider links are opened only with a scheme a browser or messenger handles. */
    fun openable(url: String): Boolean {
        val scheme = url.trim().substringBefore(':', "").lowercase()
        return scheme in SCHEMES && url.trim().length > scheme.length + 1
    }
}
