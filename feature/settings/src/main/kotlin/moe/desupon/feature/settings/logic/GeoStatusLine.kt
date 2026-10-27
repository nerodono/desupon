package moe.desupon.feature.settings.logic

import moe.desupon.data.geo.GeoKind
import moe.desupon.feature.settings.GeoUiState

/** One line of the database status in settings. */
sealed interface GeoStatusLine {
    data object WaitingForUnmetered : GeoStatusLine

    /** [checkedAtMs] is set when the server confirmed the file noticeably later than it was downloaded. */
    data class Installed(val kind: GeoKind, val categories: Int, val updatedAtMs: Long?, val checkedAtMs: Long?) : GeoStatusLine

    data class Missing(val kind: GeoKind) : GeoStatusLine

    data class DownloadFailed(val kind: GeoKind, val code: String, val atMs: Long?) : GeoStatusLine

    data class ConvertFailed(val kind: GeoKind, val atMs: Long) : GeoStatusLine

    companion object {
        private const val CHECKED_GAP_MS = 60_000L

        /** A failure of an address no longer configured is left out, and so is a conversion failure while work runs, since one is marked failed until it ends. */
        fun of(state: GeoUiState): List<GeoStatusLine> = buildList {
            if (state.waitingForUnmetered && !state.running) add(WaitingForUnmetered)
            for (file in state.files) {
                val s = file.status
                val updated = s.updatedAtMs ?: s.lastOkAtMs
                if (file.categories > 0) {
                    val checked = s.lastOkAtMs?.takeIf { updated != null && it - updated >= CHECKED_GAP_MS }
                    add(Installed(file.kind, file.categories, updated, checked))
                } else {
                    add(Missing(file.kind))
                }
                val error = s.lastError
                if (error != null && s.errorUrl == state.settings.url(file.kind)) add(DownloadFailed(file.kind, error, s.lastAttemptAtMs))
                val convertFailed = s.convertFailedAtMs
                if (convertFailed != null && !state.running) add(ConvertFailed(file.kind, convertFailed))
            }
        }
    }
}
