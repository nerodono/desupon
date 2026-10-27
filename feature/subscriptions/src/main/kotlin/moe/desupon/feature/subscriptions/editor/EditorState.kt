package moe.desupon.feature.subscriptions.editor

import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.repo.EditResult
import moe.desupon.data.repo.SubscriptionDetails
import moe.desupon.data.repo.SubscriptionEdit
import moe.desupon.data.repo.UrlMask
import moe.desupon.feature.subscriptions.Format
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.Secret
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.UpdateInterval

/** One fetch address; [key] identifies the row across moves and stays unique within an editor session. */
internal data class UrlRow(val key: Long, val url: Secret) {
    val masked: String get() = UrlMask.mask(url.raw())
    val cleartext: Boolean get() = Format.cleartext(url.raw())
}

/** User-Agent presets mimicking common clients; [label] is a client name and stays untranslated. */
internal enum class UaPreset(val label: String?, val value: String?) {
    DEFAULT(null, null),
    V2RAYNG("v2rayNG", SubscriptionFetcher.DEFAULT_USER_AGENT),
    HAPP("Happ", "Happ/3.7.0"),
    HIDDIFY("Hiddify", "HiddifyNext/2.5.7"),
    CLASH_META("Clash Meta", "ClashMetaForAndroid/2.11.7.Meta"),
    SING_BOX("sing-box", "SFA (sing-box 1.12.9; language ru_RU)"),
    CUSTOM(null, null),
}

internal data class UaSelection(val preset: UaPreset, val custom: String = "") {
    /** The override to store; null uses the global User-Agent. */
    val value: String?
        get() = when (preset) {
            UaPreset.DEFAULT -> null
            UaPreset.CUSTOM -> custom.trim().ifEmpty { null }
            else -> preset.value
        }

    /** Switching to [UaPreset.CUSTOM] starts from the current value, so a preset can be tweaked. */
    fun select(next: UaPreset): UaSelection =
        UaSelection(next, if (next == UaPreset.CUSTOM && custom.isBlank()) value.orEmpty() else custom)

    companion object {
        fun of(value: String?): UaSelection {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return UaSelection(UaPreset.DEFAULT)
            val preset = UaPreset.entries.firstOrNull { it.value == v } ?: return UaSelection(UaPreset.CUSTOM, v)
            return UaSelection(preset)
        }
    }
}

internal object Intervals {
    /** Null follows the provider. */
    val presets: List<UpdateInterval?> = listOf(
        null,
        UpdateInterval.Hours(1),
        UpdateInterval.Hours(6),
        UpdateInterval.Hours(12),
        UpdateInterval.Hours(24),
        UpdateInterval.Manual,
    )

    /** A stored override outside [presets] stays selectable. */
    fun options(current: UpdateInterval?): List<UpdateInterval?> =
        if (current in presets) presets else presets.dropLast(1) + current + UpdateInterval.Manual
}

/** The values a save writes. */
internal data class EditorForm(
    /** Blank shows and stores the provider title. */
    val name: String,
    /** Primary first; empty for local subscriptions. */
    val urls: List<UrlRow>,
    val userAgent: UaSelection,
    val interval: UpdateInterval?,
    /** User sets only; [SetId.ALL] is implied. */
    val sets: Set<SetId>,
    /** Pasted text of a local subscription; null for remote ones. */
    val localText: String?,
) {
    val isLocal: Boolean get() = localText != null

    /** A name equal to the provider title is stored as none, so it keeps following the provider. */
    fun toEdit(providerTitle: String?): SubscriptionEdit = SubscriptionEdit(
        name = name.trim().takeIf { it.isNotEmpty() && it != providerTitle },
        urls = urls.map { it.url.raw() },
        userAgent = userAgent.value,
        interval = interval,
        sets = sets.filterNot { it.isAll }.toSet(),
    )
}

