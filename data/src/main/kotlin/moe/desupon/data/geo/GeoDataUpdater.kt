package moe.desupon.data.geo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.desupon.brain.port.Clock
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.data.fetch.FetchError
import moe.desupon.data.fetch.await
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps geoip.dat and geosite.dat fresh and converts the categories of enabled rule-set rules into [RuleSetStore] files.
 * Downloads go over the default network, so through the tunnel while it is up, and a transport failure is retried once on [bypass].
 * A database replaces the previous one only after its published checksum, when there is one, and the core's parser accept it.
 */
class GeoDataUpdater(
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val store: RuleSetStore,
    private val files: GeoDataFiles,
    private val rules: SplitRuleRepo,
    private val bridge: GeoDataBridge,
    private val network: MeteredNetwork,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val bypass: () -> OkHttpClient? = { null },
    private val maxBytes: Long = MAX_BYTES,
) {
    sealed interface Outcome {
        data object NotDue : Outcome

        /** Due, but waiting for an unmetered network. */
        data object Metered : Outcome

        data object NotModified : Outcome

        data class Updated(val categories: Int) : Outcome

        /** [code] is what [GeoFileStatus.lastError] stores. */
        data class Failed(val code: String) : Outcome
    }

    private class Fetched(val tmp: File, val categories: List<GeoCategory>, val url: String, val etag: String?, val sha256: String)

    private sealed interface Attempt {
        class Ready(val file: Fetched) : Attempt

        /** The file on disk is current; [etag] replaces the stored one. */
        class Unchanged(val etag: String?) : Attempt

        class Failed(val code: String) : Attempt
    }

    private sealed interface Download {
        class Saved(val sha256: String, val etag: String?) : Download

        data object NotModified : Download

        class Failed(val code: String) : Download
    }

    private val downloads = Mutex()
    private val conversions = Mutex()
    private var busy = 0
    private val _running = MutableStateFlow(false)

    /** A download or a conversion is in progress. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** [force] skips the schedule and the unmetered requirement; the server may still answer that nothing changed. */
    suspend fun update(force: Boolean): Map<GeoKind, Outcome> {
        downloads.withLock {
            working {
                val config = settings.geoData.first()
                val statuses = settings.geoStatus.first()
                val now = clock.nowMs()
                val due = GeoKind.entries.filter { force || isDue(config, statuses.getValue(it), it, files.dat(it).isFile, now) }
                if (due.isEmpty()) return GeoKind.entries.associateWith { Outcome.NotDue }
                if (!force && config.unmeteredOnly && network.metered() != false) return GeoKind.entries.associateWith { Outcome.Metered }

                val outcomes = LinkedHashMap<GeoKind, Outcome>()
                val fetched = LinkedHashMap<GeoKind, Fetched>()
                for (kind in GeoKind.entries) {
                    if (kind !in due) {
                        outcomes[kind] = Outcome.NotDue
                        continue
                    }
                    val url = config.url(kind)
                    when (val r = download(kind, url, statuses.getValue(kind))) {
                        is Attempt.Ready -> fetched[kind] = r.file
                        is Attempt.Unchanged -> {
                            outcomes[kind] = Outcome.NotModified
                            val at = clock.nowMs()
                            settings.updateGeoStatus(kind) {
                                it.copy(url = url, etag = r.etag, lastOkAtMs = at, lastAttemptAtMs = at, lastError = null)
                            }
                        }
                        is Attempt.Failed -> {
                            outcomes[kind] = Outcome.Failed(r.code)
                            recordFailure(kind, r.code)
                        }
                    }
                }
                if (fetched.isNotEmpty()) outcomes += install(fetched)
                return GeoKind.entries.associateWith { outcomes.getValue(it) }
            }
        }
    }

    /** [update] on the updater's own scope; an automatic one is dropped while another update runs. */
    fun updateInBackground(force: Boolean) {
        if (!force && downloads.isLocked) return
        scope.launch {
            try {
                update(force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Converts the categories of enabled rule-set rules that have no file yet, or every converted one after a failed conversion. */
    suspend fun ensureReferenced() {
        conversions.withLock {
            working {
                val referenced = referencedNames()
                val statuses = settings.geoStatus.first()
                var converted = false
                for (kind in GeoKind.entries) {
                    val retry = statuses.getValue(kind).lastError == ERROR_CONVERT
                    val existing = if (retry) files.convertedNames() else emptySet()
                    val names = files.readIndex(kind).map { it.name }
                        .filter { (it in referenced && (retry || !files.set(it).isFile)) || it in existing }
                    if (names.isNotEmpty()) {
                        convert(kind, names)
                        converted = true
                    }
                }
                if (converted) store.refresh()
            }
        }
    }

    /** Converts what enabled rule-set rules start using; runs until cancelled. */
    suspend fun watchRules() {
        rules.rules.map(::referenced).distinctUntilChanged().conflate().collect {
            try {
                ensureReferenced()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Before a VPN session, while no config references the converted sets: drops the ones no enabled rule uses and leftovers of
     * interrupted writes unless that work is in progress, then converts what is missing in the background.
     */
    suspend fun prepareSession() {
        if (conversions.tryLock()) {
            try {
                val keep = referencedNames()
                val indexed = GeoKind.entries.flatMapTo(HashSet()) { kind -> files.readIndex(kind).map { it.name } }
                withContext(Dispatchers.IO) {
                    files.sets.listFiles().orEmpty().forEach { f ->
                        val name = f.name.removeSuffix(GeoDataFiles.SET_EXT)
                        if (!f.name.endsWith(GeoDataFiles.SET_EXT) || name !in keep || name !in indexed) f.delete()
                    }
                }
                store.refresh()
            } finally {
                conversions.unlock()
            }
        }
        if (downloads.tryLock()) {
            try {
                withContext(Dispatchers.IO) {
                    GeoKind.entries.forEach {
                        tmpOf(it).delete()
                        indexTmpOf(it).delete()
                    }
                }
            } finally {
                downloads.unlock()
            }
        }
        scope.launch {
            try {
                ensureReferenced()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun download(kind: GeoKind, urlText: String, status: GeoFileStatus): Attempt {
        val url = urlText.toHttpUrlOrNull() ?: return Attempt.Failed(ERROR_BAD_URL)
        val known = status.url == urlText && files.dat(kind).isFile && files.index(kind).isFile
        val etag = status.etag?.takeIf { known }
        val tmp = tmpOf(kind)
        var result: Attempt = Attempt.Failed(FetchError.Network.code)
        try {
            var used = client
            var r = fetch(client, url, etag, tmp)
            if (r is Download.Failed && r.code in TRANSPORT_ERRORS) {
                val outside = runCatching { bypass() }.getOrNull()
                if (outside != null) {
                    used = outside
                    r = fetch(outside, url, etag, tmp)
                }
            }
            result = when (r) {
                is Download.Failed -> Attempt.Failed(r.code)
                Download.NotModified -> Attempt.Unchanged(etag)
                is Download.Saved -> verify(kind, tmp, used, url, urlText, r, status.sha256.takeIf { known })
            }
            return result
        } finally {
            if (result !is Attempt.Ready) tmp.delete()
        }
    }

    private suspend fun verify(
        kind: GeoKind,
        tmp: File,
        used: OkHttpClient,
        url: HttpUrl,
        urlText: String,
        saved: Download.Saved,
        knownSha: String?,
    ): Attempt {
        if (saved.sha256 == knownSha) return Attempt.Unchanged(saved.etag)
        val published = publishedSha256(used, url)
        if (published != null && published != saved.sha256) return Attempt.Failed(ERROR_CHECKSUM)
        val categories = try {
            withContext(Dispatchers.IO) { GeoDataFiles.JSON.decodeFromString<List<GeoCategory>>(bridge.index(tmp.path, kind.id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Attempt.Failed(ERROR_INVALID)
        }
        if (categories.isEmpty()) return Attempt.Failed(ERROR_INVALID)
        return Attempt.Ready(Fetched(tmp, categories, urlText, saved.etag, saved.sha256))
    }

    private suspend fun fetch(client: OkHttpClient, url: HttpUrl, etag: String?, tmp: File): Download {
        val request = Request.Builder().url(url).get().apply { if (etag != null) header("If-None-Match", etag) }.build()
        return try {
            client.newCall(request).await().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED && etag != null -> Download.NotModified
                    !response.isSuccessful -> Download.Failed(FetchError.Http(response.code).code)
                    else -> withContext(Dispatchers.IO) { save(response, tmp) }
                }
            }
        } catch (_: InterruptedIOException) {
            Download.Failed(FetchError.Timeout.code)
        } catch (_: IOException) {
            Download.Failed(FetchError.Network.code)
        }
    }

    private suspend fun save(response: Response, tmp: File): Download {
        val body = response.body
        if (body.contentLength() > maxBytes) return Download.Failed(FetchError.TooLarge.code)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        tmp.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return Download.Failed(FetchError.TooLarge.code)
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            out.fd.sync()
        }
        if (total == 0L) return Download.Failed(FetchError.EmptyBody.code)
        return Download.Saved(hex(digest.digest()), response.header("ETag"))
    }

    /** The hash in `<url>.sha256sum`; null when that is missing, unreachable or not in sha256sum format. */
    private suspend fun publishedSha256(client: OkHttpClient, url: HttpUrl): String? {
        val last = url.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val shaUrl = url.newBuilder().setPathSegment(url.pathSize - 1, "$last$SHA_SUFFIX").build()
        return try {
            client.newCall(Request.Builder().url(shaUrl).get().build()).await().use { response ->
                if (response.code != HTTP_OK || response.body.contentLength() > SHA_MAX_BYTES) return null
                val text = withContext(Dispatchers.IO) {
                    val source = response.body.source()
                    val buffer = Buffer()
                    while (source.read(buffer, SHA_MAX_BYTES) != -1L) {
                        if (buffer.size > SHA_MAX_BYTES) return@withContext null
                    }
                    buffer.readUtf8()
                } ?: return null
                text.trim().split(WHITESPACE, limit = 2).first().lowercase(Locale.ROOT).takeIf { SHA_HEX.matches(it) }
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Puts every fetched file in place with its index, then reconverts the sets that come from it. */
    private suspend fun install(fetched: Map<GeoKind, Fetched>): Map<GeoKind, Outcome> {
        conversions.withLock {
            val outcomes = LinkedHashMap<GeoKind, Outcome>()
            val installed = LinkedHashMap<GeoKind, Fetched>()
            for ((kind, f) in fetched) {
                if (withContext(Dispatchers.IO) { place(kind, f) }) {
                    outcomes[kind] = Outcome.Updated(f.categories.size)
                    val now = clock.nowMs()
                    settings.updateGeoStatus(kind) { GeoFileStatus(f.url, f.etag, f.sha256, lastOkAtMs = now, lastAttemptAtMs = now) }
                    installed[kind] = f
                } else {
                    outcomes[kind] = Outcome.Failed(ERROR_STORAGE)
                    recordFailure(kind, ERROR_STORAGE)
                }
            }
            if (installed.isNotEmpty()) {
                val keep = referencedNames() + files.convertedNames()
                for ((kind, f) in installed) convert(kind, f.categories.map { it.name }.filter { it in keep })
                store.refresh()
            }
            return outcomes
        }
    }

    /** The index follows the database, so a crash in between leaves an older index over a newer file, which the next update repairs. */
    private fun place(kind: GeoKind, f: Fetched): Boolean {
        val indexTmp = indexTmpOf(kind)
        try {
            indexTmp.writeText(GeoDataFiles.JSON.encodeToString(f.categories))
            if (!f.tmp.renameTo(files.dat(kind))) return false
            return indexTmp.renameTo(files.index(kind))
        } catch (_: IOException) {
            return false
        } finally {
            f.tmp.delete()
            indexTmp.delete()
        }
    }

    private suspend fun convert(kind: GeoKind, names: List<String>) {
        if (names.isEmpty()) return
        val ok = try {
            withContext(Dispatchers.IO) {
                bridge.convert(files.dat(kind).path, kind.id, GeoDataFiles.JSON.encodeToString(names), files.sets.path)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        settings.updateGeoStatus(kind) { s ->
            when {
                !ok -> s.copy(lastError = ERROR_CONVERT)
                s.lastError == ERROR_CONVERT -> s.copy(lastError = null)
                else -> s
            }
        }
    }

    /** Keeps the file on disk and its validators. */
    private suspend fun recordFailure(kind: GeoKind, code: String) {
        val now = clock.nowMs()
        settings.updateGeoStatus(kind) { it.copy(lastAttemptAtMs = now, lastError = code) }
    }

    private suspend fun referencedNames(): Set<String> = referenced(rules.rules.first())

    private fun tmpOf(kind: GeoKind) = File(files.root, "${kind.id}.dat.tmp")

    private fun indexTmpOf(kind: GeoKind) = File(files.root, "${kind.id}.json.tmp")

    private inline fun <T> working(block: () -> T): T {
        setBusy(1)
        try {
            return block()
        } finally {
            setBusy(-1)
        }
    }

    private fun setBusy(delta: Int) = synchronized(this) {
        busy += delta
        _running.value = busy > 0
    }

    companion object {
        const val ERROR_BAD_URL = "BAD_URL"
        const val ERROR_CHECKSUM = "CHECKSUM"
        const val ERROR_INVALID = "INVALID"
        const val ERROR_STORAGE = "STORAGE"
        const val ERROR_CONVERT = "CONVERT"

        const val MAX_BYTES = 256L * 1024 * 1024
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 6L * 60 * 60 * 1000

        private const val HTTP_OK = 200
        private const val HTTP_NOT_MODIFIED = 304
        private const val CHUNK = 64 * 1024
        private const val SHA_SUFFIX = ".sha256sum"
        private const val SHA_MAX_BYTES = 4096L
        private val SHA_HEX = Regex("[0-9a-f]{64}")
        private val WHITESPACE = Regex("\\s+")
        private val TRANSPORT_ERRORS = setOf(FetchError.Timeout.code, FetchError.Network.code)

        /** No subscription headers: the hosts serving databases have no business knowing the client. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .followSslRedirects(false)
            .build()

        /** Names of the rule-sets enabled rules reference, as the config generator looks them up. */
        fun referenced(rules: List<SplitRule>): Set<String> =
            rules.filter { it.enabled && it.kind == SplitRuleKind.RULE_SET }.mapTo(HashSet()) { it.value.trim() }

        /**
         * Automatic checks only: a new URL or a missing file is due at once, otherwise once the interval passed since the server last
         * confirmed the file; a failure waits [RETRY_AFTER_MS]. A clock set back makes the file due.
         */
        fun isDue(config: GeoDataSettings, status: GeoFileStatus, kind: GeoKind, onDisk: Boolean, nowMs: Long): Boolean {
            val days = config.intervalDays
            if (!config.auto || days == null) return false
            val attempt = status.lastAttemptAtMs
            val failed = status.lastError != null && status.lastError != ERROR_CONVERT
            if (failed && attempt != null && nowMs - attempt in 0 until RETRY_AFTER_MS) return false
            if (!onDisk || status.url != config.url(kind)) return true
            val ok = status.lastOkAtMs ?: return true
            return nowMs < ok || nowMs - ok >= days * DAY_MS
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
