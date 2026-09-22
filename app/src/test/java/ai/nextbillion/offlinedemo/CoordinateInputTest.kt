package ai.nextbillion.offlinedemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateInputTest {
    @Test
    fun parseCoordinates_acceptsInclusiveWorldBounds() {
        assertEquals(Coordinates(90.0, -180.0), parseCoordinates("90", "-180"))
        assertEquals(Coordinates(-90.0, 180.0), parseCoordinates("-90", "180"))
    }

    @Test
    fun parseCoordinates_rejectsInvalidOrNonFiniteValues() {
        assertNull(parseCoordinates("", "1"))
        assertNull(parseCoordinates("91", "0"))
        assertNull(parseCoordinates("0", "181"))
        assertNull(parseCoordinates("NaN", "0"))
        assertNull(parseCoordinates("0", "Infinity"))
    }
}
