package moe.desupon.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.desupon.brain.port.Clock
import moe.desupon.data.db.DesuponDatabase
import moe.desupon.data.db.SubscriptionEntity
import moe.desupon.data.db.toColumns
import moe.desupon.data.db.toEntity
import moe.desupon.data.db.toModel
import moe.desupon.data.fetch.BodyCache
import moe.desupon.data.fetch.FetchError
import moe.desupon.data.fetch.FetchOutcome
import moe.desupon.data.fetch.FetchRequest
import moe.desupon.data.fetch.FetchTrigger
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.model.Secret
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionInfo
import moe.desupon.model.subscription.UpdateInterval
import moe.desupon.parser.ParsedSubscription
import moe.desupon.parser.SubscriptionParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class SubscriptionEntry(
    /** [SubscriptionInfo.url] is the primary URL. */
    val info: SubscriptionInfo,
    val nodeCount: Int,
    /** Imported from pasted text; cannot be updated. */
    val isLocal: Boolean,
    /** Own User-Agent override; null uses the global one. */
    val userAgent: String?,
    val lastAttemptAtMs: Long?,
    /** Entries the last successful parse could not read (unknown, encrypted or broken); null before the first one. */
    val skippedCount: Int?,
    /** User sets containing this subscription; [SetId.ALL] is implied. */
    val sets: Set<SetId> = emptySet(),
    /** Null follows the provider's interval. */
    val intervalOverride: UpdateInterval? = null,
    /** User mirrors after the primary URL. */
    val mirrorCount: Int = 0,
)

/** Everything the subscription editor shows; URLs stay [Secret] and render through [maskedUrls]. */
data class SubscriptionDetails(
    val id: SubId,
    val isLocal: Boolean,
    /** Null shows [providerTitle]. */
    val nameOverride: String?,
    val providerTitle: String?,
    /** Primary first, then the user's mirrors, in fetch order; empty for local subscriptions. */
    val urls: List<Secret>,
    val maskedUrls: List<String>,
    /** Mirrors announced by the provider, tried after [urls]. */
    val providerMirrorCount: Int,
    /** Null uses [globalUserAgent]. */
    val userAgentOverride: String?,
    val globalUserAgent: String,
    /** Null follows [providerIntervalH]. */
    val intervalOverride: UpdateInterval?,
    val providerIntervalH: Int?,
    /** What auto-update uses now. */
    val effectiveInterval: UpdateInterval,
    /** User sets containing this subscription. */
    val sets: Set<SetId>,
    val nodeCount: Int,
    val skippedCount: Int?,
    val lastOkAtMs: Long?,
    val lastAttemptAtMs: Long?,
    val lastError: String?,
)

/** A full editor save; [urls] is ignored for local subscriptions. */
data class SubscriptionEdit(
    val name: String?,
    val urls: List<String>,
    val userAgent: String?,
    val interval: UpdateInterval?,
    val sets: Set<SetId>,
)

sealed interface EditResult {
    /** [refetch] is the update that changed URLs triggered, null when they stayed the same. */
    data class Saved(val refetch: UpdateResult?) : EditResult

    /** Entry [index] of the given list is not an http(s) URL. */
    data class InvalidUrl(val index: Int) : EditResult

    data object NoUrls : EditResult

    /** Another subscription already uses the primary URL. */
    data class DuplicateUrl(val existing: SubId) : EditResult

    data object NotFound : EditResult
}

sealed interface UpdateResult {
    data class Updated(val nodes: Int, val skipped: Int) : UpdateResult

    /** The previous pool and cache stay in place. */
    data class Failed(val error: FetchError) : UpdateResult

    data class Throttled(val retryAtElapsedMs: Long?) : UpdateResult

    /** [FetchTrigger.AUTO] before the update interval elapsed, or with auto-update off. */
    data object NotDue : UpdateResult

    data object Local : UpdateResult

    data object NotFound : UpdateResult
}

sealed interface AddResult {
    /** Subscription URLs saved, each with its first update. */
    data class Added(val results: Map<SubId, UpdateResult>) : AddResult

    /** Pasted nodes saved as a local subscription. */
    data class Imported(val id: SubId, val nodes: Int) : AddResult

    data class AlreadyExists(val id: SubId) : AddResult

    data object NothingFound : AddResult
}

