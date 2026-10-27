package moe.desupon.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SubscriptionDao {
    @Query("SELECT * FROM subscription ORDER BY id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription ORDER BY id")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE id = :id")
    suspend fun get(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscription WHERE id = :id")
    fun observe(id: Long): Flow<SubscriptionEntity?>

    @Query("SELECT * FROM subscription WHERE url = :url AND local = 0 LIMIT 1")
    suspend fun findByUrl(url: String): SubscriptionEntity?

    @Insert
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("DELETE FROM subscription WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
internal abstract class NodeDao {
    /** Every (node, subscription) pair, grouped by subscription in body order. */
    @Query(
        "SELECT n.*, r.subId AS ref_subId, r.position AS ref_position FROM node n " +
            "INNER JOIN node_sub r ON r.nodeId = n.id ORDER BY r.subId, r.position",
    )
    abstract fun observePool(): Flow<List<PoolRow>>

    @Query(
        "SELECT n.*, r.subId AS ref_subId, r.position AS ref_position FROM node n " +
            "INNER JOIN node_sub r ON r.nodeId = n.id ORDER BY r.subId, r.position",
    )
    abstract suspend fun getPool(): List<PoolRow>

    @Query("SELECT subId, COUNT(*) AS count FROM node_sub GROUP BY subId")
    abstract fun observeCounts(): Flow<List<SubNodeCount>>

    @Query("SELECT id, unsupportedReason FROM node WHERE unsupportedReason IS NOT NULL")
    abstract suspend fun unsupportedReasons(): List<NodeReason>

    @Upsert
    abstract suspend fun upsertNodes(nodes: List<NodeEntity>)

    @Insert
    abstract suspend fun insertRefs(refs: List<NodeSubCrossRef>)

    @Query("DELETE FROM node_sub WHERE subId = :subId")
    abstract suspend fun deleteRefs(subId: Long)

    @Query("DELETE FROM node WHERE id NOT IN (SELECT nodeId FROM node_sub)")
    abstract suspend fun deleteOrphanNodes(): Int

    @Query("DELETE FROM probe_stat WHERE nodeId NOT IN (SELECT id FROM node)")
    abstract suspend fun deleteOrphanStats(): Int

    @Query("DELETE FROM tier_learning WHERE nodeId NOT IN (SELECT id FROM node)")
    abstract suspend fun deleteOrphanLearning(): Int

    @Query("DELETE FROM disabled_node WHERE nodeId NOT IN (SELECT id FROM node)")
    abstract suspend fun deleteOrphanDisabled(): Int

    /** Replaces the node list of [subId]; nodes no subscription lists any more are dropped with their stats. */
    @Transaction
    open suspend fun replaceForSubscription(subId: Long, nodes: List<NodeEntity>) {
        deleteRefs(subId)
        upsertNodes(nodes)
        insertRefs(nodes.mapIndexed { i, n -> NodeSubCrossRef(n.id, subId, i) })
        deleteOrphans()
    }

    @Transaction
    open suspend fun deleteOrphans() {
        deleteOrphanNodes()
        deleteOrphanStats()
        deleteOrphanLearning()
        deleteOrphanDisabled()
    }
}

internal data class NodeReason(val id: String, val unsupportedReason: String)

@Dao
internal interface ProbeStatDao {
    @Query("SELECT * FROM probe_stat WHERE networkKey = :networkKey")
    suspend fun forNetwork(networkKey: String): List<ProbeStatEntity>

    @Query("SELECT * FROM probe_stat WHERE networkKey = :networkKey")
    fun observeForNetwork(networkKey: String): Flow<List<ProbeStatEntity>>

    @Upsert
    suspend fun upsert(stats: List<ProbeStatEntity>)

    @Query("DELETE FROM probe_stat WHERE updatedAt < :epochMs")
    suspend fun pruneOlderThan(epochMs: Long): Int
}

@Dao
internal interface NetworkMemoryDao {
    @Query("SELECT * FROM network_memory WHERE networkKey = :networkKey")
    suspend fun get(networkKey: String): NetworkMemoryEntity?

    @Upsert
    suspend fun upsert(memory: NetworkMemoryEntity)

    @Query("DELETE FROM network_memory WHERE updatedAt < :epochMs")
    suspend fun pruneOlderThan(epochMs: Long): Int
}

@Dao
internal abstract class SplitRuleDao {
    @Query("SELECT * FROM split_rule ORDER BY position, id")
    abstract fun observeAll(): Flow<List<SplitRuleEntity>>

    @Query("SELECT * FROM split_rule ORDER BY position, id")
    abstract suspend fun getAll(): List<SplitRuleEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM split_rule")
    abstract suspend fun maxOrder(): Int

    @Insert
    abstract suspend fun insert(rule: SplitRuleEntity): Long

    @Insert
    abstract suspend fun insertAll(rules: List<SplitRuleEntity>)

    @Update
    abstract suspend fun update(rule: SplitRuleEntity): Int

    @Update
    abstract suspend fun updateAll(rules: List<SplitRuleEntity>)

    @Query("DELETE FROM split_rule WHERE id = :id")
    abstract suspend fun delete(id: Long): Int

    @Query("DELETE FROM split_rule")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(rules: List<SplitRuleEntity>) {
        deleteAll()
        insertAll(rules)
    }
}

@Dao
internal interface TierLearningDao {
    @Query("SELECT nodeId FROM tier_learning WHERE networkKey = :networkKey")
    suspend fun promoted(networkKey: String): List<String>

    /** Keeps the first promotion time of rows that already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<TierLearningEntity>)
}

@Dao
internal abstract class SubSetDao {
    @Query("SELECT * FROM sub_set ORDER BY position, id")
    abstract fun observeSets(): Flow<List<SubSetEntity>>

    @Query("SELECT * FROM sub_set ORDER BY position, id")
    abstract suspend fun getSets(): List<SubSetEntity>

    @Query("SELECT * FROM sub_set WHERE id = :id")
    abstract suspend fun getSet(id: Long): SubSetEntity?

    @Query("SELECT * FROM sub_set_member")
    abstract fun observeMembers(): Flow<List<SubSetMemberEntity>>

    @Query("SELECT subId FROM sub_set_member WHERE setId = :setId")
    abstract suspend fun members(setId: Long): List<Long>

    @Query("SELECT setId FROM sub_set_member WHERE subId = :subId")
    abstract suspend fun setsOf(subId: Long): List<Long>

    @Query("SELECT setId FROM sub_set_member WHERE subId = :subId")
    abstract fun observeSetsOf(subId: Long): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM sub_set")
    abstract suspend fun maxPosition(): Int

    @Insert
    abstract suspend fun insert(set: SubSetEntity): Long

    @Update
    abstract suspend fun update(set: SubSetEntity): Int

    @Update
    abstract suspend fun updateAll(sets: List<SubSetEntity>)

    @Query("DELETE FROM sub_set WHERE id = :id")
    abstract suspend fun delete(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMembers(members: List<SubSetMemberEntity>)

    @Query("DELETE FROM sub_set_member WHERE setId = :setId")
    abstract suspend fun clearMembers(setId: Long)

    @Query("DELETE FROM sub_set_member WHERE subId = :subId")
    abstract suspend fun clearSetsOf(subId: Long)

    @Query("SELECT * FROM sub_chain ORDER BY setId, position")
    abstract fun observeChains(): Flow<List<SubChainEntity>>

    @Query("SELECT subId FROM sub_chain WHERE setId = :setId ORDER BY position")
    abstract suspend fun chain(setId: Long): List<Long>

    @Query("DELETE FROM sub_chain WHERE setId = :setId")
    abstract suspend fun clearChain(setId: Long)

    @Insert
    abstract suspend fun insertChain(links: List<SubChainEntity>)

    /** Drops chain links of user sets whose subscription is no longer a member. */
    @Query(
        "DELETE FROM sub_chain WHERE setId != 0 AND NOT EXISTS " +
            "(SELECT 1 FROM sub_set_member m WHERE m.setId = sub_chain.setId AND m.subId = sub_chain.subId)",
    )
    abstract suspend fun pruneChains()

    @Transaction
    open suspend fun replaceChain(setId: Long, subIds: List<Long>) {
        clearChain(setId)
        insertChain(subIds.mapIndexed { i, sub -> SubChainEntity(setId, sub, i) })
    }

    @Transaction
    open suspend fun deleteSet(id: Long): Int = delete(id).also { pruneChains() }

    /** Members that name no subscription are dropped by the foreign key. */
    @Transaction
    open suspend fun replaceMembers(setId: Long, subIds: Collection<Long>, existingSubs: Set<Long>) {
        clearMembers(setId)
        insertMembers(subIds.filter { it in existingSubs }.map { SubSetMemberEntity(setId, it) })
        pruneChains()
    }

    @Transaction
    open suspend fun replaceSetsOf(subId: Long, setIds: Collection<Long>) {
        clearSetsOf(subId)
        val known = getSets().mapTo(HashSet()) { it.id }
        insertMembers(setIds.filter { it in known }.map { SubSetMemberEntity(it, subId) })
        pruneChains()
    }
}

@Dao
internal interface SelectionDao {
    @Query("SELECT * FROM selection_state WHERE slot = 0")
    fun observeState(): Flow<SelectionStateEntity?>

    @Query("SELECT * FROM selection_state WHERE slot = 0")
    suspend fun getState(): SelectionStateEntity?

    @Upsert
    suspend fun putState(state: SelectionStateEntity)

    @Query("SELECT nodeId FROM disabled_node")
    fun observeDisabled(): Flow<List<String>>

    @Query("SELECT nodeId FROM disabled_node")
    suspend fun getDisabled(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun disable(node: DisabledNodeEntity)

    @Query("DELETE FROM disabled_node WHERE nodeId = :nodeId")
    suspend fun enable(nodeId: String)
}
