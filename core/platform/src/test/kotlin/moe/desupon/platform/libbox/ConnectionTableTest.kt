package moe.desupon.platform.libbox

import moe.desupon.model.status.ConnInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionTableTest {
    private fun conn(id: String, outbound: String, chain: List<String>, up: Long = 0, down: Long = 0) =
        ConnInfo(id, "tcp", "192.0.2.1:443", null, outbound, chain, up, down, 1_000, false)

    @Test
    fun appliesNewUpdateAndClose() {
        val table = ConnectionTable(closedRetentionMs = 1_000)
        table.apply(true, listOf(ConnEvent.New(conn("1", "n-a", listOf("proxy", "n-a")))), 0)
        var list = table.apply(false, listOf(ConnEvent.Update("1", 10, 20), ConnEvent.Update("1", 5, 0)), 10)
        assertEquals(15, list.single().uplinkTotal)
        assertEquals(20, list.single().downlinkTotal)

        list = table.apply(false, listOf(ConnEvent.Closed("1", 100, null)), 100)
        assertTrue(list.single().closed)
        assertEquals(15, list.single().uplinkTotal)

        assertEquals(emptyList(), table.apply(false, emptyList(), 1_200))
    }

    @Test
    fun resetDropsEverything() {
        val table = ConnectionTable()
        table.apply(false, listOf(ConnEvent.New(conn("1", "direct", listOf("direct")))), 0)
        val list = table.apply(true, listOf(ConnEvent.New(conn("2", "n-a", listOf("proxy", "n-a")))), 0)
        assertEquals(listOf("2"), list.map { it.id })
    }

    @Test
    fun updateForUnknownIdIsIgnored() {
        assertEquals(emptyList(), ConnectionTable().apply(false, listOf(ConnEvent.Update("x", 1, 1)), 0))
    }

    @Test
    fun aggregatesOnlyProxiedTrafficPerOutbound() {
        val list = listOf(
            conn("1", "n-a", listOf("proxy", "n-a"), up = 100, down = 0),
            conn("2", "n-a", listOf("proxy", "auto", "n-a"), up = 50, down = 10),
            conn("3", "n-b", listOf("proxy", "n-b"), up = 1, down = 1).copy(closed = true),
            conn("4", "direct", listOf("direct"), up = 1_000, down = 1_000_000),
        )
        assertEquals(listOf("1", "2", "3"), list.proxied().map { it.id })
        val agg = list.trafficByOutbound()
        assertEquals(setOf("n-a", "n-b"), agg.keys)
        assertEquals(OutboundTraffic("n-a", active = 2, uplinkTotal = 150, downlinkTotal = 10), agg["n-a"])
        assertEquals(0, agg.getValue("n-b").active)
    }
}
