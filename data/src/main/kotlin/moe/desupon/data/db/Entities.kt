package moe.desupon.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscription", indices = [Index("url")])
internal data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Primary URL; empty for pasted content. */
    val url: String,
    /** User mirrors after [url], newline-separated, tried in order. */
    val mirrors: String? = null,
    /** Imported from pasted text: nothing to fetch. */
    val local: Boolean = false,
    /** User-set name, wins over [title]. */
    val name: String? = null,
    val title: String? = null,
    val userAgent: String? = null,
    val hwid: String,
    /** From the provider header. */
    val updateIntervalH: Int? = null,
    /** User override: null follows [updateIntervalH], [INTERVAL_MANUAL] disables auto-update, else hours. */
    val updateIntervalOverrideH: Int? = null,
    /** Newline-separated mirrors announced by the provider. */
    val altEndpoints: String? = null,
    @Embedded(prefix = "info_") val userInfo: UserInfoColumns? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val announce: String? = null,
    val lastOkAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    /** Entries the last successful parse could not read (unknown, encrypted or broken); null before the first one. */
    val skippedCount: Int? = null,
    val createdAt: Long,
    /** A [moe.desupon.model.subscription.SubscriptionKind] name; [local] is true for every kind but SUBSCRIPTION. */
    @ColumnInfo(defaultValue = "SUBSCRIPTION") val kind: String = KIND_SUBSCRIPTION,
    /** A config's text, a share link or sing-box outbound JSON; null for other kinds. */
    val source: String? = null,
) {
    companion object {
        const val INTERVAL_MANUAL = 0
        const val KIND_SUBSCRIPTION = "SUBSCRIPTION"
    }
}

internal data class UserInfoColumns(
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
)

@Entity(tableName = "node")
internal data class NodeEntity(
    @PrimaryKey val id: String,
    val protocol: String,
    val displayName: String,
    val country: String?,
    val capability: String,
    val outboundJson: String,
    val isEndpoint: Boolean,
    val udpOnly: Boolean,
    val labels: String,
    val unsupportedReason: String?,
)

@Entity(
    tableName = "node_sub",
    primaryKeys = ["nodeId", "subId"],
    foreignKeys = [
        ForeignKey(SubscriptionEntity::class, ["id"], ["subId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(NodeEntity::class, ["id"], ["nodeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("subId")],
)
internal data class NodeSubCrossRef(
    val nodeId: String,
    val subId: Long,
    /** Position of the node in its subscription body. */
    val position: Int,
)

internal data class PoolRow(
    @Embedded val node: NodeEntity,
    @ColumnInfo(name = "ref_subId") val subId: Long,
    @ColumnInfo(name = "ref_position") val position: Int,
)

internal data class SubNodeCount(val subId: Long, val count: Int)

@Entity(tableName = "probe_stat", primaryKeys = ["nodeId", "networkKey"], indices = [Index("networkKey")])
internal data class ProbeStatEntity(
    val nodeId: String,
    val networkKey: String,
    val ewmaRttMs: Double,
    val ok: Int,
    val fail: Int,
    val failStreak: Int,
    val lastStage: String?,
    val lastOkAt: Long?,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val ewmaConnectMs: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val madMs: Double = 0.0,
    val lastFailAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val okRecent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val failRecent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val rttSamples: Int = 0,
    val ewmaKbps: Double? = null,
    @ColumnInfo(defaultValue = "0") val throttled: Boolean = false,
)

@Entity(tableName = "network_memory")
internal data class NetworkMemoryEntity(
    @PrimaryKey val networkKey: String,
    val mode: String,
    val lastGoodNodeId: String?,
    val updatedAt: Long,
)

@Entity(tableName = "split_rule")
internal data class SplitRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "position") val order: Int,
    val enabled: Boolean,
    val kind: String,
    val value: String,
    val action: String,
)

/** A node promoted to the RU tier on one network. */
@Entity(tableName = "tier_learning", primaryKeys = ["networkKey", "nodeId"], indices = [Index("nodeId")])
internal data class TierLearningEntity(
    val networkKey: String,
    val nodeId: String,
    val promotedAt: Long,
)

@Entity(tableName = "sub_set")
internal data class SubSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "sub_set_member",
    primaryKeys = ["setId", "subId"],
    foreignKeys = [
        ForeignKey(SubSetEntity::class, ["id"], ["setId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(SubscriptionEntity::class, ["id"], ["subId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("subId")],
)
internal data class SubSetMemberEntity(val setId: Long, val subId: Long)

/** The single row of global selection state. */
@Entity(tableName = "selection_state")
internal data class SelectionStateEntity(
    @PrimaryKey val slot: Int = SLOT,
    /** 0 or a missing set means every subscription. */
    val activeSetId: Long = 0,
    /** `LocationChoice.encode` form. */
    val location: String = "any",
    /** Country of the pinned node, the fallback once it disappears. */
    val pinCountry: String? = null,
) {
    companion object {
        const val SLOT = 0
    }
}

@Entity(tableName = "disabled_node")
internal data class DisabledNodeEntity(@PrimaryKey val nodeId: String)

/** One link of a set's priority chain; [setId] 0 is the built-in set of every subscription, which has no `sub_set` row to reference. */
@Entity(
    tableName = "sub_chain",
    primaryKeys = ["setId", "subId"],
    foreignKeys = [ForeignKey(SubscriptionEntity::class, ["id"], ["subId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("subId")],
)
internal data class SubChainEntity(val setId: Long, val subId: Long, val position: Int)
