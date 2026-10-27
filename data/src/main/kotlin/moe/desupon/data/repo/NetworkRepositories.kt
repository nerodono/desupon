package moe.desupon.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.NetworkMemoryRepo
import moe.desupon.brain.port.ProbeStatRepo
import moe.desupon.brain.port.TierLearningRepo
import moe.desupon.data.db.NetworkMemoryDao
import moe.desupon.data.db.ProbeStatDao
import moe.desupon.data.db.TierLearningDao
import moe.desupon.data.db.TierLearningEntity
import moe.desupon.data.db.toEntity
import moe.desupon.data.db.toModel
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.NetworkMemory
import moe.desupon.model.node.NodeId
import moe.desupon.model.probe.ProbeStat
import java.util.concurrent.TimeUnit

class NetworkMemoryRepository internal constructor(
    private val memory: NetworkMemoryDao,
    private val stats: ProbeStatDao,
    private val clock: Clock,
) : NetworkMemoryRepo {
    override suspend fun get(key: NetworkKey): NetworkMemory? = memory.get(key.encode())?.toModel(key)

    override suspend fun put(memory: NetworkMemory) = this.memory.upsert(memory.toEntity())

    /** Drops network memories and probe stats not updated since [epochMs]. */
    override suspend fun pruneOlderThan(epochMs: Long) {
        memory.pruneOlderThan(epochMs)
        stats.pruneOlderThan(epochMs)
    }

    suspend fun pruneExpired(ttlMs: Long = DEFAULT_TTL_MS) = pruneOlderThan(clock.nowMs() - ttlMs)

    companion object {
        val DEFAULT_TTL_MS: Long = TimeUnit.DAYS.toMillis(30)
    }
}

class ProbeStatRepository internal constructor(
    private val dao: ProbeStatDao,
    private val clock: Clock,
) : ProbeStatRepo {
    override suspend fun forNetwork(key: NetworkKey): Map<NodeId, ProbeStat> =
        dao.forNetwork(key.encode()).associate { NodeId(it.nodeId) to it.toModel(key) }

    fun observe(key: NetworkKey): Flow<Map<NodeId, ProbeStat>> =
        dao.observeForNetwork(key.encode()).map { rows -> rows.associate { NodeId(it.nodeId) to it.toModel(key) } }.distinctUntilChanged()

    override suspend fun upsert(stats: Collection<ProbeStat>) {
        if (stats.isEmpty()) return
        val now = clock.nowMs()
        dao.upsert(stats.map { it.toEntity(now) })
    }
}

class TierLearningRepository internal constructor(
    private val dao: TierLearningDao,
    private val clock: Clock,
) : TierLearningRepo {
    override suspend fun promoted(key: NetworkKey): Set<NodeId> = dao.promoted(key.encode()).mapTo(HashSet(), ::NodeId)

    override suspend fun promote(key: NetworkKey, nodes: Set<NodeId>) {
        if (nodes.isEmpty()) return
        val network = key.encode()
        val now = clock.nowMs()
        dao.insert(nodes.map { TierLearningEntity(network, it.hash, now) })
    }
}
