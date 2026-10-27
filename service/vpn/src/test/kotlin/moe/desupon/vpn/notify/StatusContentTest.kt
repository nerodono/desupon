package moe.desupon.vpn.notify

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import moe.desupon.data.repo.SubscriptionEntry
import moe.desupon.model.Secret
import moe.desupon.model.network.NetworkKey
import moe.desupon.model.network.Transport
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.probe.ProbeStat
import moe.desupon.model.status.TrafficStatus
import moe.desupon.model.subscription.SubId
import moe.desupon.model.subscription.SubscriptionInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun testNode(name: String = "Amsterdam 3", country: String? = "NL", vararg subs: Long) = Node(
    id = NodeId(name.padEnd(64, '0')),
    subs = subs.map(::SubId).toSet(),
    protocol = Protocol.VLESS,
    displayName = name,
    country = country,
    capability = Capability.SUPPORTED,
    outboundJson = Secret("{}"),
    isEndpoint = false,
    udpOnly = false,
)

internal fun traffic(down: Long = 0, up: Long = 0, downTotal: Long = 0, upTotal: Long = 0) =
    TrafficStatus(uplinkRate = up, downlinkRate = down, uplinkTotal = upTotal, downlinkTotal = downTotal, connectionsIn = 0, connectionsOut = 0)

private const val KB = 1024L
private const val MB = 1024L * 1024

@RunWith(RobolectricTestRunner::class)
class StatusContentTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `rates use binary units, a decimal comma and drop a trailing zero`() {
        assertEquals("0 Б/с", res.rate(0))
        assertEquals("0 Б/с", res.rate(-5))
        assertEquals("512 Б/с", res.rate(512))
        assertEquals("1,5 КБ/с", res.rate(1536))
        assertEquals("64 КБ/с", res.rate(64 * KB))
        assertEquals("1,2 МБ/с", res.rate((1.2 * MB).toLong()))
        assertEquals("150 МБ/с", res.rate(150 * MB))
        assertEquals("152 МБ", res.amount(152 * MB))
        assertEquals("8 МБ", res.amount(8 * MB))
        assertEquals("2,5 ГБ", res.amount((2.5 * 1024 * MB).toLong()))
    }

    @Test
    fun `connected text puts the ping first and the details in the expanded lines`() {
        val c = StatusContent.Connected(
            testNode(), 1_000, SubscriptionLabel("Финка", 1), rttMs = 45,
            traffic((1.2 * MB).toLong(), 64 * KB, 152 * MB, 8 * MB),
        )
        val text = res.connectedText(c)
        assertEquals("🇳🇱 Нидерланды", text.title)
        assertEquals("45 мс · ↓ 1,2 МБ/с · ↑ 64 КБ/с", text.text)
        assertEquals("Финка", text.subText)
        assertEquals("45 мс · ↓ 1,2 МБ/с · ↑ 64 КБ/с\nЗа сессию: ↓ 152 МБ · ↑ 8 МБ\nСервер: Amsterdam 3", text.bigText)
    }

    @Test
    fun `an unknown ping is left out and an untitled subscription is numbered`() {
        val text = res.connectedText(StatusContent.Connected(testNode(), 0, SubscriptionLabel(null, 2), null, traffic(512, 0)))
        assertEquals("↓ 512 Б/с · ↑ 0 Б/с", text.text)
        assertEquals("Подписка 2", text.subText)
        assertNull(res.connectedText(StatusContent.Connected(testNode(), 0, null, null, traffic())).subText)
    }

    @Test
    fun `long country names stay whole and an unknown country shows the node`() {
        assertEquals("🇻🇨 Сент-Винсент и Гренадины", placeTitle(testNode(country = "VC")))
        assertEquals("Amsterdam 3", placeTitle(testNode(country = null)))
        assertEquals("Amsterdam 3", placeTitle(testNode(country = "ZZ")))
        assertEquals("Amsterdam 3", placeTitle(testNode(country = "N1")))
    }

    @Test
    fun `ping is the node's smoothed probe round trip once sampled`() {
        val node = testNode()
        val key = NetworkKey(Transport.WIFI, "home")
        val stat = ProbeStat(node.id, key, ewmaRttMs = 44.6, ok = 3, fail = 0, failStreak = 0, lastStage = null, lastOkAtMs = null, rttSamples = 3)
        assertEquals(45, warmRtt(mapOf(node.id to stat), node))
        assertNull(warmRtt(mapOf(node.id to stat.copy(rttSamples = 0)), node))
        assertNull(warmRtt(emptyMap(), node))
    }

    @Test
    fun `the subscription is the first in list order carrying the node`() {
        fun sub(id: Long, title: String?) = SubscriptionEntry(
            SubscriptionInfo(SubId(id), Secret("https://example.invalid/$id"), title, null, null, null, null, null, null, null),
            nodeCount = 1, isLocal = false, userAgent = null, lastAttemptAtMs = null, skippedCount = null,
        )
        val subs = listOf(sub(1, "A"), sub(2, " "), sub(3, "C"))
        assertEquals(SubscriptionLabel(null, 2), subscriptionOf(subs, testNode("x", "NL", 3, 2)))
        assertEquals(SubscriptionLabel("C", 3), subscriptionOf(subs, testNode("x", "NL", 3)))
        assertNull(subscriptionOf(subs, testNode("x", "NL", 9)))
    }
}
