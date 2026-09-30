package dev.navframe.app

import dev.navframe.core.*
import org.junit.Assert.*
import org.junit.Test

class UnifiedPlaceSearchTest {
    @Test fun mergedResultsPreferOfflineDetailsAndKeepDistinctCoordinates() {
        val local = PlaceSelection.offline(OfflinePlace("osm:1", "Café", PlaceCategory.FOOD, GeoPoint(43.0, -5.0), phone = "123"))
        val duplicate = PlaceSelection.online(SearchResult("Cafe", local.position))
        val other = PlaceSelection.online(SearchResult("Cafe", GeoPoint(43.1, -5.0)))
        val merged = UnifiedPlaceSearch.merge(listOf(local), listOf(duplicate, other))
        assertEquals(listOf(local, other), merged)
        assertEquals("123", merged.first().offlinePlace?.phone)
        assertTrue(merged.first().sourceLabel.startsWith("Offline"))
        assertTrue(merged.last().sourceLabel.startsWith("Online"))
    }
    @Test fun viewportCenterHandlesDateLine() {
        assertEquals(180.0, PlaceViewport(-10.0, 170.0, 10.0, -170.0).center.longitude, 0.0)
    }
}
