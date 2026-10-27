package moe.desupon.feature.subscriptions.configs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.desupon.data.repo.ConfigSaveResult
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.data.repo.SubscriptionRepository
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.Messages
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.feature.subscriptions.editor.EditorNode
import moe.desupon.feature.subscriptions.editor.SetOption
import moe.desupon.model.node.Capability
import moe.desupon.model.node.NodeId
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.ConfigParser

internal sealed interface ConfigTarget {
    data object New : ConfigTarget

    data class Edit(val id: SubId) : ConfigTarget
}

/** One node the text currently reads to. */
internal data class PreviewNode(
    val flag: String?,
    val name: String,
    val protocol: String,
    val endpoint: String?,
    val reason: String?,
    val supported: Boolean,
)

/** Live reading of the text being edited; [error] is set when it reads to nothing. */
internal data class ConfigPreview(val nodes: List<PreviewNode>, val error: UiText?)

/** Live, read-only part of the editor. */
internal data class ConfigInfo(
    val title: String?,
    val nodes: List<EditorNode>,
    val userSets: List<SetOption>,
)

internal sealed interface ConfigEvent {
    data class Message(val text: UiText) : ConfigEvent

    data class Copy(val text: String) : ConfigEvent

    data class ShowQr(val text: String) : ConfigEvent
}

/** The full-screen config editor, for a new config or a saved one. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ConfigEditorViewModel internal constructor(
    private val subscriptions: SubscriptionRepository,
    private val nodeRepo: NodeRepository,
    private val selection: SelectionRepository,
) : ViewModel() {
    private val _target = MutableStateFlow<ConfigTarget?>(null)
    private val _draft = MutableStateFlow<ConfigDraft?>(null)
    private val _events = Channel<ConfigEvent>(Channel.BUFFERED)
    private val _closed = Channel<UiText>(Channel.BUFFERED)
    private var loadJob: Job? = null

    internal val target: StateFlow<ConfigTarget?> = _target.asStateFlow()

    /** Null while loading. */
    internal val draft: StateFlow<ConfigDraft?> = _draft.asStateFlow()
    internal val events: Flow<ConfigEvent> = _events.receiveAsFlow()

    /** Messages for the screen behind the editor, sent as a save closes it. */
    internal val closed: Flow<UiText> = _closed.receiveAsFlow()

    internal val preview: StateFlow<ConfigPreview?> = _draft
        .map { it?.output }
        .distinctUntilChanged()
        .debounce(PREVIEW_DEBOUNCE_MS)
        .mapLatest { text -> text?.let { withContext(Dispatchers.Default) { preview(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    internal val info: StateFlow<ConfigInfo?> = _target.flatMapLatest { t ->
        val userSets = selection.subscriptionSets.map { sets -> sets.filterNot { it.isAll }.map { SetOption(it.set.id, it.set.name) } }
        if (t !is ConfigTarget.Edit) return@flatMapLatest userSets.map { ConfigInfo(null, emptyList(), it) }
        combine(subscriptions.subscriptions, nodeRepo.nodes, selection.disabledNodes, userSets) { entries, nodes, disabled, sets ->
            val own = nodes.filter { t.id in it.subs }
            val reasons = if (own.any { it.capability == Capability.UNSUPPORTED }) nodeRepo.unsupportedReasons() else emptyMap()
            ConfigInfo(
                title = entries.firstOrNull { it.info.id == t.id }?.info?.title,
                nodes = own.map { n ->
                    val supported = n.capability == Capability.SUPPORTED
                    EditorNode(
                        id = n.id,
                        flag = Format.flag(n.country),
                        name = n.displayName,
                        protocol = Format.protocol(n.protocol),
                        supported = supported,
                        reason = reasons[n.id]?.takeIf { !supported },
                        enabled = n.id !in disabled,
                    )
                },
                userSets = sets,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** A new config; non-blank [text] opens it in text mode with that text, else on the protocol picker. */
    fun openNew(text: String = "") {
        loadJob?.cancel()
        _target.value = ConfigTarget.New
        _draft.value = if (text.isBlank()) ConfigDraft.new() else ConfigDraft.fromText(text.trim())
    }

    /** Re-opening the config already shown keeps its unsaved edits. */
    fun open(id: SubId) {
        if (_target.value == ConfigTarget.Edit(id)) return
        _target.value = ConfigTarget.Edit(id)
        _draft.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload(id) }
    }

    fun close() {
        loadJob?.cancel()
        _target.value = null
        _draft.value = null
    }

    internal fun edit(change: (ConfigDraft) -> ConfigDraft) {
        _draft.update { d -> if (d == null || d.saving) d else change(d) }
    }

    fun save() {
        val draft = _draft.value ?: return
        if (draft.saving) return
        val current = draft.validate()
        if (current.error != null) {
            _draft.value = current
            return
        }
        _draft.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                val id = current.id
                val result = when {
                    id == null -> subscriptions.createConfig(current.output)
                    current.contentChanged -> subscriptions.updateConfig(id, current.output)
                    else -> ConfigSaveResult.Saved(id)
                }
                when (result) {
                    is ConfigSaveResult.Saved -> {
                        if (current.sets != current.initialSets) selection.setSubscriptionSets(result.id, current.sets)
                        if (id == null) {
                            close()
                            _closed.send(UiText.Res(R.string.subs_config_created))
                        } else {
                            reload(id)
                            _events.send(ConfigEvent.Message(UiText.Res(R.string.subs_editor_saved)))
                        }
                    }
                    is ConfigSaveResult.Invalid -> _draft.update { it?.copy(saving = false, error = Messages.configError(result.error)) }
                    ConfigSaveResult.NotFound -> close()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _draft.update { it?.copy(saving = false) }
                _events.send(ConfigEvent.Message(UiText.Res(R.string.subs_editor_save_failed)))
            }
        }
    }

    /** The saved text, not unsaved edits. */
    fun copy() = share(ConfigEvent::Copy)

    fun showQr() = share(ConfigEvent::ShowQr)

    fun setNodeEnabled(id: NodeId, enabled: Boolean) {
        viewModelScope.launch { selection.setNodeEnabled(id, enabled) }
    }

    private fun share(event: (String) -> ConfigEvent) {
        val id = (_target.value as? ConfigTarget.Edit)?.id ?: return
        viewModelScope.launch { subscriptions.configSource(id)?.let { _events.send(event(it)) } }
    }

    private suspend fun reload(id: SubId) {
        val text = subscriptions.configSource(id)
        if (_target.value != ConfigTarget.Edit(id)) return
        if (text == null) {
            close()
            return
        }
        val sets = selection.observeSetsOf(id).first()
        val draft = withContext(Dispatchers.Default) { ConfigDraft.of(id, text, sets) }
        if (_target.value == ConfigTarget.Edit(id)) _draft.value = draft
    }

    private fun preview(text: String): ConfigPreview {
        val read = ConfigParser.read(text, SubId(0))
        return ConfigPreview(
            nodes = read.nodes.map { n ->
                PreviewNode(
                    flag = Format.flag(n.country),
                    name = n.displayName,
                    protocol = Format.protocol(n.protocol),
                    endpoint = Endpoint.of(n.outboundJson.raw()),
                    reason = read.unsupportedReasons[n.id],
                    supported = n.capability == Capability.SUPPORTED,
                )
            },
            error = Messages.configError(read.error),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PREVIEW_DEBOUNCE_MS = 200L
    }
}
