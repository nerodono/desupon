package moe.desupon.feature.subscriptions.sets

import moe.desupon.feature.subscriptions.chain.ChainOp
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SetDraftTest {
    @Test
    fun `blank name is rejected`() {
        assertEquals(SetDraftError.NAME_BLANK, SetDraft(id = null).validate(emptyList()))
        assertEquals(SetDraftError.NAME_BLANK, SetDraft(id = null, name = "   ").validate(emptyList()))
    }

    @Test
    fun `name taken by another set is rejected ignoring case and spaces`() {
        assertEquals(SetDraftError.NAME_TAKEN, SetDraft(id = null, name = " работа ").validate(listOf("Работа")))
        assertNull(SetDraft(id = SetId(2), name = "Работа").validate(listOf("Дом")))
    }

    @Test
    fun `valid draft may be empty of members`() {
        assertNull(SetDraft(id = null, name = "Дом").validate(listOf("Работа")))
    }

    @Test
    fun `editing the name clears the error`() {
        val d = SetDraft(id = null, error = SetDraftError.NAME_BLANK).name("Д")
        assertNull(d.error)
        assertEquals("Д", d.name)
    }

    @Test
    fun `toggle adds and removes members`() {
        val d = SetDraft(id = null).toggle(SubId(1)).toggle(SubId(2)).toggle(SubId(1))
        assertEquals(setOf(SubId(2)), d.members)
    }

    @Test
    fun `unchecking a member removes it from the chain`() {
        val d = SetDraft(id = SetId(2), members = setOf(SubId(1), SubId(2)), chain = listOf(SubId(2), SubId(1))).toggle(SubId(2))
        assertEquals(listOf(SubId(1)), d.chain)
    }

    @Test
    fun `chain ops apply to members only`() {
        val d = SetDraft(id = SetId(2), members = setOf(SubId(1), SubId(2)))
        assertEquals(listOf(SubId(2)), d.chain(ChainOp.Add(SubId(2))).chain)
        assertEquals(d, d.chain(ChainOp.Add(SubId(9))))
        assertEquals(d, d.chain(ChainOp.Remove(SubId(1))))
    }

    @Test
    fun `the all set edits only its chain`() {
        val d = SetDraft(id = SetId.ALL, members = setOf(SubId(1)))
        assertNull(d.validate(listOf("")))
        assertEquals(d, d.toggle(SubId(1)))
        assertEquals(listOf(SubId(1)), d.chain(ChainOp.Add(SubId(1))).chain)
    }

    @Test
    fun `move swaps neighbours and clamps`() {
        val ids = listOf(SetId(1), SetId(2), SetId(3))
        assertEquals(listOf(SetId(2), SetId(1), SetId(3)), SetOrder.move(ids, SetId(2), -1))
        assertEquals(listOf(SetId(1), SetId(3), SetId(2)), SetOrder.move(ids, SetId(2), 1))
        assertNull(SetOrder.move(ids, SetId(1), -1))
        assertNull(SetOrder.move(ids, SetId(3), 1))
        assertNull(SetOrder.move(ids, SetId(9), 1))
    }
}
