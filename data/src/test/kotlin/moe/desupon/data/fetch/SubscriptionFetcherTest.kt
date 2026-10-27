package moe.desupon.data.fetch

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.desupon.data.FakeClock
import moe.desupon.model.Secret
import moe.desupon.model.subscription.SubId
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SubscriptionFetcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val mirror = MockWebServer()
    private val clock = FakeClock()
    private lateinit var cache: BodyCache
    private lateinit var fetcher: SubscriptionFetcher
    private val id = SubId(7)

    private val client = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server.start()
        mirror.start()
        cache = BodyCache(tmp.newFolder("sub"))
        fetcher = SubscriptionFetcher(client, cache, DeviceInfo(osVersion = "14", model = "Pixel Test"), clock)
    }

    @After
    fun tearDown() {
        server.close()
        mirror.close()
    }

    private fun request(vararg urls: String, trigger: FetchTrigger = FetchTrigger.MANUAL) =
        FetchRequest(id, urls.map(::Secret), userAgent = "TestUA/1.0", hwid = "0123456789abcdef", trigger = trigger)

    private val primary get() = server.url("/sub/token").toString()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private suspend fun fetchText(vararg urls: String, trigger: FetchTrigger = FetchTrigger.MANUAL) =
        fetcher.fetch(request(*urls, trigger = trigger)) { String(it.body) }

    @Test
    fun sendsClientHeadersAndNeverAsksForHtml() = runTest {
        server.enqueue(ok("body"))
        assertIs<FetchOutcome.Success<String>>(fetchText(primary))
        val headers = server.takeRequest().headers
        assertEquals("TestUA/1.0", headers["User-Agent"])
        assertEquals("0123456789abcdef", headers["X-HWID"])
        assertEquals("Android", headers["X-Device-OS"])
        assertEquals("14", headers["X-Ver-OS"])
        assertEquals("Pixel Test", headers["X-Device-Model"])
        assertTrue(headers.values("Accept").none { it.contains("text/html") })
    }

    @Test
    fun blankUserAgentFallsBackToDefault() = runTest {
        server.enqueue(ok("body"))
        fetcher.fetch(request(primary).copy(userAgent = " ")) { it }
        assertEquals(SubscriptionFetcher.DEFAULT_USER_AGENT, server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun successCachesBodyAndExposesHeaders() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Subscription-Userinfo", "upload=1; total=2").body("v1").build())
        val r = assertIs<FetchOutcome.Success<String>>(fetchText(primary))
        assertEquals("v1", r.value)
        assertEquals("upload=1; total=2", r.fetched.headers["subscription-userinfo"])
        assertContentEquals("v1".toByteArray(), cache.read(id))
    }

    @Test
    fun errorsNeverOverwriteTheCachedBody() = runTest {
        server.enqueue(ok("good"))
        fetchText(primary)

        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        server.enqueue(ok(" \r\n"))
        server.enqueue(MockResponse.Builder().code(200).body("late").headersDelay(2, TimeUnit.SECONDS).build())
        server.enqueue(ok("<html>not a subscription</html>"))

        assertEquals(FetchError.Http(403), assertIs<FetchOutcome.Failure>(fetchText(primary)).error)
        assertEquals(FetchError.Http(502), assertIs<FetchOutcome.Failure>(fetchText(primary)).error)
        assertEquals(FetchError.EmptyBody, assertIs<FetchOutcome.Failure>(fetchText(primary)).error)
        assertEquals(FetchError.Timeout, assertIs<FetchOutcome.Failure>(fetchText(primary)).error)
        val rejected = fetcher.fetch(request(primary)) { f -> String(f.body).takeUnless { it.startsWith("<html>") } }
        assertEquals(FetchError.Rejected, assertIs<FetchOutcome.Failure>(rejected).error)

        assertContentEquals("good".toByteArray(), cache.read(id))
    }

    @Test
    fun unreachableHostIsANetworkError() = runTest {
        val dead = server.url("/x").toString()
        server.close()
        assertEquals(FetchError.Network, assertIs<FetchOutcome.Failure>(fetchText(dead)).error)
        assertNull(cache.read(id))
    }

    @Test
    fun fallsBackToAlternateEndpointInOrder() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        mirror.enqueue(ok("from mirror"))
        val endpoints = SubscriptionFetcher.endpoints(primary, listOf(mirror.url("/alt").toString()))

        val r = assertIs<FetchOutcome.Success<String>>(fetcher.fetch(request().copy(endpoints = endpoints)) { String(it.body) })
        assertEquals("from mirror", r.value)
        assertEquals(1, r.fetched.endpointIndex)
        assertEquals(1, server.requestCount)
        assertEquals("0123456789abcdef", mirror.takeRequest().headers["X-HWID"])
        assertContentEquals("from mirror".toByteArray(), cache.read(id))
    }

    @Test
    fun pipeSeparatedMirrorsComeBeforeAlternates() {
        val e = SubscriptionFetcher.endpoints(
            " https://a.example.com/s | https://b.example.com/s|ftp://c.example.com/s",
            listOf("https://b.example.com/s", "https://d.example.com/s", "tg://x"),
        ).map { it.raw() }
        assertEquals(listOf("https://a.example.com/s", "https://b.example.com/s", "https://d.example.com/s"), e)
    }

    @Test
    fun allEndpointsFailingReportsEachAndCountsOneFailure() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        mirror.enqueue(MockResponse.Builder().code(500).build())
        val r = assertIs<FetchOutcome.Failure>(fetchText(primary, mirror.url("/m").toString()))
        assertEquals(listOf(FetchError.Http(404), FetchError.Http(500)), r.errors)
        assertEquals(1, fetcher.failures(id))
    }

    @Test
    fun automaticRetriesWaitFifteenMinutes() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        assertIs<FetchOutcome.Failure>(fetchText(primary, trigger = FetchTrigger.AUTO))

        val throttled = assertIs<FetchOutcome.Throttled>(fetchText(primary, trigger = FetchTrigger.AUTO))
        assertEquals(clock.elapsed + TimeUnit.MINUTES.toMillis(15), throttled.retryAtElapsedMs)
        clock.advance(TimeUnit.MINUTES.toMillis(15) - 1)
        assertIs<FetchOutcome.Throttled>(fetchText(primary, trigger = FetchTrigger.AUTO))
        assertEquals(1, server.requestCount)

        clock.advance(1)
        server.enqueue(ok("ok"))
        assertIs<FetchOutcome.Success<String>>(fetchText(primary, trigger = FetchTrigger.AUTO))
        assertEquals(0, fetcher.failures(id))
    }

    @Test
    fun automaticFetchStopsAfterFiveFailuresPerSession() = runTest {
        repeat(5) {
            server.enqueue(MockResponse.Builder().code(500).build())
            assertIs<FetchOutcome.Failure>(fetchText(primary, trigger = FetchTrigger.AUTO))
            clock.advance(TimeUnit.MINUTES.toMillis(15))
        }
        assertNull(assertIs<FetchOutcome.Throttled>(fetchText(primary, trigger = FetchTrigger.AUTO)).retryAtElapsedMs)
        clock.advance(TimeUnit.DAYS.toMillis(1))
        assertIs<FetchOutcome.Throttled>(fetchText(primary, trigger = FetchTrigger.AUTO))
        assertEquals(5, server.requestCount)

        server.enqueue(ok("manual"))
        assertIs<FetchOutcome.Success<String>>(fetchText(primary))
    }

    @Test
    fun manualFetchIgnoresTheRetryInterval() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(ok("again"))
        fetchText(primary)
        assertIs<FetchOutcome.Success<String>>(fetchText(primary))
    }

    @Test
    fun rateLimitIsPerSubscription() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(ok("other"))
        fetchText(primary, trigger = FetchTrigger.AUTO)
        val other = fetcher.fetch(request(primary, trigger = FetchTrigger.AUTO).copy(subId = SubId(8))) { String(it.body) }
        assertIs<FetchOutcome.Success<String>>(other)
    }

    @Test
    fun oversizedBodyIsRejectedWithoutCaching() = runTest {
        val small = SubscriptionFetcher(client, cache, DeviceInfo(osVersion = "", model = ""), clock, FetchPolicy(maxBodyBytes = 16))
        server.enqueue(ok("x".repeat(64)))
        val r = small.fetch(request(primary)) { it }
        assertEquals(FetchError.TooLarge, assertIs<FetchOutcome.Failure>(r).error)
        assertNull(cache.read(id))
        val headers = server.takeRequest().headers
        assertNull(headers["X-Device-Model"])
        assertNull(headers["X-Ver-OS"])
    }

    /** Stands in for the tunnel with a dead proxy: every connect is refused. */
    private fun deadTunnelClient(): OkHttpClient {
        val port = ServerSocket(0).use { it.localPort }
        return client.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()
    }

    @Test
    fun transportFailureIsRetriedOnceOutsideTheTunnel() = runTest {
        var bypassed = 0
        val f = SubscriptionFetcher(deadTunnelClient(), cache, DeviceInfo(osVersion = "", model = ""), clock, bypass = { bypassed++; client })
        server.enqueue(ok("body"))
        val r = f.fetch(request(primary)) { String(it.body) }
        assertEquals("body", assertIs<FetchOutcome.Success<String>>(r).value)
        assertEquals(1, bypassed)
    }

    @Test
    fun withoutBypassTheTransportFailureStands() = runTest {
        val f = SubscriptionFetcher(deadTunnelClient(), cache, DeviceInfo(osVersion = "", model = ""), clock)
        assertEquals(FetchError.Network, assertIs<FetchOutcome.Failure>(f.fetch(request(primary)) { it }).error)
    }

    @Test
    fun httpErrorIsNotRetriedOutsideTheTunnel() = runTest {
        var bypassed = 0
        val f = SubscriptionFetcher(client, cache, DeviceInfo(osVersion = "", model = ""), clock, bypass = { bypassed++; client })
        server.enqueue(MockResponse.Builder().code(502).build())
        assertIs<FetchOutcome.Failure>(f.fetch(request(primary)) { it })
        assertEquals(0, bypassed)
    }
}
