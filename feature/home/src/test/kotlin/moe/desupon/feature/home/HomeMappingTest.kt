package moe.desupon.feature.home

import moe.desupon.brain.FailReason
import moe.desupon.brain.port.ConnState
import moe.desupon.data.location.ProbeState
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Tier
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UserInfo
import moe.desupon.ui.theme.ConnectionButtonState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeMappingTest {
    private val subs = listOf(
        sub(1, "Alpha", UserInfo(upload = GIB, download = 2 * GIB, total = 0, expireEpochSec = 0)),
        sub(2, "Beta", UserInfo(upload = 0, download = 10 * GIB, total = 100 * GIB, expireEpochSec = (NOW_MS + 5 * DAY_MILLIS) / 1000)),
        sub(3, null),
    )

    private fun ready(conn: ConnState, start: Long? = null, list: List<moe.desupon.data.repo.SubscriptionEntry> = subs) =
        assertIs<HomeUiState.Ready>(homeUiState(conn, list, start, NOW_MS))

    @Test
    fun noSubscriptionsWhileIdleOrFailedIsEmpty() {
        assertEquals(HomeUiState.Empty, homeUiState(ConnState.Idle, emptyList(), null, NOW_MS))
        assertEquals(HomeUiState.Empty, homeUiState(ConnState.Failed(FailReason.NO_NODES), emptyList(), null, NOW_MS))
    }

    @Test
    fun runningSessionWithoutSubscriptionsStillShowsTheButton() {
        val s = ready(ConnState.Connecting(NetworkMode.OPEN), list = emptyList())
        assertEquals(ConnectionButtonState.Connecting, s.button)
        assertEquals(ButtonAction.DISCONNECT, s.action)
    }

    @Test
    fun idleIsDisconnectedWithOptimalChoice() {
        val s = ready(ConnState.Idle)
        assertEquals(ConnectionButtonState.Disconnected, s.button)
        assertEquals(ButtonAction.CONNECT, s.action)
        assertNull(s.caption)
        assertEquals(LocationSummary(Place.AnyCountry, auto = false, setName = null, subscription = null, showHint = true), s.location)
        assertEquals(false, s.showRetry)
    }

    @Test
    fun idleCardsComeFromTheFirstSubscriptionWithUserInfo() {
        val s = ready(ConnState.Idle, list = listOf(sub(9, "Bare"), subs[1]))
        assertEquals(ExpiryCard(5), s.expiry)
        assertEquals(ByteAmount("100", ByteUnit.GB), s.traffic?.total)
    }

    @Test
    fun connectingSegmentsOneUnlitArcPerSubscription() {
        assertEquals(ConnectionButtonState.Checking(listOf(false, false, false)), ready(ConnState.Connecting(NetworkMode.WHITELIST)).button)
    }

    @Test
    fun connectingLightsArcsOfSubscriptionsWithALiveNode() {
        val s = assertIs<HomeUiState.Ready>(homeUiState(ConnState.Connecting(NetworkMode.OPEN), subs, null, NOW_MS, setOf(SubId(3), SubId(1))))
        assertEquals(ConnectionButtonState.Checking(listOf(true, false, true)), s.button)
        val connected = assertIs<HomeUiState.Ready>(homeUiState(ConnState.Connected(node("X", 1), Tier.DIRECT, 1), subs, 1, NOW_MS, setOf(SubId(1))))
        assertEquals(ConnectionButtonState.Connected, connected.button)
    }

    @Test
    fun turnWarmupIsConnectingWithItsOwnCaption() {
        val s = ready(ConnState.TurnWarmup("whitelist"))
        assertEquals(ConnectionButtonState.Connecting, s.button)
        assertEquals(Caption.TurnWarmup, s.caption)
    }

    @Test
    fun connectedShowsServerOfFirstListingSubscriptionAndItsCards() {
        val n = node("Amsterdam", 3, 2, country = "nl")
        val s = ready(ConnState.Connected(n, Tier.DIRECT, connectedAtMs = 42), start = 42)
        assertEquals(ConnectionButtonState.Connected, s.button)
        assertEquals(ButtonAction.DISCONNECT, s.action)
        assertEquals(42, s.sessionStartMs)
        assertEquals(LocationSummary(Place.Named("🇳🇱", "Amsterdam"), true, null, SubscriptionLabel("Beta", 2), false), s.location)
        assertEquals(TrafficCard(ByteAmount("10", ByteUnit.GB), ByteAmount("100", ByteUnit.GB)), s.traffic)
        assertEquals(ExpiryCard(5), s.expiry)
    }

    @Test
    fun untitledSubscriptionFallsBackToOrdinal() {
        val s = ready(ConnState.Connected(node("X", 3), Tier.RU, 1))
        assertEquals(SubscriptionLabel(null, 3), s.location.subscription)
        assertNull(s.traffic)
        assertNull(s.expiry)
    }

    @Test
    fun unlimitedWithoutExpiryHidesDaysCard() {
        val s = ready(ConnState.Connected(node("X", 1), Tier.DIRECT, 1))
        assertEquals(TrafficCard(ByteAmount("3", ByteUnit.GB), null), s.traffic)
        assertNull(s.expiry)
    }

    @Test
    fun degradedAndUnstableStayConnectedWithCaption() {
        val n = node("X", 1)
        val degraded = ready(ConnState.Degraded(n, sinceMs = 5), start = 7)
        assertEquals(ConnectionButtonState.Connected, degraded.button)
        assertEquals(Caption.Degraded, degraded.caption)
        assertEquals(7, degraded.sessionStartMs)
        val unstable = ready(ConnState.Unstable(n))
        assertEquals(ConnectionButtonState.Connected, unstable.button)
        assertEquals(Caption.Unstable, unstable.caption)
    }

    @Test
    fun switchingShowsTheTargetServer() {
        val s = ready(ConnState.Switching(from = node("Old", 1), to = node("New", 2, country = null)))
        assertEquals(ConnectionButtonState.Switching, s.button)
        assertEquals(ButtonAction.DISCONNECT, s.action)
        assertEquals(Place.Named(null, "New"), s.location.place)
        assertEquals(SubscriptionLabel("Beta", 2), s.location.subscription)
    }

    @Test
    fun failedOffersRetryAndMapsReasons() {
        val s = ready(ConnState.Failed(FailReason.NO_NODES))
        assertEquals(ConnectionButtonState.Error, s.button)
        assertEquals(ButtonAction.CONNECT, s.action)
        assertTrue(s.showRetry)
        assertNull(s.caption)
        assertEquals(Caption.FailConfig, caption(ConnState.Failed(FailReason.CONFIG)))
        assertEquals(Caption.FailCore, caption(ConnState.Failed(FailReason.CORE)))
        assertEquals(Caption.FailNotPrepared, caption(ConnState.Failed(FailReason.NOT_PREPARED)))
        assertEquals(Caption.FailStartBlocked, caption(ConnState.Failed(FailReason.START_BLOCKED)))
        assertEquals(Caption.FailUnknown, caption(ConnState.Failed("something_new")))
    }

    @Test
    fun sessionStartSurvivesTransientPhasesOnly() {
        val n = node("X", 1)
        val seq = listOf(
            ConnState.Connecting(NetworkMode.OPEN) to null,
            ConnState.Connected(n, Tier.DIRECT, 100) to 100L,
            ConnState.Degraded(n, 150) to 100L,
            ConnState.Switching(n, n) to 100L,
            ConnState.Unstable(n) to 100L,
            ConnState.Connected(n, Tier.DIRECT, 100) to 100L,
            ConnState.Failed(FailReason.CORE) to null,
            ConnState.Degraded(n, 200) to null,
            ConnState.Idle to null,
        )
        var start: Long? = null
        for ((state, expected) in seq) {
            start = nextSessionStart(start, state)
            assertEquals(expected, start, "after $state")
        }
    }

    @Test
    fun locationChoiceIsShownWhileIdle() {
        val country = homeUiState(ConnState.Idle, subs, null, NOW_MS, selection = SelectionContext(LocationChoice.country("nl")))
        assertEquals(Place.Named("🇳🇱", "Нидерланды"), assertIs<HomeUiState.Ready>(country).location.place)

        val pinned = nodeId("Pinned")
        val list = listData(countries = listOf(locCountry("DE", ProbeState.OK, 50, listOf(locNode("Pinned", "DE")))))
        val pin = assertIs<HomeUiState.Ready>(homeUiState(ConnState.Idle, subs, null, NOW_MS, selection = SelectionContext(LocationChoice.Node(pinned), list = list)))
        assertEquals(Place.Named("🇩🇪", "Pinned"), pin.location.place)
        assertEquals(false, pin.location.auto)

        val gone = assertIs<HomeUiState.Ready>(homeUiState(ConnState.Idle, subs, null, NOW_MS, selection = SelectionContext(LocationChoice.Node(nodeId("Gone")), list = list)))
        assertEquals(Place.UnknownNode, gone.location.place)
    }

    @Test
    fun runningSessionShowsActualNodeAsAutoUnlessPinned() {
        val n = node("Amsterdam", 1)
        val auto = assertIs<HomeUiState.Ready>(
            homeUiState(ConnState.Connected(n, Tier.DIRECT, 1), subs, 1, NOW_MS, selection = SelectionContext(LocationChoice.country("NL"))),
        )
        assertEquals(Place.Named("🇳🇱", "Amsterdam"), auto.location.place)
        assertTrue(auto.location.auto)
        assertEquals(false, auto.location.showHint)

        val pinned = assertIs<HomeUiState.Ready>(
            homeUiState(ConnState.Connected(n, Tier.DIRECT, 1), subs, 1, NOW_MS, selection = SelectionContext(LocationChoice.Node(n.id))),
        )
        assertEquals(false, pinned.location.auto)
    }

    @Test
    fun activeSetNameAndSubscriptionOnlyWhenSetMixesSeveral() {
        val sets = listOf(setEntry(0, "", 1, 2, 3), setEntry(5, "Работа", 1))
        val n = node("X", 1)
        val single = assertIs<HomeUiState.Ready>(
            homeUiState(ConnState.Connected(n, Tier.DIRECT, 1), subs, 1, NOW_MS, selection = SelectionContext(setId = SetId(5), sets = sets)),
        )
        assertEquals("Работа", single.location.setName)
        assertNull(single.location.subscription)

        val all = assertIs<HomeUiState.Ready>(
            homeUiState(ConnState.Connected(n, Tier.DIRECT, 1), subs, 1, NOW_MS, selection = SelectionContext(setId = SetId.ALL, sets = sets)),
        )
        assertNull(all.location.setName)
        assertEquals(SubscriptionLabel("Alpha", 1), all.location.subscription)
    }

    @Test
    fun locationFailuresGetTheirOwnCaptionAndAnyCountryOffer() {
        val country = ready(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE, "NL"))
        assertEquals(Caption.CountryUnavailable("Нидерланды"), country.caption)
        assertTrue(country.offerAnyCountry)
        assertTrue(country.showRetry)
        assertEquals(ConnectionButtonState.Error, country.button)

        val node = ready(ConnState.Failed(FailReason.NODE_UNAVAILABLE, "abc"))
        assertEquals(Caption.NodeUnavailable, node.caption)
        assertTrue(node.offerAnyCountry)

        assertEquals(Caption.CountryUnavailable(null), caption(ConnState.Failed(FailReason.COUNTRY_UNAVAILABLE)))
        assertEquals(false, ready(ConnState.Failed(FailReason.CORE)).offerAnyCountry)
    }

    @Test
    fun degradedWithLocationReasonKeepsConnectedVisuals() {
        val n = node("X", 1)
        val s = ready(ConnState.Degraded(n, 5, FailReason.COUNTRY_UNAVAILABLE, "DE"))
        assertEquals(ConnectionButtonState.Connected, s.button)
        assertEquals(ButtonAction.DISCONNECT, s.action)
        assertEquals(Caption.CountryUnavailable("Германия"), s.caption)
        assertTrue(s.offerAnyCountry)
        assertEquals(false, s.showRetry)
        assertEquals(Caption.NodeUnavailable, caption(ConnState.Degraded(n, 5, FailReason.NODE_UNAVAILABLE, "h")))
        assertEquals(Caption.Degraded, caption(ConnState.Degraded(n, 5)))
    }
}
