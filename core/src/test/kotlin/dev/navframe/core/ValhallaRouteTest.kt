package dev.navframe.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ValhallaRouteTest {
    private val fixture get() = javaClass.getResource("/valhalla-route.json")!!.readText()

    @Test fun `precision six preserves known coordinates`() {
        val decoded = decodePolyline6("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)), decoded)
    }

    @Test fun `parse route converts kilometers and preserves segment indices and destination`() {
        val destination = GeoPoint(43.36031, -5.85012)
        val route = parseValhallaRoute(fixture, destination)
        assertEquals(listOf(GeoPoint(43.36, -5.85), GeoPoint(43.3601, -5.8502), GeoPoint(43.3603, -5.8501)), route.geometry)
        assertEquals(52, route.distanceMeters)
        assertEquals(17, route.durationSeconds)
        assertEquals(destination, route.destination)
        assertEquals(31, route.maneuvers[0].distanceMeters)
        assertEquals(listOf("Calle de prueba"), route.maneuvers[0].streetNames)
        assertEquals(10, route.maneuvers[1].type)
        assertEquals(1, route.maneuvers[1].beginShapeIndex)
        assertEquals(2, route.maneuvers[1].endShapeIndex)
        assertEquals(0, route.maneuvers.last().distanceMeters)
    }

    @Test fun `miles conversion is explicit`() {
        val route = parseValhallaRoute(fixture.replace("kilometers", "miles").replace("0.052", "1.0"))
        assertEquals(1609, route.distanceMeters)
        assertEquals(50, route.maneuvers.first().distanceMeters)
    }

    @Test fun `multiple legs deduplicate join and rebase indices`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        val trip = root.getAsJsonObject("trip")
        val legs = trip.getAsJsonArray("legs")
        val next = legs[0].asJsonObject.deepCopy()
        next.addProperty("shape", "waouqAf~`dJgEoK")
        val nextManeuvers = next.getAsJsonArray("maneuvers")
        nextManeuvers.remove(2)
        nextManeuvers[1].asJsonObject.addProperty("begin_shape_index", 1)
        nextManeuvers[1].asJsonObject.addProperty("end_shape_index", 1)
        legs.add(next)
        val route = parseValhallaRoute(root.toString())
        assertEquals(4, route.geometry.size)
        assertEquals(GeoPoint(43.3604, -5.8499), route.geometry.last())
        assertEquals(2, route.maneuvers[3].beginShapeIndex)
        assertEquals(3, route.maneuvers[3].endShapeIndex)
        assertEquals(3, route.maneuvers[4].beginShapeIndex)
    }

    @Test fun `malformed shape rejected without hanging`() {
        listOf("", "_", "??_", "!!", "~~~~~~~~~~~~??", "_keqlD???").forEach { shape ->
            rejects { decodePolyline6(shape) }
        }
    }

    @Test fun `malformed JSON measurements status and indices rejected`() {
        listOf(
            "{\"trip\":{}", fixture + "{}", "// comment\n$fixture",
            fixture.replace("\"status\": 0", "\"status\": 442"),
            fixture.replace("\"length\": 0.052", "\"length\": -1"),
            fixture.replace("\"length\": 0.052", "\"length\": 1e100"),
            fixture.replace("\"time\": 17.4", "\"time\": \"17.4\""),
            fixture.replace("\"end_shape_index\": 2", "\"end_shape_index\": 3"),
            fixture.replace("\"begin_shape_index\": 1", "\"begin_shape_index\": 3"),
            fixture.replace("\"type\": 10", "\"type\": 1.5"),
            fixture.replace("kilometers", "meters"),
            " ".repeat(2 * 1024 * 1024 + 1),
        ).forEach { json -> rejects { parseValhallaRoute(json) } }
    }

    @Test fun `disconnected legs rejected`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        val legs = root.getAsJsonObject("trip").getAsJsonArray("legs")
        legs.add(legs[0].deepCopy())
        rejects { parseValhallaRoute(root.toString()) }
    }

    @Test fun `coordinate request fixes format language and explicit profile options`() {
        val origin = GeoPoint(43.36, -5.85)
        val destination = GeoPoint(43.37, -5.84)
        val root = JsonParser.parseString(buildValhallaRequest(origin, destination)).asJsonObject
        assertEquals("motorcycle", root["costing"].asString)
        assertEquals(0, root.getAsJsonObject("costing_options").getAsJsonObject("motorcycle")["use_tracks"].asInt)
        assertEquals(0.5, root.getAsJsonObject("costing_options").getAsJsonObject("motorcycle")["use_trails"].asDouble, 0.0)
        assertEquals("polyline6", root["shape_format"].asString)
        assertEquals("es-ES", root["language"].asString)
        assertEquals("kilometers", root["units"].asString)
        assertEquals(2, root.getAsJsonArray("locations").size())
        assertEquals("break", root.getAsJsonArray("locations")[0].asJsonObject["type"].asString)
        val avoid = JsonParser.parseString(buildValhallaRequest(origin, destination, RouteOptions(RouteProfile.AVOID_MOTORWAYS))).asJsonObject
        assertEquals(0.0, avoid.getAsJsonObject("costing_options").getAsJsonObject("motorcycle")["use_highways"].asDouble, 0.0)
        val touring = JsonParser.parseString(buildValhallaRequest(origin, destination, RouteOptions(RouteProfile.TOURING))).asJsonObject
        assertEquals(0.2, touring.getAsJsonObject("costing_options").getAsJsonObject("motorcycle")["use_highways"].asDouble, 0.0)
        rejects { buildValhallaRequest(GeoPoint(Double.NaN, 0.0), destination) }
        rejects { buildValhallaRequest(origin, GeoPoint(0.0, 181.0)) }
    }

    private fun motorcycleOptions(options: RouteOptions) = JsonParser.parseString(
        buildValhallaRequest(GeoPoint(43.36, -5.85), GeoPoint(43.37, -5.84), options)
    ).asJsonObject.getAsJsonObject("costing_options").getAsJsonObject("motorcycle")

    @Test fun `asphalt preset applies motorcycle surface preference without unsupported hard exclusion`() {
        val fast = motorcycleOptions(RouteOptions())
        val asphalt = motorcycleOptions(RouteOptions(RouteProfile.AVOID_UNPAVED))
        assertEquals(0.5, fast["use_highways"].asDouble, 0.0)
        assertEquals(0.5, fast["use_trails"].asDouble, 0.0)
        assertEquals(0.0, asphalt["use_trails"].asDouble, 0.0)
        assertEquals(fast["use_highways"], asphalt["use_highways"])
        assertFalse(asphalt.has("exclude_unpaved"))
        assertFalse(asphalt.has("exclude_highways"))
        assertFalse(asphalt.has("shortest"))
    }

    @Test fun `combinable flags persist on presets without changing motorcycle mode`() {
        val options = RouteOptions(RouteProfile.TOURING, avoidMotorways = true, avoidUnpaved = true, avoidTolls = true)
        val cost = motorcycleOptions(options)
        assertEquals(0.0, cost["use_highways"].asDouble, 0.0)
        assertEquals(0.0, cost["use_trails"].asDouble, 0.0)
        assertEquals(0.0, cost["use_tolls"].asDouble, 0.0)
        assertEquals(0, cost["use_tracks"].asInt)
        val request = JsonParser.parseString(buildValhallaRequest(GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.1), options)).asJsonObject
        assertEquals("motorcycle", request["costing"].asString)
        assertFalse(cost.keySet().any { it.startsWith("exclude_") })
        assertEquals(cost, motorcycleOptions(options.copy()))
    }

    @Test fun `optional rough maneuver evidence never certifies asphalt from absence`() {
        assertNull(parseValhallaRoute(fixture).hasUnpaved)
        val root = JsonParser.parseString(fixture).asJsonObject
        val maneuvers = root.getAsJsonObject("trip").getAsJsonArray("legs")[0].asJsonObject.getAsJsonArray("maneuvers")
        maneuvers[0].asJsonObject.addProperty("rough", true)
        assertEquals(true, parseValhallaRoute(root.toString()).hasUnpaved)
        maneuvers[0].asJsonObject.addProperty("rough", false)
        assertNull(parseValhallaRoute(root.toString()).hasUnpaved)
        maneuvers.forEach { it.asJsonObject.addProperty("rough", false) }
        assertEquals(false, parseValhallaRoute(root.toString()).hasUnpaved)
    }

    @Test fun `optional surface evidence rejects coercion instead of trusting backend strings`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        val maneuver = root.getAsJsonObject("trip").getAsJsonArray("legs")[0].asJsonObject.getAsJsonArray("maneuvers")[0].asJsonObject
        listOf("false", "true", "1").forEach {
            maneuver.addProperty("rough", it)
            rejects { parseValhallaRoute(root.toString()) }
        }
    }

    @Test fun `backend error is sanitized`() {
        try {
            parseValhallaRoute("{\"error\":\"private-coordinate-details\",\"error_code\":442}")
            fail("Expected error")
        } catch (error: ValhallaRouteException) {
            assertFalse(error.message!!.contains("private-coordinate-details"))
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid routing data to be rejected") }
        catch (_: ValhallaRouteException) { /* expected */ }
    }

    @Test fun `multi stop request routes through intermediate stops in one leg`() {
        val stops = (0..4).map { GeoPoint(43.3 + it * 0.01, -5.8) }
        val locations = JsonParser.parseString(buildValhallaRequest(stops)).asJsonObject.getAsJsonArray("locations")
        assertEquals(listOf("break", "through", "through", "through", "break"), locations.map { it.asJsonObject["type"].asString })
        assertEquals(43.32, locations[2].asJsonObject["lat"].asDouble, 1e-9)
        try { buildValhallaRequest(List(MAX_REQUEST_LOCATIONS + 1) { stops[0] }); fail("Expected location limit") } catch (_: ValhallaRouteException) { }
    }
    @Test fun `merged legs rebase maneuvers and drop intermediate arrivals`() {
        val a = GeoPoint(43.0, -5.0); val b = GeoPoint(43.01, -5.0); val c = GeoPoint(43.02, -5.0); val d = GeoPoint(43.03, -5.0)
        fun part(from: GeoPoint, mid: GeoPoint, to: GeoPoint, unpaved: Boolean?) = RouteResult(listOf(from, mid, to), listOf(
            Maneuver("start", 10, type = 1, beginShapeIndex = 0, endShapeIndex = 1),
            Maneuver("right", 10, type = 10, beginShapeIndex = 1, endShapeIndex = 2),
            Maneuver("arrive", 0, type = 4, beginShapeIndex = 2, endShapeIndex = 2),
        ), 1_000, 60, to, hasUnpaved = unpaved)
        val merged = mergeRouteLegs(listOf(part(a, b, c, false), part(c, GeoPoint(43.025, -5.0), d, null)))
        assertEquals(5, merged.geometry.size)
        assertEquals(listOf(1, 10, 10, 4), merged.maneuvers.map { it.type })
        assertEquals(listOf(0, 1, 3, 4), merged.maneuvers.map { it.beginShapeIndex })
        assertEquals(2_000, merged.distanceMeters)
        assertEquals(120, merged.durationSeconds)
        assertNull(merged.hasUnpaved)
        assertEquals(d, merged.destination)
        // Independently snapped endpoints that do not coincide keep both points.
        assertEquals(6, mergeRouteLegs(listOf(part(a, b, c, true), part(GeoPoint(43.0201, -5.0), GeoPoint(43.025, -5.0), d, false))).geometry.size)
    }

    @Test fun `imported routes get a road leg from a distant rider position`() {
        val start = GeoPoint(43.0, -5.0)
        val trace = RouteResult(listOf(start, GeoPoint(43.01, -5.0)), emptyList(), 1_100, 0, GeoPoint(43.01, -5.0), imported = true)
        assertFalse(needsApproach(GeoPoint(43.0005, -5.0), trace)) // About 55 m away.
        assertTrue(needsApproach(GeoPoint(42.99, -5.0), trace))
        val approach = RouteResult(listOf(GeoPoint(42.99, -5.0), start), listOf(
            Maneuver("start", 1_100, type = 1, beginShapeIndex = 0, endShapeIndex = 1),
            Maneuver("arrive", 0, type = 4, beginShapeIndex = 1, endShapeIndex = 1),
        ), 1_100, 90, start)
        val merged = prependApproach(approach, trace)
        assertEquals(3, merged.geometry.size)
        assertTrue(merged.maneuvers.isEmpty()) // Still a trace to follow.
        assertEquals(2_200, merged.distanceMeters)
        assertTrue(merged.imported)
        assertEquals(trace.destination, merged.destination)
        assertEquals(0, merged.durationSeconds) // A trace never shows an ETA.
    }

    @Test fun `rejoining an imported route never goes back and counts points passed nearby`() {
        val points = (0..10).map { GeoPoint(43.0 + it * 0.001, -5.0) } // About 111 m apart.
        val route = RouteResult(points, listOf(
            Maneuver("right", 100, type = 10, beginShapeIndex = 3, endShapeIndex = 4),
            Maneuver("left", 100, type = 15, beginShapeIndex = 7, endShapeIndex = 8),
        ), 1_110, 100, points.last(), imported = true)
        val step = routeLength(route) / 10
        // Off to the side of point 2, but progress already reached point 5.
        val ahead = rejoinRemainder(route, GeoPoint(43.002, -4.998), 5 * step - 1)!!
        assertEquals(points[5], ahead.geometry.first())
        assertEquals(listOf(15), ahead.maneuvers.map { it.type })
        assertEquals(2, ahead.maneuvers.single().beginShapeIndex)
        assertEquals(555, ahead.distanceMeters)
        assertEquals(50, ahead.durationSeconds)
        // Nearest point ahead, not the next one.
        assertEquals(points[8], rejoinRemainder(route, GeoPoint(43.008, -4.999), 0.0)!!.geometry.first())
        assertNull(rejoinRemainder(route, points.last(), 0.0))
        // Riding past point 4 within 100 m counts; a point beyond the window does not.
        assertEquals(4 * step, passedAlong(route, GeoPoint(43.004, -4.9995), 0.0)!!, 1e-6)
        assertNull(passedAlong(route, GeoPoint(43.004, -4.99), 0.0))
        assertNull(passedAlong(route, points[9], 0.0, windowMeters = 500.0))
    }
}

