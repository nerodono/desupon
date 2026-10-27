package moe.desupon.data.split

import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AppBypassTest {
    private val own = "moe.desupon.app"
    private val split = AppSplit(
        SplitMode.ALL_EXCEPT_SELECTED,
        packages = setOf("org.example.bank", "ru.example.both", "org.example.kept"),
        bypassPrefixes = setOf("ru.", "su."),
        forceTunnel = setOf("ru.example.kept", "org.example.kept", "org.example.stale"),
    )

    @Test
    fun overrideBeatsPrefixBeatsSelection() {
        assertEquals(AppRoute.BYPASS_PREFIX, AppBypass.route(split, "ru.example.maps", own))
        assertEquals(AppRoute.BYPASS_PREFIX, AppBypass.route(split, "ru.example.both", own))
        assertEquals(AppRoute.BYPASS_SELECTED, AppBypass.route(split, "org.example.bank", own))
        assertEquals(AppRoute.TUNNEL_FORCED, AppBypass.route(split, "ru.example.kept", own))
        assertEquals(AppRoute.TUNNEL_FORCED, AppBypass.route(split, "org.example.kept", own))
        assertEquals(AppRoute.TUNNEL, AppBypass.route(split, "org.example.stale", own))
        assertEquals(AppRoute.TUNNEL, AppBypass.route(split, "com.example.ru.app", own), "prefixes match the start only")
    }

    @Test
    fun ownPackageAndOtherModesNeverBypass() {
        assertEquals(AppRoute.TUNNEL, AppBypass.route(split.copy(bypassPrefixes = setOf("moe.")), own, own))
        assertEquals(AppRoute.TUNNEL, AppBypass.route(split.copy(mode = SplitMode.ONLY_SELECTED), "ru.example.maps", own))
        assertEquals(AppRoute.TUNNEL, AppBypass.route(split.copy(mode = SplitMode.ALL), "org.example.bank", own))
    }

    @Test
    fun bypassedListsMatchingInstalledPackages() {
        val installed = listOf("ru.example.maps", "su.example.mail", "ru.example.kept", "org.example.bank", "org.example.other", own)
        assertEquals(setOf("ru.example.maps", "su.example.mail", "org.example.bank"), AppBypass.bypassed(split, installed, own))
    }
}
