package moe.desupon.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import moe.desupon.data.FakeClock
import moe.desupon.data.Links
import moe.desupon.data.fetch.BodyCache
import moe.desupon.data.fetch.DeviceInfo
import moe.desupon.data.fetch.FetchError
import moe.desupon.data.fetch.FetchTrigger
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.inMemoryDb
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionKind
import moe.desupon.model.subscription.UserInfo
import moe.desupon.parser.ConfigError
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SubscriptionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val db = inMemoryDb()
    private val clock = FakeClock()
    private val globalUa = MutableStateFlow<String?>(null)
    private lateinit var cache: BodyCache
    private lateinit var repo: SubscriptionRepository
    private val nodes = NodeRepository(db.nodeDao())

    @Before
    fun setUp() {
        server.start()
        cache = BodyCache(tmp.newFolder("sub"))
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val fetcher = SubscriptionFetcher(client, cache, DeviceInfo(osVersion = "14", model = "Test"), clock)
        repo = SubscriptionRepository(db, fetcher, cache, globalUa, clock)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun url(path: String = "/sub/a") = server.url(path).toString()

    private fun subResponse(body: String, base64: Boolean = true) = MockResponse.Builder()
        .code(200)
        .addHeader("subscription-userinfo", "upload=10; download=20; total=1000; expire=1900000000")
        .addHeader("profile-title", "base64:" + Base64.getEncoder().encodeToString("Тестовая".toByteArray()))
        .addHeader("profile-update-interval", "6")
        .body(if (base64) Base64.getEncoder().encodeToString(body.toByteArray()) else body)
        .build()

    private suspend fun addOk(body: String = Links.body("A", "B"), path: String = "/sub/a"): SubId {
        server.enqueue(subResponse(body))
        val added = assertIs<AddResult.Added>(repo.addUrl(url(path)))
        val (id, result) = added.results.entries.single()
        assertIs<UpdateResult.Updated>(result)
        return id
    }

    @Test
    fun addingUrlFetchesParsesAndExposesUserInfo() = runTest {
        val id = addOk()
        val entry = repo.subscriptions.first().single()
        assertEquals(id, entry.info.id)
        assertEquals("Тестовая", entry.info.title)
        assertEquals(UserInfo(10, 20, 1000, 1_900_000_000), entry.info.userInfo)
        assertEquals(6, entry.info.updateIntervalH)
        assertEquals(2, entry.nodeCount)
        assertNull(entry.info.lastError)
        assertEquals(listOf("A", "B"), nodes.snapshot().map { it.displayName })
        assertEquals(setOf(id), nodes.snapshot().first().subs)
    }

    @Test
    fun failedUpdateKeepsPoolAndCacheAndRecordsError() = runTest {
        val id = addOk()
        val cached = cache.read(id)!!
        server.enqueue(MockResponse.Builder().code(500).build())

        assertEquals(UpdateResult.Failed(FetchError.Http(500)), repo.update(id))
        assertEquals(listOf("A", "B"), nodes.snapshot().map { it.displayName })
        assertContentEquals(cached, cache.read(id))
        val entry = repo.subscriptions.first().single()
        assertEquals("HTTP_500", entry.info.lastError)
        assertEquals(UserInfo(10, 20, 1000, 1_900_000_000), entry.info.userInfo)
    }

    @Test
    fun bodyWithoutNodesKeepsOldPool() = runTest {
        val id = addOk()
        server.enqueue(MockResponse.Builder().code(200).body("<html><body>login</body></html>").build())

        assertEquals(UpdateResult.Failed(FetchError.Rejected), repo.update(id))
        assertEquals(2, nodes.snapshot().size)
        assertTrue(String(cache.read(id)!!).isNotBlank())
    }

    @Test
    fun successfulUpdateReplacesNodes() = runTest {
        val id = addOk()
        server.enqueue(subResponse(Links.body("B", "C")))
        assertEquals(UpdateResult.Updated(2, 0), repo.update(id))
        assertEquals(listOf("B", "C"), nodes.snapshot().map { it.displayName })
    }

    @Test
    fun sharedNodeSurvivesDeletingOneSubscription() = runTest {
        val a = addOk(Links.body("A", "B"), "/sub/a")
        val b = addOk(Links.body("B", "C"), "/sub/b")
        assertEquals(listOf("A", "B", "C"), nodes.snapshot().map { it.displayName })
        assertEquals(setOf(a, b), nodes.snapshot().first { it.displayName == "B" }.subs)

        repo.delete(a)
        assertEquals(listOf("B", "C"), nodes.snapshot().map { it.displayName })
        assertNull(cache.read(a))
        assertEquals(listOf(b), repo.subscriptions.first().map { it.info.id })
    }

    @Test
    fun eachSubscriptionGetsItsOwnStableHwid() = runTest {
        addOk(path = "/sub/a")
        addOk(path = "/sub/b")
        val first = server.takeRequest().headers["X-HWID"]!!
        val second = server.takeRequest().headers["X-HWID"]!!
        assertNotEquals(first, second)
        assertEquals(16, first.length)

        server.enqueue(subResponse(Links.body("A")))
        repo.update(repo.subscriptions.first().first().info.id)
        assertEquals(first, server.takeRequest().headers["X-HWID"])
    }

    @Test
    fun userAgentOverrideBeatsGlobalWhichBeatsDefault() = runTest {
        val id = addOk()
        assertEquals(SubscriptionFetcher.DEFAULT_USER_AGENT, server.takeRequest().headers["User-Agent"])

        globalUa.value = "Global/1"
        server.enqueue(subResponse(Links.body("A")))
        repo.update(id)
        assertEquals("Global/1", server.takeRequest().headers["User-Agent"])

        repo.setUserAgent(id, "Own/2")
        server.enqueue(subResponse(Links.body("A")))
        repo.update(id)
        assertEquals("Own/2", server.takeRequest().headers["User-Agent"])
        assertEquals("Own/2", repo.subscriptions.first().single().userAgent)
    }

    @Test
    fun automaticUpdateWaitsForTheInterval() = runTest {
        val id = addOk()
        assertEquals(UpdateResult.NotDue, repo.update(id, FetchTrigger.AUTO))
        clock.advance(TimeUnit.HOURS.toMillis(6))
        server.enqueue(subResponse(Links.body("A")))
        assertIs<UpdateResult.Updated>(repo.update(id, FetchTrigger.AUTO))
    }

    @Test
    fun duplicateUrlIsNotAddedTwice() = runTest {
        val id = addOk()
        assertEquals(AddResult.AlreadyExists(id), repo.addUrl(url()))
        assertEquals(1, repo.subscriptions.first().size)
    }

    @Test
    fun pastedLinksBecomeOneConfigEach() = runTest {
        val r = assertIs<ConfigImportResult.Imported>(repo.importConfigs("\n" + Links.body("X", "Y") + "\n"))
        assertEquals(2, r.ids.size)
        assertEquals(2, r.nodes)
        val entries = repo.subscriptions.first()
        assertEquals(listOf(SubscriptionKind.CONFIG, SubscriptionKind.CONFIG), entries.map { it.kind })
        assertTrue(entries.all { it.isLocal && it.info.url.isEmpty() })
        assertEquals(listOf("X", "Y"), entries.map { it.info.title })
        assertEquals(listOf(Links.trojan("X"), Links.trojan("Y")), r.ids.map { repo.configSource(it) })
        assertEquals(UpdateResult.Local, repo.update(r.ids[0]))
        assertEquals(listOf("X", "Y"), nodes.snapshot().map { it.displayName })
        assertEquals(UpdateResult.Updated(1, 0), repo.reparseCached(r.ids[0]))
        assertNull(repo.localText(r.ids[0]))
    }

    @Test
    fun configsAlreadySavedAreSkippedOnImport() = runTest {
        repo.importConfigs(Links.body("X"))
        val r = assertIs<ConfigImportResult.Imported>(repo.importConfigs(Links.body("X", "Y", "Y")))
        assertEquals(1, r.ids.size)
        assertEquals(2, r.duplicates)
        assertEquals(listOf("X", "Y"), repo.subscriptions.first().map { it.info.title })
        assertEquals(ConfigImportResult.Imported(emptyList(), 0, 1, 0), repo.importConfigs(Links.trojan("X")))
    }

    @Test
    fun structuredBodiesImportAsOutboundJson() = runTest {
        val json = """{"outbounds":[
            {"type":"trojan","tag":"J1","server":"j1.example.com","server_port":443,"password":"a","tls":{"enabled":true}},
            {"type":"direct","tag":"direct"},
            {"type":"trojan","tag":"J2","server":"j2.example.com","server_port":443,"password":"b","tls":{"enabled":true}}]}"""
        val r = assertIs<ConfigImportResult.Imported>(repo.importConfigs(json))
        assertEquals(2, r.ids.size)
        val sources = r.ids.map { repo.configSource(it)!! }
        assertTrue(sources.all { it.trimStart().startsWith("{") && "\"tag\"" in it }, sources.toString())
        assertEquals(listOf("J1", "J2"), nodes.snapshot().map { it.displayName })
    }

    @Test
    fun subscriptionAndConfigImportRefuseEachOthersText() = runTest {
        assertEquals(AddResult.LooksLikeConfigs(2), repo.addSubscriptions(Links.body("X", "Y")))
        assertEquals(ConfigImportResult.SubscriptionUrls, repo.importConfigs("${url("/sub/1")}\n${url("/sub/2")}"))
        assertTrue(repo.subscriptions.first().isEmpty())
    }

    @Test
    fun mixedTextAddsUrlsAsSubscriptionsAndTheRestAsConfigs() = runTest {
        val text = "${url("/sub/1")}\n${Links.trojan("X")}\n${Links.trojan("Y")}"
        assertEquals(ImportPreview(urls = 1, configs = 2), repo.preview(text))
        assertEquals(AddResult.LooksLikeConfigs(configs = 2, urls = 1), repo.addSubscriptions(text))
        assertTrue(repo.subscriptions.first().isEmpty())

        server.enqueue(subResponse(Links.body("A")))
        val r = assertIs<ConfigImportResult.Imported>(repo.importConfigs(text))
        assertEquals(2, r.ids.size)
        assertEquals(0, r.skipped)
        assertIs<UpdateResult.Updated>(r.subscriptions.values.single())
        val kinds = repo.subscriptions.first().map { it.kind }
        assertEquals(1, kinds.count { it == SubscriptionKind.SUBSCRIPTION })
        assertEquals(2, kinds.count { it == SubscriptionKind.CONFIG })
        assertEquals(listOf("A", "X", "Y"), nodes.snapshot().map { it.displayName }.sorted())
    }

    @Test
    fun configsCanBeSavedAsOneList() = runTest {
        val body = Links.body("X", "Y", "Z")
        val r = assertIs<ConfigImportResult.Listed>(repo.importConfigs(body, asList = true))
        assertEquals(3, r.nodes)
        val entry = repo.subscriptions.first().single()
        assertEquals(SubscriptionKind.LIST, entry.kind)
        assertEquals(r.id, entry.info.id)
        assertEquals(body, repo.localText(r.id))
        assertEquals(listOf("X", "Y", "Z"), nodes.snapshot().map { it.displayName })
    }

    @Test
    fun severalRowsAreDeletedAtOnce() = runTest {
        val r = assertIs<ConfigImportResult.Imported>(repo.importConfigs(Links.body("X", "Y", "Z")))
        repo.delete(r.ids.take(2))
        assertEquals(listOf("Z"), repo.subscriptions.first().map { it.info.title })
        assertEquals(listOf("Z"), nodes.snapshot().map { it.displayName })
    }

    @Test
    fun pastedUrlsAreAddedAsSubscriptions() = runTest {
        server.enqueue(subResponse(Links.body("A")))
        server.enqueue(subResponse(Links.body("B")))
        val r = assertIs<AddResult.Added>(repo.addSubscriptions("${url("/sub/1")}\n${url("/sub/2")}"))
        assertEquals(2, r.results.size)
        assertEquals(listOf("A", "B"), nodes.snapshot().map { it.displayName })
        assertTrue(repo.subscriptions.first().all { it.kind == SubscriptionKind.SUBSCRIPTION })
    }

    @Test
    fun textWithoutNodesAddsNothing() = runTest {
        assertEquals(AddResult.NothingFound, repo.addSubscriptions("hello there"))
        assertEquals(AddResult.NothingFound, repo.addSubscriptions("   "))
        assertEquals(ConfigImportResult.NothingFound(ConfigError.NotAConfig), repo.importConfigs("hello there"))
        assertEquals(ConfigImportResult.NothingFound(ConfigError.Empty), repo.importConfigs("   "))
        val broken = assertIs<ConfigImportResult.NothingFound>(repo.importConfigs("vless://@h.example.com:443"))
        assertEquals(ConfigError.Malformed("vless", "missing uuid"), broken.error)
        assertTrue(repo.subscriptions.first().isEmpty())
    }

    @Test
    fun configIsCreatedAndEditedOnlyWithValidText() = runTest {
        assertIs<ConfigSaveResult.Invalid>(repo.createConfig("trojan://@nope"))
        assertEquals(ConfigSaveResult.Invalid(ConfigError.Several(2)), repo.createConfig(Links.body("X", "Y")))
        val id = assertIs<ConfigSaveResult.Saved>(repo.createConfig("  " + Links.trojan("X") + "\n")).id
        assertEquals(Links.trojan("X"), repo.configSource(id))
        val before = nodes.snapshot()

        assertIs<ConfigSaveResult.Invalid>(repo.updateConfig(id, "nothing here"))
        assertEquals(before, nodes.snapshot())
        assertEquals(Links.trojan("X"), repo.configSource(id))

        assertEquals(ConfigSaveResult.Saved(id), repo.updateConfig(id, Links.trojan("Z")))
        assertEquals(listOf("Z"), nodes.snapshot().map { it.displayName })
        assertEquals("Z", repo.subscriptions.first().single().info.title)
        assertEquals(Links.trojan("Z"), repo.configSource(id))

        val remote = addOk(path = "/sub/r")
        assertEquals(ConfigSaveResult.NotFound, repo.updateConfig(remote, Links.trojan("Q")))
        assertNull(repo.configSource(remote))
    }

    @Test
    fun renameOverridesProviderTitle() = runTest {
        val id = addOk()
        repo.rename(id, "Мой")
        assertEquals("Мой", repo.subscriptions.first().single().info.title)
        repo.rename(id, " ")
        assertEquals("Тестовая", repo.subscriptions.first().single().info.title)
    }

    @Test
    fun unreadableEntryCountIsStoredWithTheSubscription() = runTest {
        val broken = "hysteria2://pw@h.example.com:443?obfs=salamander"
        val id = addOk(Links.body("A") + "\n" + broken)
        assertEquals(1, repo.subscriptions.first().single().skippedCount)

        server.enqueue(subResponse(Links.body("A", "B")))
        assertIs<UpdateResult.Updated>(repo.update(id))
        assertEquals(0, repo.subscriptions.first().single().skippedCount)

        val configs = assertIs<ConfigImportResult.Imported>(repo.importConfigs(Links.trojan("L") + "\n" + broken))
        assertEquals(1, configs.skipped)
        assertEquals(1, configs.ids.size)
    }
}
