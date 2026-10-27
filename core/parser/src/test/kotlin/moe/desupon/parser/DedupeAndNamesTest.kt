package moe.desupon.parser

import moe.desupon.model.subscription.SubId
import moe.desupon.parser.internal.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DedupeAndNamesTest {
    private val base = "trojan://pw@d.example.com:443?sni=d.example.com"

    @Test
    fun `duplicates inside one subscription keep the first`() {
        val r = Fx.parse("$base#first\n$base#second\n${base.replace(":443", ":8443")}#other\n")
        assertEquals(listOf("first", "other"), r.nodes.map { it.displayName })
        assertEquals(1, r.duplicates)
    }

    @Test
    fun `identity ignores name but not connection fields`() {
        val a = Fx.single("$base#A")
        assertEquals(a.id, Fx.single("$base#B").id)
        assertEquals(a.id, Fx.single(base.replace("d.example.com:", "D.EXAMPLE.com:")).id)
        assertNotEquals(a.id, Fx.single(base.replace("pw@", "pw2@")).id)
        assertNotEquals(a.id, Fx.single("$base&type=ws&path=/x").id)
        assertNotEquals(a.id, Fx.single(base.replace("sni=d.example.com", "sni=e.example.com")).id)
        val hy = "hysteria2://pw@h.example.com:443?obfs=salamander&obfs-password="
        assertNotEquals(Fx.single(hy + "a").id, Fx.single(hy + "b").id)
    }

    @Test
    fun `same server from different formats dedupes`() {
        val yaml = """
            proxies:
              - {name: y, type: trojan, server: d.example.com, port: 443, password: pw, sni: d.example.com}
        """.trimIndent()
        assertEquals(Fx.single(base).id, Fx.parse(yaml).nodes.single().id)
    }

    @Test
    fun `pool merge unions subscriptions`() {
        val s1 = SubscriptionParser.parse("$base#from-1\n${base.replace("pw@", "x@")}#only-1", SubId(1)).nodes
        val s2 = SubscriptionParser.parse("$base#from-2", SubId(2)).nodes
        val pool = NodePool.merge(s1, s2)
        assertEquals(2, pool.size)
        assertEquals(setOf(SubId(1), SubId(2)), pool[0].subs)
        assertEquals("from-1", pool[0].displayName)
        assertEquals(setOf(SubId(1)), pool[1].subs)
        assertEquals(pool, NodePool.merge(listOf(s1, s2)))
    }

    @Test
    fun `country from flag, then iso token`() {
        val c = Normalizer::country
        assertEquals("NL", c("🇳🇱 VLESS"))
        assertEquals("GB", c("🇬🇧 London"))
        assertEquals("GB", c("UK London"))
        assertEquals("DE", c("Germany DE-1"))
        assertEquals("RU", c("Moscow | RU🇷🇺"))
        assertNull(c("WS TLS"))
        assertNull(c("FreeTurn"))
        assertNull(c("🇪🇺 EU"))
        assertNull(c("us-west"))
        assertNull(c("VLESS"))
    }

    @Test
    fun `labels from names`() {
        val l = Normalizer::labels
        assertEquals(setOf(NodeLabels.ONLY_MOBILE), l("🇷🇺 Только для мобильных"))
        assertEquals(setOf(NodeLabels.ONLY_MOBILE, NodeLabels.LTE), l("only mobile LTE"))
        assertEquals(setOf(NodeLabels.ONLY_MOBILE), l("NL mobile-only"))
        assertEquals(setOf(NodeLabels.ONLY_WIFI), l("WiFi only"))
        assertEquals(setOf(NodeLabels.ONLY_WIFI), l("только Wi-Fi"))
        assertEquals(setOf(NodeLabels.WHITELIST), l("RU WL обход"))
        assertEquals(setOf(NodeLabels.WHITELIST), l("Белые списки"))
        assertEquals(setOf(NodeLabels.WHITELIST, NodeLabels.LTE), l("БС 4G"))
        assertEquals(emptySet(), l("🇳🇱 VLESS"))
        assertEquals(emptySet(), l("wlan-node"))
        assertEquals(emptySet(), l("Bullet LTEX"))
    }
}
