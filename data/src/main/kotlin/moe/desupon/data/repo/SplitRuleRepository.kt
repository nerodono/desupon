package moe.desupon.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.data.db.SplitRuleDao
import moe.desupon.data.db.toEntity
import moe.desupon.data.db.toModel
import moe.desupon.data.split.RuDefaults
import moe.desupon.model.routing.SplitRule

class SplitRuleRepository internal constructor(private val dao: SplitRuleDao) : SplitRuleRepo {
    override val rules: Flow<List<SplitRule>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }.distinctUntilChanged()

    /** Appends [rule] after the last one, ignoring its id and order; returns the new id. */
    suspend fun add(rule: SplitRule): Long = dao.insert(rule.copy(id = 0, order = dao.maxOrder() + 1).toEntity())

    /** Returns false when no rule has [SplitRule.id]. */
    suspend fun update(rule: SplitRule): Boolean = dao.update(rule.toEntity()) > 0

    suspend fun delete(id: Long): Boolean = dao.delete(id) > 0

    /** Renumbers rules to follow [ids]; rules missing from [ids] keep their relative order after them. */
    suspend fun reorder(ids: List<Long>) {
        val all = dao.getAll()
        val rank = ids.withIndex().associate { (i, id) -> id to i }
        val sorted = all.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.order }, { it.id }))
        dao.updateAll(sorted.mapIndexed { i, r -> r.copy(order = i) })
    }

    suspend fun resetToDefaults() = dao.replaceAll(RuDefaults.rules.map { it.toEntity() })
}
