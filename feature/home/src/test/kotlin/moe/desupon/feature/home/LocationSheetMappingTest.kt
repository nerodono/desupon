package moe.desupon.feature.home

import moe.desupon.data.location.ProbeState
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.network.Transport
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationSheetMappingTest {
    private val subs = listOf(sub(1, "Alpha"), sub(2, "Beta"))

    private fun sheet(
        data: moe.desupon.data.location.LocationListData?,
        choice: LocationChoice = LocationChoice.Any,
        mode: NetworkMode? = null,
        sets: List<moe.desupon.data.repo.SubscriptionSetEntry> = listOf(setEntry(0, "", 1, 2)),
        active: SetId = SetId.ALL,
    ) = locationSheetState(data, sets, active, choice, mode, false, subs)

    @Test
    fun russiaFirstThenOkByRttThenCheckingUnknownDead() {
        val data = listData(
            countries = listOf(
                locCountry("DE", ProbeState.OK, 240),
                locCountry("EE", ProbeState.DEAD),
                locCountry("FI", ProbeState.CHECKING),
                locCountry("JP", ProbeState.UNKNOWN),
                locCountry("NL", ProbeState.OK, 142),
                locCountry("RU", ProbeState.DEAD),
                locCountry("PL", ProbeState.OK, 214),
            ),
        )
        assertEquals(listOf("RU", "NL", "PL", "DE", "FI", "JP", "EE"), sheet(data).countries.map { it.iso })
    }

    @Test
    fun equalRankFallsBackToRussianNameOrder() {
        val data = listData(countries = listOf(locCountry("JP", ProbeState.UNKNOWN), locCountry("DE", ProbeState.UNKNOWN), locCountry("AT", ProbeState.UNKNOWN)))
        assertEquals(listOf("Австрия", "Германия", "Япония"), sheet(data).countries.map { it.name })
    }

    @Test
    fun countryRowsCarryNameFlagRelayAndSelection() {
        val data = listData(countries = listOf(locCountry("RU", ProbeState.OK, 30), locCountry("NL", ProbeState.OK, 131)))
        val s = sheet(data, choice = LocationChoice.country("nl"))
        val (ru, nl) = s.countries
        assertTrue(ru.relay)
        assertFalse(nl.relay)
        assertEquals("Нидерланды", nl.name)
        assertEquals("🇳🇱", nl.flag)
        assertTrue(nl.selected)
        assertFalse(ru.selected)
        assertFalse(s.anySelected)
        assertTrue(sheet(data).anySelected)
    }

    @Test
    fun nodesSortByRttWithDeadLastAndPinIsSelected() {
        val nodes = listOf(
            locNode("dead", state = ProbeState.DEAD),
            locNode("slow", state = ProbeState.OK, rtt = 361),
            locNode("fast", state = ProbeState.OK, rtt = 131),
            locNode("checking", state = ProbeState.CHECKING),
        )
        val data = listData(countries = listOf(locCountry("NL", ProbeState.OK, 131, nodes)))
        val nl = sheet(data, choice = LocationChoice.Node(nodeId("slow"))).countries.single()
        assertEquals(listOf("fast", "slow", "checking", "dead"), nl.nodes.map { it.name })
        assertEquals(listOf(false, true, false, false), nl.nodes.map { it.selected })
        assertTrue(nl.containsSelected)
        assertFalse(nl.selected)
    }

    @Test
    fun subscriptionLabelOnlyWhenActiveSetMixesSeveral() {
        val data = listData(countries = listOf(locCountry("NL", ProbeState.OK, 1, listOf(locNode("a", subs = longArrayOf(2))))))
        assertEquals(SubscriptionLabel("Beta", 2), sheet(data).countries.single().nodes.single().subscription)
        val single = sheet(data, sets = listOf(setEntry(0, "", 1, 2), setEntry(7, "Одна", 2)), active = SetId(7))
        assertNull(single.countries.single().nodes.single().subscription)
    }

    @Test
    fun setChipsKeepOrderAndMarkActive() {
        val sets = listOf(setEntry(0, "", 1, 2), setEntry(3, "Дом", 1), setEntry(4, "Работа", 2))
        assertEquals(
            listOf(SetChip(SetId.ALL, null, false), SetChip(SetId(3), "Дом", false), SetChip(SetId(4), "Работа", true)),
            sheet(null, sets = sets, active = SetId(4)).sets,
        )
    }

    @Test
    fun pinnedOutsideAndOtherAreListed() {
        val outside = locNode("far", country = "US", state = ProbeState.OK, rtt = 90)
        val data = listData(other = listOf(locNode("x", country = null)), pinnedOutside = outside)
        val s = sheet(data, choice = LocationChoice.Node(outside.id))
        assertEquals("far", s.pinnedOutside?.name)
        assertTrue(s.pinnedOutside!!.selected)
        assertEquals(listOf("x"), s.other.map { it.name })
    }

    @Test
    fun whitelistBannerOnlyForChoicesAbroad() {
        val data = listData(countries = listOf(locCountry("RU", ProbeState.OK, 1, listOf(locNode("ru1", "RU"))), locCountry("NL", ProbeState.OK, 1, listOf(locNode("nl1")))))
        val w = NetworkMode.WHITELIST
        assertFalse(showWhitelistBanner(w, LocationChoice.Any, data))
        assertFalse(showWhitelistBanner(w, LocationChoice.country("RU"), data))
        assertFalse(showWhitelistBanner(w, LocationChoice.Node(nodeId("ru1")), data))
        assertTrue(showWhitelistBanner(w, LocationChoice.country("NL"), data))
        assertTrue(showWhitelistBanner(w, LocationChoice.Node(nodeId("nl1")), data))
        assertFalse(showWhitelistBanner(NetworkMode.OPEN, LocationChoice.country("NL"), data))
        assertFalse(showWhitelistBanner(null, LocationChoice.country("NL"), data))
    }

    @Test
    fun refreshingOnlyForTheUsersProbe() {
        assertTrue(locationSheetState(null, emptyList(), SetId.ALL, LocationChoice.Any, null, true, subs).refreshing)
        assertFalse(sheet(null).refreshing)
    }

    @Test
    fun rttLevels() {
        assertEquals(RttLevel.GOOD, rttLevel(120))
        assertEquals(RttLevel.FAIR, rttLevel(121))
        assertEquals(RttLevel.FAIR, rttLevel(350))
        assertEquals(RttLevel.SLOW, rttLevel(351))
    }

    @Test
    fun throttleRoundsUp() {
        assertEquals(1, throttleSeconds(1))
        assertEquals(1, throttleSeconds(1000))
        assertEquals(2, throttleSeconds(1001))
        assertEquals(1, throttleSeconds(0))
    }

    @Test
    fun countryNamesInRussian() {
        assertEquals("Нидерланды", countryName("nl"))
        assertEquals("Россия", countryName("RU"))
        assertEquals("ZZ9", countryName("ZZ9"))
    }

    @Test
    fun recheckingNodesKeepTheirLastRtt() {
        val answered = listData(
            countries = listOf(locCountry("NL", ProbeState.OK, 90, listOf(locNode("a", "NL", ProbeState.OK, 90), locNode("b", "NL", ProbeState.OK, 200)))),
            other = listOf(locNode("o", null, ProbeState.OK, 50)),
        )
        val (_, memory) = carryRtts(RttMemory(), answered)
        val checking = listData(
            countries = listOf(locCountry("NL", ProbeState.CHECKING, null, listOf(locNode("a", "NL", ProbeState.CHECKING), locNode("b", "NL", ProbeState.CHECKING)))),
            other = listOf(locNode("o", null, ProbeState.CHECKING)),
        )
        val (filled, kept) = carryRtts(memory, checking)
        val nl = filled.countries.single()
        assertEquals(ProbeState.CHECKING, nl.state)
        assertEquals(90, nl.bestRttMs)
        assertEquals(listOf(90, 200), nl.nodes.map { it.rttMs })
        assertEquals(50, filled.other.single().rttMs)
        assertEquals(memory.rtts, kept.rtts)
    }

    @Test
    fun deadNodesAndNetworkChangesForgetRtt() {
        val (_, memory) = carryRtts(RttMemory(), listData(other = listOf(locNode("o", null, ProbeState.OK, 50))))
        val (_, afterDead) = carryRtts(memory, listData(other = listOf(locNode("o", null, ProbeState.DEAD))))
        val (filled, _) = carryRtts(afterDead, listData(other = listOf(locNode("o", null, ProbeState.CHECKING))))
        assertNull(filled.other.single().rttMs)

        val other = listData(other = listOf(locNode("o", null, ProbeState.CHECKING)))
            .copy(network = NetworkKey(Transport.WIFI, "h"))
        assertNull(carryRtts(memory, other).first.other.single().rttMs)
    }

    @Test
    fun recheckingRowsWithRttStayAmongAnswering() {
        val sorted = sortNodes(
            listOf(
                locNode("idle", state = ProbeState.CHECKING),
                locNode("slow", state = ProbeState.OK, rtt = 400),
                locNode("fast", state = ProbeState.CHECKING, rtt = 100),
            ),
        )
        assertEquals(listOf("fast", "slow", "idle"), sorted.map { it.displayName })
    }
}