internal data class EditorDraft(
    val form: EditorForm,
    val initial: EditorForm,
    val providerTitle: String?,
    val mirrorInput: String = "",
    val mirrorError: UiText? = null,
    /** Rows showing the raw URL instead of the mask. */
    val revealed: Set<Long> = emptySet(),
    val urlErrors: Map<Long, UiText> = emptyMap(),
    /** List-wide address error: none left or taken by another subscription. */
    val urlsError: UiText? = null,
    val localTextError: UiText? = null,
    val saving: Boolean = false,
    private val nextKey: Long = form.urls.maxOfOrNull { it.key + 1 } ?: 0,
) {
    val dirty: Boolean
        get() = form.toEdit(providerTitle) != initial.toEdit(providerTitle) ||
            form.localText?.trimEnd() != initial.localText?.trimEnd()

    val hasErrors: Boolean get() = urlErrors.isNotEmpty() || urlsError != null || localTextError != null || mirrorError != null

    fun name(value: String) = copy(form = form.copy(name = value))

    fun clearName() = name("")

    fun mirrorInput(value: String) = copy(mirrorInput = value, mirrorError = null)

    /** Appends every address in [mirrorInput]; whitespace or `|` separate several. Rejects all on any bad one. */
    fun addMirror(): EditorDraft {
        val parts = mirrorInput.split('|', ' ', '\n', '\t', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return copy(mirrorInput = "", mirrorError = null)
        if (parts.any { !SubscriptionFetcher.isSubscriptionUrl(it) }) return copy(mirrorError = UiText.Res(R.string.subs_editor_url_invalid))
        val existing = form.urls.mapTo(HashSet()) { it.url.raw() }
        val fresh = parts.distinct().filterNot { it in existing }
        if (fresh.isEmpty()) return copy(mirrorError = UiText.Res(R.string.subs_editor_url_in_list))
        val rows = fresh.mapIndexed { i, url -> UrlRow(nextKey + i, Secret(url)) }
        return urlsChanged(form.urls + rows).copy(mirrorInput = "", mirrorError = null, nextKey = nextKey + rows.size)
    }

    fun remove(key: Long) = urlsChanged(form.urls.filterNot { it.key == key }).copy(revealed = revealed - key)

    /** Moves the row by [delta] positions, clamped to the list. */
    fun move(key: Long, delta: Int): EditorDraft {
        val from = form.urls.indexOfFirst { it.key == key }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, form.urls.lastIndex)
        if (to == from) return this
        val list = form.urls.toMutableList()
        list.add(to, list.removeAt(from))
        return urlsChanged(list)
    }

    fun toggleReveal(key: Long) = copy(revealed = if (key in revealed) revealed - key else revealed + key)

    fun userAgent(preset: UaPreset) = copy(form = form.copy(userAgent = form.userAgent.select(preset)))

    fun customUserAgent(value: String) = copy(form = form.copy(userAgent = UaSelection(UaPreset.CUSTOM, value)))

    fun interval(value: UpdateInterval?) = copy(form = form.copy(interval = value))

    fun toggleSet(id: SetId): EditorDraft {
        if (id.isAll) return this
        val sets = if (id in form.sets) form.sets - id else form.sets + id
        return copy(form = form.copy(sets = sets))
    }

    fun localText(value: String) = if (form.localText == null) this else copy(form = form.copy(localText = value), localTextError = null)

    /** Checks what the repository cannot report per field; unchanged when the form can be submitted. */
    fun validate(): EditorDraft = when {
        form.isLocal && form.localText.isNullOrBlank() -> copy(localTextError = UiText.Res(R.string.subs_editor_local_empty))
        !form.isLocal && form.urls.isEmpty() -> copy(urlsError = UiText.Res(R.string.subs_editor_no_urls))
        else -> this
    }

    /** Maps a rejected save onto the rows that were [submitted]. */
    fun withResult(result: EditResult, submitted: List<UrlRow>): EditorDraft = when (result) {
        is EditResult.InvalidUrl -> {
            val key = submitted.getOrNull(result.index)?.key
            if (key != null) {
                copy(urlErrors = mapOf(key to UiText.Res(R.string.subs_editor_url_invalid)))
            } else {
                copy(urlsError = UiText.Res(R.string.subs_editor_url_invalid))
            }
        }
        EditResult.NoUrls -> copy(urlsError = UiText.Res(R.string.subs_editor_no_urls))
        is EditResult.DuplicateUrl -> copy(urlsError = UiText.Res(R.string.subs_editor_url_duplicate))
        is EditResult.Saved, EditResult.NotFound -> this
    }

    private fun urlsChanged(urls: List<UrlRow>) = copy(form = form.copy(urls = urls), urlErrors = emptyMap(), urlsError = null)

    companion object {
        fun from(details: SubscriptionDetails, localText: String?): EditorDraft {
            val form = EditorForm(
                name = details.nameOverride.orEmpty(),
                urls = details.urls.mapIndexed { i, url -> UrlRow(i.toLong(), url) },
                userAgent = UaSelection.of(details.userAgentOverride),
                interval = details.intervalOverride,
                sets = details.sets.filterNot { it.isAll }.toSet(),
                localText = if (details.isLocal) localText.orEmpty() else null,
            )
            return EditorDraft(form = form, initial = form, providerTitle = details.providerTitle)
        }
    }
}
