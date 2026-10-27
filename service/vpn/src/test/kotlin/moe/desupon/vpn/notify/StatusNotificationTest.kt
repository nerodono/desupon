package moe.desupon.vpn.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import moe.desupon.brain.port.ConnState
import moe.desupon.model.network.NetworkMode
import moe.desupon.model.node.Capability
import moe.desupon.model.node.Node
import moe.desupon.model.node.NodeId
import moe.desupon.model.node.Protocol
import moe.desupon.model.node.Tier
import moe.desupon.vpn.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StatusNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val node = Node(
        id = NodeId("ab12cd34"),
        subs = emptySet(),
        protocol = Protocol.VLESS,
        displayName = "VPN sing-box node example.org",
        country = "NL",
        capability = Capability.SUPPORTED,
        outboundJson = moe.desupon.model.Secret("{}"),
        isEndpoint = false,
        udpOnly = false,
        labels = emptySet(),
    )

    private val connected = StatusContent.Connected(
        node, 1_000, SubscriptionLabel("Финка", 1), rttMs = 45,
        traffic(down = 1024, downTotal = 2L * 1024 * 1024, upTotal = 1024L * 1024),
    )

    private val states = listOf(
        ConnState.Idle,
        ConnState.Connecting(NetworkMode.WHITELIST),
        ConnState.Connected(node, Tier.RU, 1_000),
        ConnState.Switching(node, node),
        ConnState.TurnWarmup("turn vpn"),
        ConnState.Degraded(node, 1_000),
        ConnState.Unstable(node),
        ConnState.Failed("core: sing-box vpn failure"),
    )

    @Test
    fun `notification text is neutral in every state but connected`() {
        val notifications = StatusNotification(context)
        for (state in states.filter { it !is ConnState.Connected }) {
            val n = notifications.build(StatusContent.Plain(state.statusText()))
            val texts = visibleTexts(n)
            assertEquals("Desupon", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(state.statusText()), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            for (t in texts) assertNeutral(t, "$state")
        }
    }

    @Test
    fun `channel is low importance with a neutral name`() {
        val n = StatusNotification(context).build(StatusContent.Plain(R.string.svc_state_connected))
        val channel = manager.getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNeutral(channel.name.toString(), "channel")
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(R.drawable.svc_ic_sprout, n.smallIcon.resId)
    }

    @Test
    fun `connected shows place, subscription, speeds and a session chronometer`() {
        val n = StatusNotification(context).build(connected)
        assertEquals("\uD83C\uDDF3\uD83C\uDDF1 Нидерланды", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("45 мс · ↓ 1 КБ/с · ↑ 0 Б/с", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Финка", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().lines()
        assertEquals(listOf("45 мс · ↓ 1 КБ/с · ↑ 0 Б/с", "За сессию: ↓ 2 МБ · ↑ 1 МБ", "Сервер: ${node.displayName}"), big)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals(1_000L, n.`when`)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `the lock screen gets only the neutral app name and state`() {
        val notifications = StatusNotification(context)
        val contents = states.filter { it !is ConnState.Connected }.map { StatusContent.Plain(it.statusText()) } + connected
        for (content in contents) {
            val n = notifications.build(content)
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            val public = n.publicVersion
            assertEquals("Desupon", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val expected = if (content is StatusContent.Plain) content.text else R.string.svc_state_connected
            assertEquals(context.getString(expected), public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            for (t in visibleTexts(public)) assertNeutral(t, "public $content")
        }
    }

    @Test
    fun `show posts only when the content changes`() {
        val notifications = StatusNotification(context)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        notifications.show(connected)
        val first = shadow.getNotification(StatusNotification.ID)
        notifications.show(connected.copy())
        assertTrue(first === shadow.getNotification(StatusNotification.ID))
        notifications.show(connected.copy(rttMs = 50))
        assertTrue(first !== shadow.getNotification(StatusNotification.ID))
    }

    @Test
    fun `every string of the module is neutral`() {
        val ids = R.string::class.java.fields.map { it.getInt(null) }
        assertTrue(ids.isNotEmpty())
        for (id in ids) assertNeutral(context.getString(id), context.resources.getResourceEntryName(id))
    }

    private fun visibleTexts(n: Notification): List<String> = buildList {
        for (key in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT)) {
            n.extras.getCharSequence(key)?.let { add(it.toString()) }
        }
        n.tickerText?.let { add(it.toString()) }
        n.actions?.forEach { add(it.title.toString()) }
    }

    private fun assertNeutral(text: String, where: String) {
        val lower = text.lowercase()
        for (word in FORBIDDEN) assertTrue(word !in lower, "\"$text\" in $where contains \"$word\"")
    }

    private companion object {
        val FORBIDDEN = listOf("vpn", "впн", "sing-box", "singbox", "libbox", "proxy", "прокси", "v2ray", "xray", "tunnel", "туннел", "example.org", "nl")
    }
}
