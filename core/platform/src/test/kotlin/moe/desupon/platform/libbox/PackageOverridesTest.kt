package moe.desupon.platform.libbox

import moe.desupon.model.routing.AppSplit
import moe.desupon.model.routing.SplitMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageOverridesTest {
    private val own = "moe.desupon.app"

    @Test
    fun allSetsNoLists() {
        assertEquals(PackageOverrides(emptyList(), emptyList()), PackageOverrides.of(AppSplit(SplitMode.ALL, setOf("a.b")), own))
    }

    @Test
    fun onlySelectedAddsOwnPackage() {
        val o = PackageOverrides.of(AppSplit(SplitMode.ONLY_SELECTED, setOf("org.example.b", "org.example.a")), own)
        assertEquals(listOf("moe.desupon.app", "org.example.a", "org.example.b"), o.include)
        assertEquals(emptyList(), o.exclude)
    }

    @Test
    fun onlySelectedEmptyStillTunnelsOwnPackage() {
        assertEquals(listOf(own), PackageOverrides.of(AppSplit(SplitMode.ONLY_SELECTED), own).include)
    }

    @Test
    fun exceptSelectedNeverExcludesOwnPackage() {
        val o = PackageOverrides.of(AppSplit(SplitMode.ALL_EXCEPT_SELECTED, setOf(own, "org.example.bank", " ")), own)
        assertEquals(emptyList(), o.include)
        assertEquals(listOf("org.example.bank"), o.exclude)
    }
}
