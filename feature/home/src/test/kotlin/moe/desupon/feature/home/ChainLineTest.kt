package moe.desupon.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import moe.desupon.brain.port.ConnState
import moe.desupon.model.node.Tier
import moe.desupon.model.selection.ChainStep
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainLineTest {
    private val subs = listOf(sub(1, "Финка"), sub(2, "OverSecure"), sub(3, null))
    private val hl = SpanStyle(color = Color.Red, fontWeight = FontWeight.SemiBold)

    private fun AnnotatedString.highlighted(): List<String> = spanStyles.filter { it.item == hl }.map { text.substring(it.start, it.end) }

    @Test
    fun stepsUseTitlesWithOrdinalFallbackAndSkipMissing() {
        val line = chainLine(subs, listOf(SubId(2), SubId(9), SubId(3)), step = null)
        assertEquals(ChainLine(listOf(SubscriptionLabel("OverSecure", 2), SubscriptionLabel(null, 3))), line)
    }

    @Test
    fun emptyOrUnknownChainHasNoLine() {
        assertNull(chainLine(subs, emptyList(), null))
        assertNull(chainLine(subs, listOf(SubId(9)), ChainStep.Rest))
    }

    @Test
    fun activeIndexFollowsTheFilteredSteps() {
        val line = chainLine(subs, listOf(SubId(9), SubId(1), SubId(2)), ChainStep.Sub(2, SubId(2)))
        assertEquals(1, line?.active)
        assertFalse(line!!.rest)
    }

    @Test
    fun restStepMarksRest() {
        val line = chainLine(subs, listOf(SubId(1)), ChainStep.Rest)!!
        assertNull(line.active)
        assertTrue(line.rest)
    }

    @Test
    fun plainChainText() {
        val text = chainText("сначала:", listOf("Финка", "OverSecure"), active = null, rest = null, highlight = hl)
        assertEquals("сначала: Финка → OverSecure", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun activeStepIsHighlighted() {
        val text = chainText("сначала:", listOf("Финка", "OverSecure"), active = 1, rest = null, highlight = hl)
        assertEquals("сначала: Финка → OverSecure", text.text)
        assertEquals(listOf("OverSecure"), text.highlighted())
    }

    @Test
    fun restIsAppendedAndHighlighted() {
        val text = chainText("сначала:", listOf("Финка", "OverSecure"), active = null, rest = "остальные", highlight = hl)
        assertEquals("сначала: Финка → OverSecure · остальные", text.text)
        assertEquals(listOf("остальные"), text.highlighted())
    }

    @Test
    fun homeRowCarriesChainAndDropsRedundantSubscription() {
        val mixed = listOf(setEntry(0, "", 1, 2, active = true))
        val connected = ConnState.Connected(node("X", 2), Tier.DIRECT, connectedAtMs = 1)
        fun row(step: ChainStep?) = (homeUiState(
            connected, subs, 1, NOW_MS,
            selection = SelectionContext(sets = mixed, chain = listOf(SubId(1), SubId(2)), step = step),
        ) as HomeUiState.Ready).location

        val onStep = row(ChainStep.Sub(1, SubId(2)))
        assertEquals(1, onStep.chain?.active)
        assertNull(onStep.subscription)

        val onRest = row(ChainStep.Rest)
        assertTrue(onRest.chain!!.rest)
        assertEquals(SubscriptionLabel("OverSecure", 2), onRest.subscription)
    }

    @Test
    fun runningCoversSessionStatesOnly() {
        val n = node("X", 1)
        assertTrue(ConnState.Connected(n, Tier.DIRECT, 0).running)
        assertTrue(ConnState.Degraded(n, 0).running)
        assertFalse(ConnState.Idle.running)
        assertFalse(ConnState.Failed("x").running)
    }
}
