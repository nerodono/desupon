package moe.desupon.model.selection

import moe.desupon.model.node.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationChoiceTest {
    @Test
    fun roundTrips() {
        for (c in listOf(LocationChoice.Any, LocationChoice.Country("NL"), LocationChoice.Node(NodeId("ab12")))) {
            assertEquals(c, LocationChoice.decode(LocationChoice.encode(c)))
        }
    }

    @Test
    fun malformedReadsAsAny() {
        for (v in listOf(null, "", "country:", "node:", "weird", "node: ")) assertEquals(LocationChoice.Any, LocationChoice.decode(v))
    }

    @Test
    fun countryIsNormalized() {
        assertEquals(LocationChoice.Country("DE"), LocationChoice.decode("country: de"))
        assertEquals(LocationChoice.Country("DE"), LocationChoice.country("de "))
    }

    @Test
    fun allSetIsBuiltIn() {
        assertTrue(SetId.ALL.isAll)
        assertTrue(!SetId(3).isAll)
    }
}
