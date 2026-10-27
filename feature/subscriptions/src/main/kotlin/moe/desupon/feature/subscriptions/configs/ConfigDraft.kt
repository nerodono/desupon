package moe.desupon.feature.subscriptions.configs

import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.parser.ConfigParser

internal enum class ConfigMode { FORM, TEXT }

/** The config editor's content; [id] is null while creating one. */
internal data class ConfigDraft(
    val id: SubId?,
    val mode: ConfigMode,
    /** The config as a share link or JSON; what TEXT mode edits and saves. */
    val text: String,
    val initialText: String,
    /** What FORM mode edits; null while [text] is not form-editable. */
    val form: ConfigForm?,
    /** User sets only; [SetId.ALL] is implied. */
    val sets: Set<SetId>,
    val initialSets: Set<SetId>,
    val error: UiText? = null,
    /** Why FORM mode is not available for [text]. */
    val formNote: UiText? = null,
    val saving: Boolean = false,
    /** The form as opened, so an untouched form keeps a share link from being rewritten as JSON. */
    val initialForm: ConfigForm? = null,
) {
    val isNew: Boolean get() = id == null

    /** The text a save writes. */
    val output: String get() = if (mode == ConfigMode.FORM && form != null) form.toText() else text

    val dirty: Boolean get() = sets != initialSets || if (isNew) output.isNotBlank() || form != null else contentChanged

    /** The config itself changed, not just its sets. */
    val contentChanged: Boolean
        get() = when (mode) {
            ConfigMode.FORM -> form != initialForm
            ConfigMode.TEXT -> text.trim() != initialText.trim() && text.trim() != initialForm?.toText()?.trim()
        }

    fun text(value: String) = copy(text = value, error = null, formNote = null)

    fun form(change: (ConfigForm) -> ConfigForm) = if (form == null) this else copy(form = change(form), error = null)

    fun toggleSet(id: SetId): ConfigDraft {
        if (id.isAll) return this
        return copy(sets = if (id in sets) sets - id else sets + id)
    }

    /** The form's content carried into the text editor. */
    fun toText(): ConfigDraft = when {
        mode == ConfigMode.TEXT -> this
        form == null -> copy(mode = ConfigMode.TEXT)
        else -> copy(mode = ConfigMode.TEXT, text = form.toText(), error = null)
    }

    /** Reads the text into the form; stays in TEXT mode with [formNote] when the form cannot show it. */
    fun toForm(): ConfigDraft {
        if (mode == ConfigMode.FORM) return this
        if (text.isBlank()) return copy(formNote = UiText.Res(R.string.subs_config_form_pick_protocol))
        val form = ConfigForm.from(ConfigParser.read(text, SubId(0)))
            ?: return copy(formNote = UiText.Res(R.string.subs_config_form_unavailable))
        return copy(mode = ConfigMode.FORM, form = form, error = null, formNote = null)
    }

    /** A fresh form for [protocol], replacing whatever was entered. */
    fun newForm(protocol: FormProtocol) = copy(mode = ConfigMode.FORM, form = ConfigForm.blank(protocol), error = null, formNote = null)

    /** Checks what the repository cannot report; unchanged when the draft can be submitted. */
    fun validate(): ConfigDraft = when {
        mode == ConfigMode.TEXT && text.isBlank() -> copy(error = UiText.Res(R.string.subs_config_error_empty))
        mode == ConfigMode.FORM && form == null -> copy(error = UiText.Res(R.string.subs_config_form_pick_protocol))
        else -> this
    }

    companion object {
        fun new(): ConfigDraft = ConfigDraft(
            id = null,
            mode = ConfigMode.TEXT,
            text = "",
            initialText = "",
            form = null,
            sets = emptySet(),
            initialSets = emptySet(),
        )

        /** Opens in FORM mode when the form can show [text]. */
        fun of(id: SubId, text: String, sets: Set<SetId>): ConfigDraft {
            val user = sets.filterNot { it.isAll }.toSet()
            val draft = ConfigDraft(id, ConfigMode.TEXT, text, text, form = null, sets = user, initialSets = user)
            val form = ConfigForm.from(ConfigParser.read(text, id)) ?: return draft
            return draft.copy(mode = ConfigMode.FORM, form = form, initialForm = form)
        }
    }
}
