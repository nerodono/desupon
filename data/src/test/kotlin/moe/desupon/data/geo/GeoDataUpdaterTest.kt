package moe.desupon.data.geo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import moe.desupon.brain.port.SplitRuleRepo
import moe.desupon.data.FakeClock
import moe.desupon.data.geo.GeoDataUpdater.Outcome
import moe.desupon.data.ruleset.RuleSetStore
import moe.desupon.data.settings.SettingsRepository
import moe.desupon.model.routing.SplitAction
import moe.desupon.model.routing.SplitRule
import moe.desupon.model.routing.SplitRuleKind
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GeoDataUpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A test database is lines of `CODE count`; anything else is rejected like a malformed file. */
    private class FakeBridge : GeoDataBridge {
        val converted = CopyOnWriteArrayList<Pair<String, List<String>>>()
        var failConvert = false

        override fun index(datPath: String, kind: String): String {
            val categories = File(datPath).readLines().filter { it.isNotBlank() }.map { line ->
                val (code, count) = LINE.matchEntire(line)?.destructured ?: throw IllegalArgumentException("malformed")
                GeoCategory(code, "$kind-${code.lowercase().replace("!", "not-")}", count.toInt())
            }
            return GeoDataFiles.JSON.encodeToString(categories)
        }

        override fun convert(datPath: String, kind: String, namesJson: String, outDir: String): String {
            if (failConvert) throw IllegalStateException("convert failed")
            val names = GeoDataFiles.JSON.decodeFromString<List<String>>(namesJson)
            converted += kind to names
            File(outDir).mkdirs()
            names.forEach { File(outDir, "$it.srs").writeText(File(datPath).readText()) }
            return "[]"
        }

        companion object {
            val LINE = Regex("([A-Z!-]+) (\\d+)")
        }
    }

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = HashMap<String, ArrayDeque<MockResponse>>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val bridge = FakeBridge()
    private val rules = object : SplitRuleRepo {
        override val rules = MutableStateFlow(
            listOf(
                rule(0, "geoip-ru-blocked"),
                rule(1, "geosite-category-ru"),
                rule(2, "geoip-ru", enabled = false),
            ),
        )
    }
    private var metered: Boolean? = false
    private lateinit var files: GeoDataFiles
    private lateinit var settings: SettingsRepository
    private lateinit var store: RuleSetStore

    private val client = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun rule(order: Int, set: String, enabled: Boolean = true) =
        SplitRule(order = order, enabled = enabled, kind = SplitRuleKind.RULE_SET, value = set, action = SplitAction.PROXY)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return synchronized(routes) { routes[request.url.encodedPath]?.removeFirstOrNull() }
                    ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        files = GeoDataFiles(File(tmp.root, "geodata"))
        settings = SettingsRepository(SettingsRepository.createDataStore(File(tmp.root, SettingsRepository.FILE_NAME)))
        store = RuleSetStore(context.assets, File(tmp.root, "ruleset"), files)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun TestScope.updater(client: OkHttpClient = this@GeoDataUpdaterTest.client, bypass: () -> OkHttpClient? = { null }) =
        GeoDataUpdater(client, settings, store, files, rules, bridge, { metered }, clock, backgroundScope, bypass)

    private suspend fun useServer() {
        settings.setGeoUrl(GeoKind.GEOIP, server.url("/geoip.dat").toString())
        settings.setGeoUrl(GeoKind.GEOSITE, server.url("/geosite.dat").toString())
    }

    private fun serve(path: String, vararg responses: MockResponse) = synchronized(routes) {
        routes.getOrPut(path) { ArrayDeque() }.addAll(responses)
    }

    private fun ok(body: String, etag: String? = null) =
        MockResponse.Builder().code(200).body(body).apply { if (etag != null) addHeader("ETag", etag) }.build()

    private fun notModified() = MockResponse.Builder().code(304).build()

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun shaSum(text: String, name: String) = ok("${sha256(text)}  $name\n")

    private fun paths() = requests.map { it.url.encodedPath }

    private suspend fun status(kind: GeoKind) = settings.geoStatus.first().getValue(kind)

    @Test
    fun downloadsVerifiesAndConvertsThenTrustsTheEtag() = runTest {
        useServer()
        val geoip = "RU 3\nRU-BLOCKED 2\n"
        val geosite = "CATEGORY-RU 4\nGEOLOCATION-!CN 1\n"
        serve("/geoip.dat", ok(geoip, "\"g1\""), notModified())
        serve("/geoip.dat.sha256sum", shaSum(geoip, "geoip.dat"))
        serve("/geosite.dat", ok(geosite, "\"s1\""), notModified())
        val u = updater()

        assertEquals(mapOf(GeoKind.GEOIP to Outcome.Updated(2), GeoKind.GEOSITE to Outcome.Updated(2)), u.update(force = false))
        assertEquals(geoip, files.dat(GeoKind.GEOIP).readText())
        assertEquals(geosite, files.dat(GeoKind.GEOSITE).readText())
        assertEquals(listOf("/geoip.dat", "/geoip.dat.sha256sum", "/geosite.dat", "/geosite.dat.sha256sum"), paths())
        assertNull(requests.first().headers["If-None-Match"])
        assertTrue(requests.first().headers.names().none { it.startsWith("X-", ignoreCase = true) }, "no client headers")
        assertEquals(GeoFileStatus(server.url("/geoip.dat").toString(), "\"g1\"", sha256(geoip), clock.now, clock.now), status(GeoKind.GEOIP))

        assertEquals(listOf("geoip" to listOf("geoip-ru-blocked"), "geosite" to listOf("geosite-category-ru")), bridge.converted.toList())
        val sets = store.sets.value
        assertEquals(files.set("geoip-ru-blocked").absolutePath, sets.getValue("geoip-ru-blocked").path)
        assertFalse(sets.getValue("geoip-ru-blocked").domainBased)
        assertFalse("geoip-ru" in sets, "only enabled rules get their category converted")
        assertTrue("geosite-geolocation-not-cn" in store.available.value.map { it.name })
        assertFalse(u.running.value)
        assertTrue(files.root.listFiles()!!.none { it.name.endsWith(".tmp") })

        clock.advance(6 * GeoDataUpdater.DAY_MS)
        requests.clear()
        bridge.converted.clear()
        assertEquals(mapOf(GeoKind.GEOIP to Outcome.NotModified, GeoKind.GEOSITE to Outcome.NotModified), u.update(force = false))
        assertEquals(listOf("/geoip.dat", "/geosite.dat"), paths())
        assertEquals(listOf("\"g1\"", "\"s1\""), requests.map { it.headers["If-None-Match"] })
        assertEquals(clock.now, status(GeoKind.GEOIP).lastOkAtMs)
        assertEquals(geoip, files.dat(GeoKind.GEOIP).readText())
        assertTrue(bridge.converted.isEmpty(), "nothing changed, nothing to convert")
    }

    @Test
    fun aChecksumMismatchOrAnUnreadableFileNeverReplacesTheGoodOne() = runTest {
        useServer()
        val good = "RU 3\n"
        serve("/geoip.dat", ok(good, "\"g1\""))
        serve("/geoip.dat.sha256sum", shaSum(good, "geoip.dat"))
        serve("/geosite.dat", ok("CATEGORY-RU 4\n", "\"s1\""))
        val u = updater()
        u.update(force = true)
        val before = status(GeoKind.GEOIP)

        serve("/geoip.dat", ok("RU 4\n", "\"g2\""), ok("not a database", "\"g3\""), ok(""), MockResponse.Builder().code(503).build())
        serve("/geoip.dat.sha256sum", shaSum("RU 5\n", "geoip.dat"))
        serve("/geosite.dat", notModified(), notModified(), notModified(), notModified())
        val failures = (1..4).map { u.update(force = true).getValue(GeoKind.GEOIP) }
        assertEquals(
            listOf(
                Outcome.Failed(GeoDataUpdater.ERROR_CHECKSUM),
                Outcome.Failed(GeoDataUpdater.ERROR_INVALID),
                Outcome.Failed("EMPTY_BODY"),
                Outcome.Failed("HTTP_503"),
            ),
            failures,
        )
        assertEquals(good, files.dat(GeoKind.GEOIP).readText())
        assertEquals(before.copy(lastAttemptAtMs = clock.now, lastError = "HTTP_503"), status(GeoKind.GEOIP))
        assertEquals(listOf("RU"), store.downloaded.value.getValue(GeoKind.GEOIP).map { it.code })
        assertTrue(files.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun automaticChecksFollowTheScheduleAndWaitForAnUnmeteredNetwork() = runTest {
        useServer()
        val u = updater()
        metered = true
        assertEquals(GeoKind.entries.associateWith { Outcome.Metered }, u.update(force = false))
        metered = null
        assertEquals(GeoKind.entries.associateWith { Outcome.Metered }, u.update(force = false), "no network is no unmetered network")
        assertTrue(requests.isEmpty())

        settings.setGeoUnmeteredOnly(false)
        metered = true
        serve("/geoip.dat", ok("RU 1\n", "\"g1\""), notModified())
        serve("/geosite.dat", ok("CATEGORY-RU 1\n", "\"s1\""), notModified())
        assertEquals(Outcome.Updated(1), u.update(force = false).getValue(GeoKind.GEOIP))

        requests.clear()
        clock.advance(4 * GeoDataUpdater.DAY_MS)
        assertEquals(GeoKind.entries.associateWith { Outcome.NotDue }, u.update(force = false))
        settings.setGeoInterval(3)
        assertEquals(Outcome.NotModified, u.update(force = false).getValue(GeoKind.GEOIP))
        assertEquals(2, requests.size)

        settings.setGeoInterval(null)
        clock.advance(30 * GeoDataUpdater.DAY_MS)
        assertEquals(GeoKind.entries.associateWith { Outcome.NotDue }, u.update(force = false))
        settings.setGeoInterval(1)
        settings.setGeoAuto(false)
        assertEquals(GeoKind.entries.associateWith { Outcome.NotDue }, u.update(force = false))

        settings.setGeoUnmeteredOnly(true)
        serve("/geoip.dat", notModified())
        serve("/geosite.dat", notModified())
        assertEquals(GeoKind.entries.associateWith { Outcome.NotModified }, u.update(force = true), "a manual update skips both gates")
    }

    @Test
    fun dueLogic() {
        val config = GeoDataSettings(intervalDays = 5)
        val url = config.url(GeoKind.GEOIP)
        val day = GeoDataUpdater.DAY_MS
        val now = 100 * day
        fun due(status: GeoFileStatus, onDisk: Boolean = true, c: GeoDataSettings = config) = GeoDataUpdater.isDue(c, status, GeoKind.GEOIP, onDisk, now)

        assertTrue(due(GeoFileStatus()), "never downloaded")
        assertTrue(due(GeoFileStatus(url, lastOkAtMs = now), onDisk = false), "file gone")
        assertTrue(due(GeoFileStatus("https://old.example/geoip.dat", lastOkAtMs = now)), "new URL")
        assertFalse(due(GeoFileStatus(url, lastOkAtMs = now - 4 * day)))
        assertTrue(due(GeoFileStatus(url, lastOkAtMs = now - 5 * day)))
        assertTrue(due(GeoFileStatus(url, lastOkAtMs = now + day)), "clock set back")
        assertFalse(due(GeoFileStatus(url, lastOkAtMs = now - 9 * day, lastAttemptAtMs = now - 1000, lastError = "NETWORK")), "failure backoff")
        assertTrue(due(GeoFileStatus(url, lastOkAtMs = now - 9 * day, lastAttemptAtMs = now - GeoDataUpdater.RETRY_AFTER_MS, lastError = "NETWORK")))
        assertTrue(
            due(GeoFileStatus(url, lastOkAtMs = now - 9 * day, lastAttemptAtMs = now - 1000, lastError = GeoDataUpdater.ERROR_CONVERT)),
            "a failed conversion is no download failure",
        )
        assertFalse(due(GeoFileStatus(), c = config.copy(intervalDays = null)))
        assertFalse(due(GeoFileStatus(), c = config.copy(auto = false)))
    }

    @Test
    fun aTransportFailureIsRetriedOnceOutsideTheTunnel() = runTest {
        useServer()
        serve("/geoip.dat", ok("RU 1\n"))
        serve("/geosite.dat", ok("CATEGORY-RU 1\n"))
        val port = ServerSocket(0).use { it.localPort }
        val deadTunnel = client.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).build()
        var bypassed = 0
        val u = updater(deadTunnel, bypass = { bypassed++; client })
        assertEquals(GeoKind.entries.associateWith { Outcome.Updated(1) }, u.update(force = true))
        assertEquals(2, bypassed)

        val stuck = updater(deadTunnel)
        assertEquals(Outcome.Failed("NETWORK"), stuck.update(force = true).getValue(GeoKind.GEOIP))
        assertEquals("RU 1\n", files.dat(GeoKind.GEOIP).readText())
    }

    @Test
    fun categoriesAreConvertedWhenRulesStartUsingThemAndRefreshedWithTheirDatabase() = runTest {
        useServer()
        serve("/geoip.dat", ok("RU 3\nRU-BLOCKED 2\nPRIVATE 1\n", "\"g1\""), ok("RU 4\nRU-BLOCKED 3\nPRIVATE 1\n", "\"g2\""))
        serve("/geosite.dat", ok("CATEGORY-RU 4\n", "\"s1\""), notModified())
        val u = updater()
        u.update(force = true)
        bridge.converted.clear()

        rules.rules.value = rules.rules.value.map { if (it.value == "geoip-ru") it.copy(enabled = true) else it } +
            rule(3, "geoip-not-in-the-database")
        u.ensureReferenced()
        assertEquals(listOf("geoip" to listOf("geoip-ru")), bridge.converted.toList(), "only what is listed and missing")
        assertEquals(files.set("geoip-ru").absolutePath, store.sets.value.getValue("geoip-ru").path)

        bridge.converted.clear()
        rules.rules.value = rules.rules.value.filter { it.value != "geoip-ru" }
        assertEquals(GeoKind.entries.associateWith { if (it == GeoKind.GEOIP) Outcome.Updated(3) else Outcome.NotModified }, u.update(force = true))
        assertEquals(listOf("geoip" to listOf("geoip-ru", "geoip-ru-blocked")), bridge.converted.toList(), "every converted set follows its database")
        assertEquals("RU 4\nRU-BLOCKED 3\nPRIVATE 1\n", files.set("geoip-ru").readText())

        bridge.failConvert = true
        rules.rules.value = rules.rules.value + rule(4, "geoip-private")
        u.ensureReferenced()
        assertEquals(GeoDataUpdater.ERROR_CONVERT, status(GeoKind.GEOIP).lastError)
        assertFalse("geoip-private" in store.sets.value)

        bridge.failConvert = false
        bridge.converted.clear()
        u.ensureReferenced()
        assertEquals(listOf("geoip-private", "geoip-ru", "geoip-ru-blocked"), bridge.converted.single().second.sorted(), "a failed conversion redoes all")
        assertNull(status(GeoKind.GEOIP).lastError)
        assertTrue("geoip-private" in store.sets.value)
    }

    @Test
    fun sessionStartDropsSetsNoEnabledRuleUses() = runTest {
        useServer()
        serve("/geoip.dat", ok("RU 3\nRU-BLOCKED 2\n"))
        serve("/geosite.dat", ok("CATEGORY-RU 4\n"))
        val u = updater()
        u.update(force = true)
        files.set("geoip-ru").writeText("stale")
        files.set("geoip-gone").writeText("not in the database")
        File(files.sets, "geoip-ru-blocked.srs.tmp").writeText("leftover")
        File(files.root, "geosite.dat.tmp").writeText("leftover")
        store.refresh()
        assertTrue("geoip-ru" in store.sets.value)

        u.prepareSession()
        assertEquals(setOf("geoip-ru-blocked.srs", "geosite-category-ru.srs"), files.sets.list()!!.toSet())
        assertFalse(File(files.root, "geosite.dat.tmp").exists())
        assertFalse("geoip-ru" in store.sets.value)
        assertEquals(files.set("geoip-ru-blocked").absolutePath, store.sets.value.getValue("geoip-ru-blocked").path)
    }
}
