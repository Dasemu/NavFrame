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
}
