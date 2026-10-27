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
import moe.desupon.data.db.SubscriptionEntity
import moe.desupon.data.fetch.SubscriptionFetcher
import moe.desupon.data.inMemoryDb
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.UpdateInterval
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SubscriptionEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val db = inMemoryDb()
    private val clock = FakeClock()
    private val nodes = NodeRepository(db.nodeDao())
    private val selection = SelectionRepository(db, nodes)
    private lateinit var cache: BodyCache
    private lateinit var repo: SubscriptionRepository

    @Before
    fun setUp() {
        server.start()
        cache = BodyCache(tmp.newFolder("sub"))
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val fetcher = SubscriptionFetcher(client, cache, DeviceInfo(osVersion = "14", model = "Test"), clock)
        repo = SubscriptionRepository(db, fetcher, cache, MutableStateFlow(null), clock, selection)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun ok(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("profile-update-interval", "6")
        .body(body)
        .build()

    private suspend fun add(body: String = Links.body("A"), path: String = "/sub/a"): SubId {
        server.enqueue(ok(body))
        return assertIs<AddResult.Added>(repo.addUrl(url(path))).results.keys.single()
    }

    @Test
    fun pipeJoinedInputBecomesPrimaryAndMirrors() = runTest {
        server.enqueue(ok(Links.body("A")))
        val second = assertIs<AddResult.Added>(repo.addUrl("${url("/sub/b")} | ${url("/m/b")}")).results.keys.single()
        val d = repo.details(second).first()!!
        assertEquals(listOf(url("/sub/b"), url("/m/b")), d.urls.map { it.raw() })
        assertEquals(1, repo.subscriptions.first().first { it.info.id == second }.mirrorCount)
        assertEquals(url("/sub/b"), repo.shareUrl(second))
        assertTrue(d.maskedUrls.all { UrlMask.MASK in it })
        assertTrue(d.urls.toString().contains("***"), "urls never render raw")
    }

    @Test
    fun changedUrlsRefetchUnchangedOnesDoNot() = runTest {
        val id = add()
        assertEquals(EditResult.Saved(null), repo.setUrls(id, listOf(url("/sub/a"))))
        assertEquals(1, server.requestCount)

        server.enqueue(ok(Links.body("B")))
        val r = assertIs<EditResult.Saved>(repo.setUrls(id, listOf(url("/sub/new"), url("/sub/mirror"), url("/sub/new"))))
        assertIs<UpdateResult.Updated>(r.refetch)
        server.takeRequest()
        assertEquals("/sub/new", server.takeRequest().url.encodedPath)
        assertEquals(listOf(url("/sub/new"), url("/sub/mirror")), repo.details(id).first()!!.urls.map { it.raw() })
        assertEquals(listOf("B"), nodes.snapshot().map { it.displayName })
    }

    @Test
    fun badUrlListsChangeNothing() = runTest {
        val a = add(path = "/sub/a")
        val b = add(path = "/sub/b")
        assertEquals(EditResult.InvalidUrl(1), repo.setUrls(a, listOf(url("/x"), "ftp://nope")))
        assertEquals(EditResult.NoUrls, repo.setUrls(a, listOf(" ", "")))
        assertEquals(EditResult.DuplicateUrl(b), repo.setUrls(a, listOf(url("/sub/b"))))
        assertEquals(EditResult.NotFound, repo.setUrls(SubId(99), listOf(url("/x"))))
        assertEquals(url("/sub/a"), repo.shareUrl(a))
    }

    @Test
    fun fullSaveAppliesEveryField() = runTest {
        val id = add()
        val x = selection.createSet("X")!!
        val y = selection.createSet("Y")!!
        val r = repo.save(id, SubscriptionEdit(" Мой ", listOf(url("/sub/a")), " UA/1 ", UpdateInterval.Hours(2), setOf(x, SetId.ALL)))
        assertEquals(EditResult.Saved(null), r)
        val d = repo.details(id).first()!!
        assertEquals("Мой", d.nameOverride)
        assertEquals("UA/1", d.userAgentOverride)
        assertEquals(UpdateInterval.Hours(2), d.intervalOverride)
        assertEquals(UpdateInterval.Hours(2), d.effectiveInterval)
        assertEquals(6, d.providerIntervalH)
        assertEquals(setOf(x), d.sets)
        assertEquals(setOf(x), repo.subscriptions.first().single().sets)

        repo.save(id, SubscriptionEdit(null, listOf(url("/sub/a")), null, null, setOf(y)))
        val cleared = repo.details(id).first()!!
        assertNull(cleared.nameOverride)
        assertEquals(UpdateInterval.Hours(6), cleared.effectiveInterval)
        assertEquals(setOf(y), cleared.sets)
        assertEquals(SubscriptionFetcher.DEFAULT_USER_AGENT, cleared.globalUserAgent)
    }

    @Test
    fun intervalOverrideDrivesAutoUpdates() = runTest {
        val id = add()
        repo.setUpdateInterval(id, UpdateInterval.Manual)
        clock.advance(TimeUnit.DAYS.toMillis(30))
        assertEquals(UpdateResult.NotDue, repo.update(id, FetchTrigger.AUTO))

        repo.setUpdateInterval(id, UpdateInterval.Hours(1))
        server.enqueue(ok(Links.body("A")))
        assertIs<UpdateResult.Updated>(repo.update(id, FetchTrigger.AUTO))
        clock.advance(TimeUnit.MINUTES.toMillis(30))
        assertEquals(UpdateResult.NotDue, repo.update(id, FetchTrigger.AUTO))
    }

    /** A pasted list as earlier versions saved it; new pastes become configs. */
    private suspend fun legacyList(text: String): SubId {
        val id = SubId(db.subscriptionDao().insert(SubscriptionEntity(url = "", local = true, kind = "LIST", hwid = "h", createdAt = 1)))
        cache.write(id, text.toByteArray())
        assertIs<UpdateResult.Updated>(repo.reparseCached(id))
        return id
    }

    @Test
    fun localTextIsEditableAndReparsedOnSave() = runTest {
        val local = legacyList(Links.body("X", "Y"))
        assertEquals(Links.body("X", "Y"), repo.localText(local))
        assertEquals(Links.body("X", "Y"), repo.configSource(local))
        assertNull(repo.shareUrl(local))
        assertTrue(repo.details(local).first()!!.urls.isEmpty())

        assertEquals(UpdateResult.Failed(FetchError.Rejected), repo.setLocalText(local, "nothing here"))
        assertEquals(listOf("X", "Y"), nodes.snapshot().map { it.displayName })

        assertEquals(UpdateResult.Updated(1, 0), repo.setLocalText(local, Links.trojan("Z")))
        assertEquals(listOf("Z"), nodes.snapshot().map { it.displayName })
        assertEquals(Links.trojan("Z"), repo.localText(local))
        val remote = add()
        assertNull(repo.localText(remote))
    }

    @Test
    fun updateThatDropsThePinnedNodeFallsBack() = runTest {
        val nl1 = Links.trojan("NL-1", "nl-1.example.com")
        val nl2 = Links.trojan("NL-2", "nl-2.example.com")
        val id = add("$nl1\n$nl2")
        val pinned = nodes.snapshot().first { it.displayName == "NL-1" }
        assertEquals("NL", pinned.country)
        assertTrue(selection.setLocation(LocationChoice.Node(pinned.id)))

        server.enqueue(ok(nl2))
        repo.update(id)
        assertEquals(LocationChoice.Country("NL"), selection.location.first())

        selection.setLocation(LocationChoice.Node(nodes.snapshot().single().id))
        repo.delete(id)
        assertEquals(LocationChoice.Any, selection.location.first())
    }
}
