package moe.desupon.model.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NetworkKeysTest {
    private val home = NetworkKeys.hashed(Transport.WIFI, listOf("192.168.1.1"), listOf("192.168.1.1", "fd00::1"), "lan home")

    @Test
    fun hashIgnoresOrderCaseWhitespaceAndDuplicates() {
        val again = NetworkKeys.hashed(Transport.WIFI, listOf(" 192.168.1.1"), listOf("FD00::1", "192.168.1.1", "192.168.1.1"), "HOME  lan")
        assertEquals(home, again)
        assertEquals(16, home.id.length)
        assertEquals(home, NetworkKey.decode(home.encode()))
    }

    @Test
    fun anyDifferingInputChangesTheKey() {
        assertNotEquals(home, NetworkKeys.hashed(Transport.WIFI, listOf("192.168.0.1"), listOf("192.168.1.1", "fd00::1"), "lan home"))
        assertNotEquals(home, NetworkKeys.hashed(Transport.WIFI, listOf("192.168.1.1"), listOf("192.168.1.1"), "lan home"))
        assertNotEquals(home, NetworkKeys.hashed(Transport.WIFI, listOf("192.168.1.1"), listOf("192.168.1.1", "fd00::1"), null))
        // Values cannot move between parts unnoticed.
        assertNotEquals(home.id, NetworkKeys.hashed(Transport.WIFI, listOf("192.168.1.1", "fd00::1"), listOf("192.168.1.1"), "lan home").id)
    }

    @Test
    fun cellularUsesOperatorAndFallsBackToHashWithoutOne() {
        assertEquals(NetworkKey(Transport.CELLULAR, "25001"), NetworkKeys.cellular(" 25001 "))
        assertEquals(NetworkKeys.cellular("25001"), NetworkKeys.of(Transport.CELLULAR, "25001", listOf("10.0.0.1"), emptyList(), null))
        val noOperator = NetworkKeys.of(Transport.CELLULAR, " ", listOf("10.0.0.1"), listOf("10.0.0.2"), null)
        assertEquals(NetworkKeys.hashed(Transport.CELLULAR, listOf("10.0.0.1"), listOf("10.0.0.2"), null), noOperator)
    }

    @Test
    fun ofMatchesHashedForNonCellular() {
        for (t in listOf(Transport.WIFI, Transport.ETHERNET, Transport.OTHER)) {
            assertEquals(
                NetworkKeys.hashed(t, listOf("192.168.1.1"), listOf("192.168.1.1", "fd00::1"), "lan home"),
                NetworkKeys.of(t, "25001", listOf("192.168.1.1"), listOf("fd00::1", "192.168.1.1"), "home lan"),
            )
        }
    }
}
