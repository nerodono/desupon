package moe.desupon.feature.subscriptions

import moe.desupon.data.repo.AddResult
import moe.desupon.data.repo.UpdateResult
import moe.desupon.model.subscription.SubId

/** User-facing text for fetch results; built from codes only, so URLs and tokens can never leak into it. */
internal object Messages {
    private const val HTTP_PREFIX = "HTTP_"

    /** Text for a stored `lastError` code; null when there is no error. */
    fun error(code: String?): UiText? {
        if (code.isNullOrEmpty()) return null
        if (code.startsWith(HTTP_PREFIX)) {
            val status = code.removePrefix(HTTP_PREFIX).toIntOrNull()
                ?: return UiText.Res(R.string.subs_error_unknown)
            val id = when (status) {
                401, 403 -> R.string.subs_error_unauthorized
                404, 410 -> R.string.subs_error_not_found
                429 -> R.string.subs_error_rate_limited
                in 500..599 -> R.string.subs_error_server
                else -> R.string.subs_error_http
            }
            return UiText.Res(id, listOf(status))
        }
        return UiText.Res(
            when (code) {
                "TIMEOUT" -> R.string.subs_error_timeout
                "NETWORK" -> R.string.subs_error_network
                "EMPTY_BODY" -> R.string.subs_error_empty
                "TOO_LARGE" -> R.string.subs_error_too_large
                "NO_NODES" -> R.string.subs_error_no_nodes
                "NO_ENDPOINTS" -> R.string.subs_error_no_endpoints
                else -> R.string.subs_error_unknown
            },
        )
    }

    fun update(result: UpdateResult): UiText? = when (result) {
        is UpdateResult.Updated -> UiText.Plural(R.plurals.subs_result_updated, result.nodes)
        is UpdateResult.Failed -> error(result.error.code)
        is UpdateResult.Throttled -> UiText.Res(R.string.subs_result_throttled)
        UpdateResult.Local -> UiText.Res(R.string.subs_result_local)
        UpdateResult.NotFound -> UiText.Res(R.string.subs_result_not_found)
        UpdateResult.NotDue -> null
    }

    /** Snackbar after an editor save; [refetch] is the update that changed addresses or text triggered. */
    fun saved(refetch: UpdateResult?): UiText {
        val update = refetch?.let(::update) ?: return UiText.Res(R.string.subs_editor_saved)
        return UiText.Res(R.string.subs_editor_saved_with, listOf(update))
    }

    /** Inline error for a rejected local-text edit. */
    fun localText(result: UpdateResult): UiText = when (result) {
        is UpdateResult.Failed -> UiText.Res(R.string.subs_editor_local_no_nodes)
        else -> update(result) ?: UiText.Res(R.string.subs_error_unknown)
    }

    fun updateAll(results: Map<SubId, UpdateResult>): UiText {
        val attempted = results.values.filter { it !is UpdateResult.NotDue && it !is UpdateResult.Local }
        if (attempted.isEmpty()) return UiText.Res(R.string.subs_result_all_none)
        val ok = attempted.count { it is UpdateResult.Updated }
        val failed = attempted.count { it is UpdateResult.Failed }
        return if (failed == 0) {
            UiText.Res(R.string.subs_result_all_ok, listOf(ok, attempted.size))
        } else {
            UiText.Res(R.string.subs_result_all_partial, listOf(ok, attempted.size, failed))
        }
    }

    fun add(result: AddResult): UiText = when (result) {
        is AddResult.Imported -> UiText.Plural(R.plurals.subs_result_imported, result.nodes)
        is AddResult.AlreadyExists -> UiText.Res(R.string.subs_add_exists)
        AddResult.NothingFound -> UiText.Res(R.string.subs_add_nothing)
        is AddResult.Added -> if (result.results.isEmpty()) UiText.Res(R.string.subs_add_exists) else {
            val single = result.results.values.singleOrNull()
            val failure = (single as? UpdateResult.Failed)?.let { error(it.error.code) }
            if (failure != null) {
                UiText.Res(R.string.subs_result_added_failed, listOf(failure))
            } else {
                UiText.Plural(R.plurals.subs_result_added, result.results.size)
            }
        }
    }
}
