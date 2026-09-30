package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class OfflinePlaceTest {
    @Test fun `search matches accents combining forms and irregular spaces`() {
        assertEquals("cafe alvaro", normalizePlaceSearch("  CAFÉ\u00a0ÁLVARO\n"))
        assertEquals(normalizePlaceSearch("José"), normalizePlaceSearch("Jose\u0301"))
        assertEquals("penaflor", normalizePlaceSearch("Peñaflor"))
        assertEquals("καφες", normalizePlaceSearch("Καφές"))
        assertEquals("россия", normalizePlaceSearch("Россия"))
        assertEquals("a b", normalizePlaceSearch("A\u200bB"))
        assertEquals("", normalizePlaceSearch(" \n\t"))
    }

    @Test fun `LIKE literal escaping preserves apostrophes and never treats input as SQL`() {
        assertEquals("100\\% moto\\_x\\\\y", escapePlaceLike("100% moto_x\\y"))
        assertEquals("o'connor", escapePlaceLike("o'connor"))
        assertEquals("' or 1=1 --", escapePlaceLike("' or 1=1 --"))
    }

    @Test fun `business carries raw opening hours and distinct OSM identity without invented details`() {
        val place = OfflinePlace("w/123", "Taller test", PlaceCategory.WORKSHOP, GeoPoint(43.36, -5.85))
        assertNull(place.openingHours)
        assertNull(place.distanceMeters)
        assertEquals("Talleres", place.category.labelSpanish)
        assertNotEquals(place, place.copy(id = "n/123"))
        assertEquals(5, PlaceCategory.entries.size)
    }
}
