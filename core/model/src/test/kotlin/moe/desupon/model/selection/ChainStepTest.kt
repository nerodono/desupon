package moe.desupon.model.selection

import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainStepTest {
    private val a = SubId(1)
    private val b = SubId(2)
    private val c = SubId(3)

    @Test
    fun aNodeBelongsToItsEarliestChainSubscription() {
        val chain = listOf(b, a)
        assertEquals(ChainStep.Sub(0, b), ChainStep.of(chain, setOf(a, b)))
        assertEquals(ChainStep.Sub(1, a), ChainStep.of(chain, setOf(a, c)))
        assertEquals(1, ChainStep.indexOf(chain, setOf(a)))
    }

    @Test
    fun subscriptionsOutsideTheChainAreTheRest() {
        assertEquals(ChainStep.Rest, ChainStep.of(listOf(a), setOf(c)))
        assertEquals(1, ChainStep.indexOf(listOf(a), setOf(c)))
        assertEquals(ChainStep.Rest, ChainStep.of(emptyList(), setOf(a)))
        assertEquals(0, ChainStep.indexOf(emptyList(), setOf(a)))
    }
}
