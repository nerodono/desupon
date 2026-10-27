package moe.desupon.feature.settings.logic

import moe.desupon.data.ruleset.RuleSetInfo
import moe.desupon.data.ruleset.RuleSetInfo.Source
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleSetSearchTest {
    private val sets = listOf(
        RuleSetInfo("geosite-category-ru", "CATEGORY-RU", Source.BUNDLED, 1_103),
        RuleSetInfo("geoip-ru", null, Source.BUNDLED, null),
        RuleSetInfo("geoip-ru-blocked", "RU-BLOCKED", Source.DOWNLOADED, 88_587),
        RuleSetInfo("geosite-geolocation-not-cn", "GEOLOCATION-!CN", Source.DOWNLOADED, 5),
        RuleSetInfo("geosite-ru-blocked-all", "RU-BLOCKED-ALL", Source.DOWNLOADED, 1_374_049),
    )
    private val labels = mapOf("geosite-category-ru" to "Российские сервисы", "geoip-ru" to "IP-адреса России")

    private fun search(query: String) = RuleSetSearch.groups(sets, query, labels).map { (g, items) -> g to items.map { it.name } }

    @Test
    fun blankQueryListsBundledThenGeoipThenGeosite() {
        assertEquals(
            listOf(
                RuleSetGroup.BUNDLED to listOf("geosite-category-ru", "geoip-ru"),
                RuleSetGroup.GEOIP to listOf("geoip-ru-blocked"),
                RuleSetGroup.GEOSITE to listOf("geosite-geolocation-not-cn", "geosite-ru-blocked-all"),
            ),
            search(" "),
        )
    }

    @Test
    fun matchesLabelCodeAndNameIgnoringCase() {
        assertEquals(listOf(RuleSetGroup.BUNDLED to listOf("geoip-ru")), search("россии"))
        assertEquals(listOf(RuleSetGroup.GEOSITE to listOf("geosite-geolocation-not-cn")), search("!cn"))
        assertEquals(
            listOf(RuleSetGroup.GEOIP to listOf("geoip-ru-blocked"), RuleSetGroup.GEOSITE to listOf("geosite-ru-blocked-all")),
            search("ru-blocked"),
        )
        assertEquals(emptyList(), search("netflix"))
    }

    @Test
    fun hugeSetsAreFlagged() {
        assertEquals(listOf("geosite-ru-blocked-all"), sets.filter { it.huge }.map { it.name })
    }
}
