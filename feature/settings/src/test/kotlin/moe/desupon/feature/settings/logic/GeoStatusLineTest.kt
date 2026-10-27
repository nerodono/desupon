package moe.desupon.feature.settings.logic

import moe.desupon.data.geo.GeoDataSettings
import moe.desupon.data.geo.GeoFileStatus
import moe.desupon.data.geo.GeoKind
import moe.desupon.feature.settings.GeoFileUi
import moe.desupon.feature.settings.GeoUiState
import moe.desupon.feature.settings.logic.GeoStatusLine.ConvertFailed
import moe.desupon.feature.settings.logic.GeoStatusLine.DownloadFailed
import moe.desupon.feature.settings.logic.GeoStatusLine.Installed
import moe.desupon.feature.settings.logic.GeoStatusLine.Missing
import moe.desupon.feature.settings.logic.GeoStatusLine.WaitingForUnmetered
import kotlin.test.Test
import kotlin.test.assertEquals

class GeoStatusLineTest {
    private val geoipUrl = GeoDataSettings.DEFAULT_GEOIP_URL

    private fun state(geoip: GeoFileStatus, categories: Int = 266, running: Boolean = false, waiting: Boolean = false) = GeoUiState(
        files = listOf(GeoFileUi(GeoKind.GEOIP, geoip, categories), GeoFileUi(GeoKind.GEOSITE)),
        running = running,
        waitingForUnmetered = waiting,
    )

    @Test
    fun aCheckIsNoUpdate() {
        val downloaded = GeoFileStatus(geoipUrl, updatedAtMs = 1_000, lastOkAtMs = 1_000)
        assertEquals(listOf(Installed(GeoKind.GEOIP, 266, 1_000, null), Missing(GeoKind.GEOSITE)), GeoStatusLine.of(state(downloaded)))
        assertEquals(
            Installed(GeoKind.GEOIP, 266, 1_000, 900_000),
            GeoStatusLine.of(state(downloaded.copy(lastOkAtMs = 900_000))).first(),
        )
    }

    @Test
    fun failuresAreExtraLinesUnderTheDatabaseInUse() {
        val status = GeoFileStatus(
            geoipUrl,
            updatedAtMs = 1_000,
            lastOkAtMs = 1_000,
            lastAttemptAtMs = 5_000,
            lastError = "NETWORK",
            errorUrl = geoipUrl,
            convertFailedAtMs = 7_000,
        )
        assertEquals(
            listOf(
                Installed(GeoKind.GEOIP, 266, 1_000, null),
                DownloadFailed(GeoKind.GEOIP, "NETWORK", 5_000),
                ConvertFailed(GeoKind.GEOIP, 7_000),
                Missing(GeoKind.GEOSITE),
            ),
            GeoStatusLine.of(state(status)),
        )
        assertEquals(
            listOf(Installed(GeoKind.GEOIP, 266, 1_000, null), Missing(GeoKind.GEOSITE)),
            GeoStatusLine.of(state(status.copy(errorUrl = "https://old.example/geoip.dat"), running = true)),
            "an old address's failure and a conversion in progress are not shown",
        )
    }

    @Test
    fun waitingForWifiIsShownUntilWorkStarts() {
        assertEquals(listOf(WaitingForUnmetered, Missing(GeoKind.GEOIP), Missing(GeoKind.GEOSITE)), GeoStatusLine.of(state(GeoFileStatus(), 0, waiting = true)))
        assertEquals(listOf(Missing(GeoKind.GEOIP), Missing(GeoKind.GEOSITE)), GeoStatusLine.of(state(GeoFileStatus(), 0, running = true, waiting = true)))
    }
}
