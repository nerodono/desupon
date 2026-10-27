package moe.desupon.model.subscription

import moe.desupon.model.Secret

@JvmInline
value class SubId(val value: Long)

/** `subscription-userinfo` header; byte counters and unix seconds, 0 meaning unlimited/none. */
data class UserInfo(
    val upload: Long,
    val download: Long,
    val total: Long,
    val expireEpochSec: Long,
) {
    val hasTrafficLimit: Boolean get() = total > 0
    val hasExpiry: Boolean get() = expireEpochSec > 0
}

data class SubscriptionInfo(
    val id: SubId,
    val url: Secret,
    val title: String?,
    val userInfo: UserInfo?,
    val updateIntervalH: Int?,
    val supportUrl: String?,
    val webPageUrl: String?,
    val announce: String?,
    val lastOkAtMs: Long?,
    val lastError: String?,
)

/** User override of the auto-update interval; no override means the provider's `profile-update-interval`. */
sealed interface UpdateInterval {
    /** Updated only when the user asks. */
    data object Manual : UpdateInterval

    data class Hours(val hours: Int) : UpdateInterval {
        init {
            require(hours > 0) { "hours must be positive" }
        }
    }
}