/** Subscriptions and their node lists; an update replaces a subscription's nodes only with a non-empty parse. */
class SubscriptionRepository internal constructor(
    private val db: DesuponDatabase,
    private val fetcher: SubscriptionFetcher,
    private val cache: BodyCache,
    private val globalUserAgent: Flow<String?>,
    private val clock: Clock,
    /** Told about every pool change so a pin that lost its node falls back. */
    private val selection: SelectionRepository? = null,
) {
    private val subs = db.subscriptionDao()
    private val nodes = db.nodeDao()
    private val sets = db.subSetDao()
    private val locks = HashMap<Long, Mutex>()
    private val random = SecureRandom()

    val subscriptions: Flow<List<SubscriptionEntry>> =
        combine(subs.observeAll(), nodes.observeCounts(), sets.observeMembers()) { rows, counts, members ->
            val byId = counts.associate { it.subId to it.count }
            val setsOf = members.groupBy({ it.subId }, { SetId(it.setId) })
            rows.map { it.toEntry(byId[it.id] ?: 0, setsOf[it.id].orEmpty().toSet()) }
        }.distinctUntilChanged()

    fun details(id: SubId): Flow<SubscriptionDetails?> =
        combine(subs.observe(id.value), nodes.observeCounts(), sets.observeSetsOf(id.value), globalUserAgent) { row, counts, setIds, ua ->
            row?.toDetails(counts.firstOrNull { it.subId == id.value }?.count ?: 0, setIds.mapTo(HashSet(), ::SetId), ua)
        }.distinctUntilChanged()

    /** Accepts subscription URLs (one per line, `|` mirrors allowed) or a subscription body / share links. */
    suspend fun addFromText(text: String): AddResult {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return AddResult.NothingFound
        if (lines.all(SubscriptionFetcher::isSubscriptionUrl)) {
            val results = LinkedHashMap<SubId, UpdateResult>()
            for (line in lines) {
                when (val r = addUrl(line)) {
                    is AddResult.Added -> results += r.results
                    is AddResult.AlreadyExists -> if (lines.size == 1) return r
                    else -> Unit
                }
            }
            return AddResult.Added(results)
        }
        return importLocal(text)
    }

    /** [url] may carry `|`-separated mirrors after the primary one. */
    suspend fun addUrl(url: String): AddResult {
        val clean = url.trim()
        if (!SubscriptionFetcher.isSubscriptionUrl(clean)) return AddResult.NothingFound
        val urls = splitUrls(clean)
        subs.findByUrl(urls.first())?.let { return AddResult.AlreadyExists(SubId(it.id)) }
        val id = subs.insert(
            SubscriptionEntity(url = urls.first(), mirrors = joinMirrors(urls), hwid = newHwid(), createdAt = clock.nowMs()),
        )
        return AddResult.Added(mapOf(SubId(id) to update(SubId(id), FetchTrigger.MANUAL)))
    }

    suspend fun update(id: SubId, trigger: FetchTrigger = FetchTrigger.MANUAL): UpdateResult = lock(id).withLock {
        val sub = subs.get(id.value) ?: return UpdateResult.NotFound
        if (sub.local) return UpdateResult.Local
        if (trigger == FetchTrigger.AUTO && !sub.isDue()) return UpdateResult.NotDue
        val urls = sub.urls()
        val request = FetchRequest(
            subId = id,
            endpoints = SubscriptionFetcher.endpoints(urls.first(), urls.drop(1) + sub.altEndpoints?.lines().orEmpty()),
            userAgent = sub.userAgent ?: globalUserAgent.first() ?: SubscriptionFetcher.DEFAULT_USER_AGENT,
            hwid = sub.hwid,
            trigger = trigger,
        )
        val outcome = fetcher.fetch(request) { f ->
            withContext(Dispatchers.Default) { SubscriptionParser.parse(f.body, id, f.headers, f.host) }
                .takeIf { it.nodes.isNotEmpty() }
        }
        when (outcome) {
            is FetchOutcome.Success -> {
                store(sub, outcome.value)
                UpdateResult.Updated(outcome.value.nodes.size, outcome.value.skipped.size)
            }
            is FetchOutcome.Failure -> {
                subs.update(sub.copy(lastAttemptAt = clock.nowMs(), lastError = outcome.error.code))
                UpdateResult.Failed(outcome.error)
            }
            is FetchOutcome.Throttled -> UpdateResult.Throttled(outcome.retryAtElapsedMs)
        }
    }.also { if (it is UpdateResult.Updated) poolChanged() }

    suspend fun updateAll(trigger: FetchTrigger = FetchTrigger.MANUAL): Map<SubId, UpdateResult> =
        subs.getAll().filterNot { it.local }.associate { SubId(it.id) to update(SubId(it.id), trigger) }

    /** Re-reads the cached body of [id] with the current parser, keeping the pool when it yields nothing. */
    suspend fun reparseCached(id: SubId): UpdateResult = lock(id).withLock {
        val sub = subs.get(id.value) ?: return UpdateResult.NotFound
        val body = withContext(Dispatchers.IO) { cache.read(id) } ?: return UpdateResult.Failed(FetchError.EmptyBody)
        val parsed = withContext(Dispatchers.Default) { SubscriptionParser.parse(body, id) }
        if (parsed.nodes.isEmpty()) return UpdateResult.Failed(FetchError.Rejected)
        db.withTransaction {
            subs.update(sub.copy(skippedCount = parsed.skipped.size))
            nodes.replaceForSubscription(id.value, parsed.toEntities())
        }
        UpdateResult.Updated(parsed.nodes.size, parsed.skipped.size)
    }.also { if (it is UpdateResult.Updated) poolChanged() }

    suspend fun delete(id: SubId) {
        lock(id).withLock {
            db.withTransaction {
                subs.delete(id.value)
                nodes.deleteOrphans()
            }
            withContext(Dispatchers.IO) { cache.delete(id) }
        }
        poolChanged()
    }

    /** Null or blank restores the provider title. */
    suspend fun rename(id: SubId, name: String?) = edit(id) { it.copy(name = name?.trim()?.takeIf(String::isNotEmpty)) }

    /** Null or blank falls back to the global User-Agent. */
    suspend fun setUserAgent(id: SubId, userAgent: String?) =
        edit(id) { it.copy(userAgent = userAgent?.trim()?.takeIf(String::isNotEmpty)) }

    /** Null follows the provider's interval. */
    suspend fun setUpdateInterval(id: SubId, interval: UpdateInterval?) = edit(id) { it.copy(updateIntervalOverrideH = interval.toColumn()) }

    /** Primary first; entries may hold `|`-separated mirrors. Changed URLs refetch at once. */
    suspend fun setUrls(id: SubId, urls: List<String>): EditResult {
        val clean = when (val v = validateUrls(id, urls)) {
            is UrlCheck.Bad -> return v.result
            is UrlCheck.Ok -> v.urls
        }
        val changed = lock(id).withLock {
            val sub = subs.get(id.value) ?: return EditResult.NotFound
            if (sub.local) return EditResult.Saved(null)
            val differs = sub.urls() != clean
            if (differs) subs.update(sub.copy(url = clean.first(), mirrors = joinMirrors(clean)))
            differs
        }
        return EditResult.Saved(if (changed) update(id, FetchTrigger.MANUAL) else null)
    }

    /** Applies a whole editor form; validation failures change nothing. */
    suspend fun save(id: SubId, edit: SubscriptionEdit): EditResult {
        val sub = subs.get(id.value) ?: return EditResult.NotFound
        val clean = if (sub.local) {
            null
        } else {
            when (val v = validateUrls(id, edit.urls)) {
                is UrlCheck.Bad -> return v.result
                is UrlCheck.Ok -> v.urls
            }
        }
        val changed = lock(id).withLock {
            val current = subs.get(id.value) ?: return EditResult.NotFound
            val differs = clean != null && current.urls() != clean
            db.withTransaction {
                subs.update(
                    current.copy(
                        url = if (differs) clean!!.first() else current.url,
                        mirrors = if (differs) joinMirrors(clean!!) else current.mirrors,
                        name = edit.name?.trim()?.takeIf(String::isNotEmpty),
                        userAgent = edit.userAgent?.trim()?.takeIf(String::isNotEmpty),
                        updateIntervalOverrideH = edit.interval.toColumn(),
                    ),
                )
                sets.replaceSetsOf(id.value, edit.sets.filterNot { it.isAll }.map { it.value })
            }
            differs
        }
        return EditResult.Saved(if (changed) update(id, FetchTrigger.MANUAL) else null)
    }

    /** The raw primary URL for QR and copy; null for local or missing subscriptions. */
    suspend fun shareUrl(id: SubId): String? = subs.get(id.value)?.takeUnless { it.local }?.url

    /** The pasted text of a local subscription. */
    suspend fun localText(id: SubId): String? {
        val sub = subs.get(id.value)?.takeIf { it.local } ?: return null
        return withContext(Dispatchers.IO) { cache.read(SubId(sub.id)) }?.toString(Charsets.UTF_8)
    }

    /** Replaces a local subscription's text when it parses to at least one node; otherwise nothing changes. */
    suspend fun setLocalText(id: SubId, text: String): UpdateResult = lock(id).withLock {
        val sub = subs.get(id.value) ?: return UpdateResult.NotFound
        if (!sub.local) return UpdateResult.Failed(FetchError.Rejected)
        val parsed = withContext(Dispatchers.Default) { SubscriptionParser.parse(text, id) }
        if (parsed.nodes.isEmpty()) return UpdateResult.Failed(FetchError.Rejected)
        val now = clock.nowMs()
        db.withTransaction {
            subs.update(sub.copy(title = parsed.meta.title ?: sub.title, lastOkAt = now, lastAttemptAt = now, skippedCount = parsed.skipped.size))
            nodes.replaceForSubscription(id.value, parsed.toEntities())
        }
        withContext(Dispatchers.IO) { runCatching { cache.write(id, text.toByteArray(Charsets.UTF_8)) } }
        UpdateResult.Updated(parsed.nodes.size, parsed.skipped.size)
    }.also { if (it is UpdateResult.Updated) poolChanged() }

    private suspend fun edit(id: SubId, change: (SubscriptionEntity) -> SubscriptionEntity): Boolean = lock(id).withLock {
        val sub = subs.get(id.value) ?: return false
        subs.update(change(sub))
        true
    }

    private sealed interface UrlCheck {
        class Ok(val urls: List<String>) : UrlCheck

        class Bad(val result: EditResult) : UrlCheck
    }

    private suspend fun validateUrls(id: SubId, urls: List<String>): UrlCheck {
        val clean = ArrayList<String>()
        for ((i, entry) in urls.withIndex()) {
            val parts = entry.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.any { it.toHttpUrlOrNull() == null }) return UrlCheck.Bad(EditResult.InvalidUrl(i))
            clean += parts
        }
        val distinct = clean.distinct()
        if (distinct.isEmpty()) return UrlCheck.Bad(EditResult.NoUrls)
        subs.findByUrl(distinct.first())?.takeIf { it.id != id.value }?.let { return UrlCheck.Bad(EditResult.DuplicateUrl(SubId(it.id))) }
        return UrlCheck.Ok(distinct)
    }

    private suspend fun importLocal(text: String): AddResult {
        val parsed = withContext(Dispatchers.Default) { SubscriptionParser.parse(text, PENDING_ID) }
        if (parsed.nodes.isEmpty()) return AddResult.NothingFound
        val now = clock.nowMs()
        val id = db.withTransaction {
            val id = subs.insert(
                SubscriptionEntity(
                    url = "",
                    local = true,
                    title = parsed.meta.title,
                    hwid = newHwid(),
                    lastOkAt = now,
                    lastAttemptAt = now,
                    skippedCount = parsed.skipped.size,
                    createdAt = now,
                ),
            )
            nodes.replaceForSubscription(id, parsed.toEntities())
            id
        }
        withContext(Dispatchers.IO) { runCatching { cache.write(SubId(id), text.toByteArray(Charsets.UTF_8)) } }
        return AddResult.Imported(SubId(id), parsed.nodes.size)
    }

    private suspend fun store(sub: SubscriptionEntity, parsed: ParsedSubscription) {
        val meta = parsed.meta
        val now = clock.nowMs()
        val updated = sub.copy(
            title = meta.title ?: sub.title,
            updateIntervalH = meta.updateIntervalH ?: sub.updateIntervalH,
            altEndpoints = meta.altEndpoints.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.raw() } ?: sub.altEndpoints,
            userInfo = meta.userInfo?.toColumns() ?: sub.userInfo,
            supportUrl = meta.supportUrl ?: sub.supportUrl,
            webPageUrl = meta.webPageUrl ?: sub.webPageUrl,
            announce = meta.announce ?: sub.announce,
            lastOkAt = now,
            lastAttemptAt = now,
            lastError = null,
            skippedCount = parsed.skipped.size,
        )
        db.withTransaction {
            subs.update(updated)
            nodes.replaceForSubscription(sub.id, parsed.toEntities())
        }
    }

    private suspend fun poolChanged() {
        selection?.reconcile()
    }

    private fun ParsedSubscription.toEntities() = nodes.map { it.toEntity(unsupportedReasons[it.id]) }

    private fun SubscriptionEntity.urls(): List<String> =
        if (local) emptyList() else listOf(url) + mirrors?.lines()?.filter { it.isNotBlank() }.orEmpty()

    private fun SubscriptionEntity.intervalOverride(): UpdateInterval? = when (val h = updateIntervalOverrideH) {
        null -> null
        SubscriptionEntity.INTERVAL_MANUAL -> UpdateInterval.Manual
        else -> if (h > 0) UpdateInterval.Hours(h) else null
    }

    private fun SubscriptionEntity.effectiveInterval(): UpdateInterval =
        intervalOverride() ?: UpdateInterval.Hours(updateIntervalH?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_H)

    private fun SubscriptionEntity.isDue(): Boolean {
        val hours = when (val i = effectiveInterval()) {
            UpdateInterval.Manual -> return false
            is UpdateInterval.Hours -> i.hours
        }
        val last = lastOkAt ?: return true
        return clock.nowMs() - last >= TimeUnit.HOURS.toMillis(hours.toLong())
    }

    private fun SubscriptionEntity.toEntry(nodeCount: Int, setIds: Set<SetId>) = SubscriptionEntry(
        info = SubscriptionInfo(
            id = SubId(id),
            url = if (local) Secret.EMPTY else Secret(url),
            title = name ?: title,
            userInfo = userInfo?.toModel(),
            updateIntervalH = updateIntervalH,
            supportUrl = supportUrl,
            webPageUrl = webPageUrl,
            announce = announce,
            lastOkAtMs = lastOkAt,
            lastError = lastError,
        ),
        nodeCount = nodeCount,
        isLocal = local,
        userAgent = userAgent,
        lastAttemptAtMs = lastAttemptAt,
        skippedCount = skippedCount,
        sets = setIds,
        intervalOverride = intervalOverride(),
        mirrorCount = (urls().size - 1).coerceAtLeast(0),
    )

    private fun SubscriptionEntity.toDetails(nodeCount: Int, setIds: Set<SetId>, globalUa: String?): SubscriptionDetails {
        val urls = urls()
        return SubscriptionDetails(
            id = SubId(id),
            isLocal = local,
            nameOverride = name,
            providerTitle = title,
            urls = urls.map(::Secret),
            maskedUrls = urls.map(UrlMask::mask),
            providerMirrorCount = altEndpoints?.lines()?.count { it.isNotBlank() } ?: 0,
            userAgentOverride = userAgent,
            globalUserAgent = globalUa ?: SubscriptionFetcher.DEFAULT_USER_AGENT,
            intervalOverride = intervalOverride(),
            providerIntervalH = updateIntervalH,
            effectiveInterval = effectiveInterval(),
            sets = setIds,
            nodeCount = nodeCount,
            skippedCount = skippedCount,
            lastOkAtMs = lastOkAt,
            lastAttemptAtMs = lastAttemptAt,
            lastError = lastError,
        )
    }

    private fun lock(id: SubId): Mutex = synchronized(locks) { locks.getOrPut(id.value) { Mutex() } }

    private fun newHwid(): String {
        val bytes = ByteArray(HWID_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val DEFAULT_INTERVAL_H = 12
        private const val HWID_BYTES = 8
        private val PENDING_ID = SubId(0)

        private fun splitUrls(text: String): List<String> = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        private fun joinMirrors(urls: List<String>): String? = urls.drop(1).joinToString("\n").ifEmpty { null }

        private fun UpdateInterval?.toColumn(): Int? = when (this) {
            null -> null
            UpdateInterval.Manual -> SubscriptionEntity.INTERVAL_MANUAL
            is UpdateInterval.Hours -> hours
        }
    }
}
