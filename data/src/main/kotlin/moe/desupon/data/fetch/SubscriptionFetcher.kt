package moe.desupon.data.fetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.desupon.brain.port.Clock
import moe.desupon.model.Secret
import moe.desupon.model.subscription.SubId
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

enum class FetchTrigger {
    /** User asked: skips the retry interval and the session failure cap. */
    MANUAL,

    /** Background or startup refresh. */
    AUTO,
}

data class FetchRequest(
    val subId: SubId,
    /** Tried in order until one yields an accepted body; see [SubscriptionFetcher.endpoints]. */
    val endpoints: List<Secret>,
    val userAgent: String,
    val hwid: String,
    val trigger: FetchTrigger,
)

class FetchedBody(
    val endpointIndex: Int,
    val url: Secret,
    /** Lower-case names; repeated headers joined with commas. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val host: String? get() = url.raw().toHttpUrlOrNull()?.host
}

/** Failure causes without URLs or bodies; [code] is stable for persistence. */
sealed interface FetchError {
    val code: String

    data class Http(val status: Int) : FetchError {
        override val code get() = "HTTP_$status"
    }

    data object Timeout : FetchError {
        override val code get() = "TIMEOUT"
    }

    data object Network : FetchError {
        override val code get() = "NETWORK"
    }

    data object EmptyBody : FetchError {
        override val code get() = "EMPTY_BODY"
    }

    data object TooLarge : FetchError {
        override val code get() = "TOO_LARGE"
    }

    /** The body arrived but the caller found nothing usable in it. */
    data object Rejected : FetchError {
        override val code get() = "NO_NODES"
    }

    data object NoEndpoints : FetchError {
        override val code get() = "NO_ENDPOINTS"
    }
}

sealed interface FetchOutcome<out T> {
    data class Success<T>(val value: T, val fetched: FetchedBody) : FetchOutcome<T>

    /** [errors] has one entry per endpoint tried, in order. */
    data class Failure(val errors: List<FetchError>) : FetchOutcome<Nothing> {
        val error: FetchError get() = errors.firstOrNull() ?: FetchError.NoEndpoints
    }

    /** Not attempted; [retryAtElapsedMs] is null once the session failure cap is hit. */
    data class Throttled(val retryAtElapsedMs: Long?) : FetchOutcome<Nothing>
}

data class FetchPolicy(
    val minRetryIntervalMs: Long = TimeUnit.MINUTES.toMillis(15),
    val maxFailuresPerSession: Int = 5,
    val maxBodyBytes: Long = 16L * 1024 * 1024,
)

/**
 * Subscription downloads over the default network; when the tunnel is up the config routes the app through it,
 * and a transport failure is retried once on [bypass], a client outside the tunnel.
 * The last accepted body is cached per subscription and is never replaced by an error, timeout or empty response.
 */
class SubscriptionFetcher(
    private val client: OkHttpClient,
    private val cache: BodyCache,
    private val device: DeviceInfo,
    private val clock: Clock,
    private val policy: FetchPolicy = FetchPolicy(),
    private val bypass: () -> OkHttpClient? = { null },
) {
    private class Attempts(var lastAtElapsed: Long, var failures: Int)

    private val attempts = HashMap<Long, Attempts>()

    /**
     * Tries every endpoint until [accept] returns non-null for a body; only then the body goes to the cache.
     * A [FetchTrigger.AUTO] request is throttled to one per [FetchPolicy.minRetryIntervalMs] and stops after
     * [FetchPolicy.maxFailuresPerSession] failures.
     */
    suspend fun <T : Any> fetch(request: FetchRequest, accept: suspend (FetchedBody) -> T?): FetchOutcome<T> {
        throttle(request)?.let { return it }
        val errors = ArrayList<FetchError>()
        for ((index, endpoint) in request.endpoints.withIndex()) {
            val url = endpoint.raw().toHttpUrlOrNull()
            if (url == null) {
                errors += FetchError.NoEndpoints
                continue
            }
            val fetched = when (val r = download(index, endpoint, url, request)) {
                is Downloaded.Error -> {
                    errors += r.error
                    continue
                }
                is Downloaded.Body -> r.body
            }
            val value = try {
                accept(fetched)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (value == null) {
                errors += FetchError.Rejected
                continue
            }
            withContext(Dispatchers.IO) { runCatching { cache.write(request.subId, fetched.body) } }
            record(request.subId, ok = true)
            return FetchOutcome.Success(value, fetched)
        }
        record(request.subId, ok = false)
        return FetchOutcome.Failure(errors.ifEmpty { listOf(FetchError.NoEndpoints) })
    }

    /** Failures counted against [subId] in this session. */
    fun failures(subId: SubId): Int = synchronized(attempts) { attempts[subId.value]?.failures ?: 0 }

    private fun throttle(request: FetchRequest): FetchOutcome.Throttled? = synchronized(attempts) {
        val now = clock.elapsedMs()
        val a = attempts[request.subId.value]
        if (request.trigger == FetchTrigger.AUTO && a != null) {
            if (a.failures >= policy.maxFailuresPerSession) return FetchOutcome.Throttled(null)
            val next = a.lastAtElapsed + policy.minRetryIntervalMs
            if (now < next) return FetchOutcome.Throttled(next)
        }
        if (a == null) attempts[request.subId.value] = Attempts(now, 0) else a.lastAtElapsed = now
        null
    }

    private fun record(subId: SubId, ok: Boolean) = synchronized(attempts) {
        val a = attempts.getOrPut(subId.value) { Attempts(clock.elapsedMs(), 0) }
        a.failures = if (ok) 0 else a.failures + 1
    }

    private sealed interface Downloaded {
        class Body(val body: FetchedBody) : Downloaded

        class Error(val error: FetchError) : Downloaded
    }

    private suspend fun download(index: Int, endpoint: Secret, url: HttpUrl, request: FetchRequest): Downloaded {
        val first = download(client, index, endpoint, url, request)
        if (first !is Downloaded.Error || (first.error != FetchError.Timeout && first.error != FetchError.Network)) return first
        val outside = runCatching { bypass() }.getOrNull() ?: return first
        return download(outside, index, endpoint, url, request)
    }

    private suspend fun download(client: OkHttpClient, index: Int, endpoint: Secret, url: HttpUrl, request: FetchRequest): Downloaded {
        val call = client.newCall(buildRequest(url, request))
        return try {
            call.await().use { response ->
                if (!response.isSuccessful) return Downloaded.Error(FetchError.Http(response.code))
                val bytes = withContext(Dispatchers.IO) { readLimited(response) }
                    ?: return Downloaded.Error(FetchError.TooLarge)
                if (isBlank(bytes)) return Downloaded.Error(FetchError.EmptyBody)
                Downloaded.Body(FetchedBody(index, endpoint, headerMap(response), bytes))
            }
        } catch (_: InterruptedIOException) {
            Downloaded.Error(FetchError.Timeout)
        } catch (_: IOException) {
            Downloaded.Error(FetchError.Network)
        }
    }

    private fun buildRequest(url: HttpUrl, request: FetchRequest): Request = Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", request.userAgent.ifBlank { DEFAULT_USER_AGENT })
        // An HTML Accept makes 3x-ui serve its web page instead of the subscription.
        .header("Accept", "*/*")
        .apply {
            if (request.hwid.isNotBlank()) header("X-HWID", request.hwid)
            if (device.os.isNotBlank()) header("X-Device-OS", device.os)
            if (device.osVersion.isNotBlank()) header("X-Ver-OS", device.osVersion)
            if (device.model.isNotBlank()) header("X-Device-Model", device.model)
        }
        .build()

    private fun readLimited(response: Response): ByteArray? {
        val body = response.body
        if (body.contentLength() > policy.maxBodyBytes) return null
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, READ_CHUNK) != -1L) {
            if (buffer.size > policy.maxBodyBytes) return null
        }
        return buffer.readByteArray()
    }

    private fun isBlank(bytes: ByteArray): Boolean = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").isBlank()

    private fun headerMap(response: Response): Map<String, String> =
        response.headers.toMultimap().mapValues { (_, v) -> v.joinToString(",") }

    companion object {
        const val DEFAULT_USER_AGENT = "v2rayNG/2.3.13"
        private const val READ_CHUNK = 8192L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()

        /** `|`-separated mirrors of [primary] first, then [alternates]; non-http(s) entries and duplicates dropped. */
        fun endpoints(primary: String, alternates: List<String> = emptyList()): List<Secret> =
            (primary.split('|') + alternates)
                .map { it.trim() }
                .filter { it.toHttpUrlOrNull() != null }
                .distinct()
                .map(::Secret)

        fun isSubscriptionUrl(text: String): Boolean {
            val parts = text.trim().split('|').map { it.trim() }
            return parts.isNotEmpty() && parts.all { it.toHttpUrlOrNull() != null }
        }
    }
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
