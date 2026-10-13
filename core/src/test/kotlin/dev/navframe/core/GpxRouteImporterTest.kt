package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxRouteImporterTest {
    private val points = "<rtept lat=\"43\" lon=\"-5\"/><rtept lat=\"43.001\" lon=\"-5\"/>"
    private fun parse(xml: String) = GpxRouteImporter.parse(ByteArrayInputStream(xml.toByteArray()))
    private fun rejected(xml: String, expected: String? = null) {
        try { parse(xml); fail("Expected rejected GPX") } catch (error: IllegalArgumentException) {
            expected?.let { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(it)) }
        }
    }
    @Test fun `imports route geometry without invented guidance`() {
        val result = parse("<gpx><rte><name> Asturias &amp; Costa </name>$points</rte></gpx>")
        assertEquals("Asturias & Costa", result.name)
        assertEquals(2, result.route.geometry.size)
        assertEquals(111, result.route.distanceMeters)
        assertEquals(0, result.route.durationSeconds)
        assertTrue(result.route.maneuvers.isEmpty())
        assertTrue(result.route.imported)
        assertEquals(result.route.geometry.last(), result.route.destination)
    }
    @Test fun `supports standard namespaces and prefixed GPX`() {
        for (version in listOf("1/0", "1/1")) {
            assertEquals(2, parse("<gpx xmlns=\"http://www.topografix.com/GPX/$version\"><rte>$points</rte></gpx>").route.geometry.size)
        }
        assertEquals(2, parse("<p:gpx xmlns:p=\"http://www.topografix.com/GPX/1/1\"><p:rte><p:rtept lat=\"0\" lon=\"0\"/><p:rtept lat=\"1\" lon=\"1\"/></p:rte></p:gpx>").route.geometry.size)
    }
    @Test fun `imports track and ignores empty segments and waypoints`() {
        val trackPoints = points.replace("rtept", "trkpt")
        val result = parse("<gpx><wpt lat=\"90\" lon=\"180\"/><trk><name>Track</name><trkseg/><trkseg>$trackPoints</trkseg><trkseg/></trk></gpx>")
        assertEquals("Track", result.name)
        assertEquals(2, result.route.geometry.size)
    }
    @Test fun `rejects multiple candidates and disconnected segments`() {
        rejected("<gpx><rte>$points</rte><rte>$points</rte></gpx>", "varias")
        rejected("<gpx><rte>$points</rte><trk/></gpx>")
        rejected("<gpx><trk/><trk/></gpx>", "varias")
        val segment = "<trkseg>${points.replace("rtept", "trkpt")}</trkseg>"
        rejected("<gpx><trk>$segment$segment</trk></gpx>", "segmentos")
    }
    @Test fun `route stop list with a detailed track follows only the track in either order`() {
        val stops = "<rte><name>Stops</name>$points</rte>"
        val trace = "<trk><name>Detailed trace</name><trkseg><trkpt lat=\"43\" lon=\"-5\"/><trkpt lat=\"43.0005\" lon=\"-5.001\"/><trkpt lat=\"43.001\" lon=\"-5\"/></trkseg></trk>"
        for (body in listOf(stops + trace, trace + stops)) {
            val result = parse("<gpx>$body</gpx>")
            assertEquals("Detailed trace", result.name)
            assertEquals(3, result.route.geometry.size)
            assertEquals(GeoPoint(43.0005, -5.001), result.route.geometry[1])
        }
    }
    @Test fun `route and track share the point budget`() {
        val stops = "<rte>" + "<rtept lat=\"1\" lon=\"1\"/>".repeat(GpxRouteImporter.MAX_POINTS) + "</rte>"
        rejected("<gpx>$stops<trk><trkseg><trkpt lat=\"1\" lon=\"1\"/></trkseg></trk></gpx>", "10000")
    }
    @Test fun `rejects DTD internal and external entities`() {
        rejected("<!DOCTYPE gpx [<!ENTITY name 'expanded'>]><gpx><rte><name>&name;</name>$points</rte></gpx>")
        rejected("<!DOCTYPE gpx SYSTEM 'file:///etc/passwd'><gpx><rte>$points</rte></gpx>")
        rejected("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'https://example.com/secret'>]><gpx><rte><name>&x;</name>$points</rte></gpx>")
    }
    @Test fun `rejects invalid empty and stationary geometry`() {
        for (value in listOf("NaN", "Infinity", "91", "nope")) rejected("<gpx><rte><rtept lat=\"$value\" lon=\"0\"/>$points</rte></gpx>")
        rejected("<gpx><rte><rtept lat=\"0\" lon=\"181\"/>$points</rte></gpx>")
        rejected("<gpx/>")
        rejected("<gpx><rte><rtept lat=\"0\" lon=\"0\"/><rtept lat=\"0\" lon=\"0\"/></rte></gpx>", "distintos")
        rejected("<other><rte>$points</rte></other>")
        rejected("<gpx xmlns=\"https://example.com\"><rte>$points</rte></gpx>")
        rejected("<gpx><rte>$points")
    }
    @Test fun `does not import points from extensions`() {
        rejected("<gpx><extensions><rte>$points</rte></extensions></gpx>")
        rejected("<gpx xmlns:x=\"https://example.com\"><rte><x:rtept lat=\"0\" lon=\"0\"/><x:rtept lat=\"1\" lon=\"1\"/></rte></gpx>")
    }
    @Test fun `bounds bytes points and nesting`() {
        rejected(" ".repeat(GpxRouteImporter.MAX_BYTES + 1), "5 MiB")
        rejected("<gpx><rte>" + "<rtept lat=\"1\" lon=\"1\"/>".repeat(GpxRouteImporter.MAX_POINTS + 1) + "</rte></gpx>", "10000")
        rejected("<gpx>" + "<extensions>".repeat(GpxRouteImporter.MAX_DEPTH) + "</extensions>".repeat(GpxRouteImporter.MAX_DEPTH) + "</gpx>", "niveles")
    }
    @Test fun `bounded name and submeter route remain usable`() {
        val result = parse("<gpx><rte><name>${"x".repeat(1000)}</name><rtept lat=\"0\" lon=\"0\"/><rtept lat=\"0.000001\" lon=\"0\"/></rte></gpx>")
        assertEquals(256, result.name!!.length)
        assertEquals(1, result.route.distanceMeters)
    }
    @Test fun `imported route tracks progress without turns or ETA and confirms deviation`() {
        val route = parse("<gpx><rte><rtept lat=\"0\" lon=\"0\"/><rtept lat=\"0\" lon=\"0.01\"/></rte></gpx>").route
        val tracker = RouteGuidanceTracker(route)
        fun fix(lat: Double, time: Long) = LocationFix(GeoPoint(lat, 0.005), 90f, 5f, 5f, time)
        val onLine = tracker.update(fix(0.0, 1), 0, 100_000)
        assertEquals(NavigationStatus.NAVIGATING, onLine.navigationStatus)
        assertEquals(556, onLine.remainingDistanceMeters!!)
        assertNull(onLine.etaEpochMillis)
        assertNull(onLine.nextManeuver)
        assertNull(onLine.distanceToNextManeuverMeters)
        // A 56m displacement is believable movement, but clearly outside the route corridor.
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(0.0005, 2), 1_000, 101_000).navigationStatus)
        assertEquals(NavigationStatus.NAVIGATING, tracker.update(fix(0.0005, 3), 4_000, 104_000).navigationStatus)
        val outside = tracker.update(fix(0.0005, 4), 6_000, 106_000)
        assertEquals(NavigationStatus.OFF_ROUTE, outside.navigationStatus)
        assertNull(outside.nextManeuver)
        assertNull(outside.distanceToNextManeuverMeters)
        assertNull(outside.etaEpochMillis)
    }

}
