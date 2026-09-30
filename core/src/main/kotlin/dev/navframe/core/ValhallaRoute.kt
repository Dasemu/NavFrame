package dev.navframe.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import kotlin.math.roundToInt

private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
private const val MAX_SHAPE_POINTS = 100_000
private val routeJson = GsonBuilder().setStrictness(Strictness.STRICT).create()

/** Original adapter; no Valhalla implementation code is embedded. Errors omit response contents. */
class ValhallaRouteException(message: String) : IllegalArgumentException(message)

/** Explicit coordinate request. The HTTP adapter owns rate limits, headers and cancellation. */
fun buildValhallaRequest(origin: GeoPoint, destination: GeoPoint, options: RouteOptions = RouteOptions(), language: String = "es-ES"): String {
    validatePoint(origin)
    validatePoint(destination)
    val request = JsonObject()
    request.add("locations", JsonArray().apply {
        listOf(origin, destination).forEach { point ->
            add(JsonObject().apply {
                addProperty("lat", point.latitude)
                addProperty("lon", point.longitude)
                addProperty("type", "break")
            })
        }
    })
    request.addProperty("costing", "motorcycle")
    request.addProperty("units", "kilometers")
    request.addProperty("language", GuidanceLanguage.resolve(language).tag)
    request.addProperty("directions_type", "instructions")
    request.addProperty("shape_format", "polyline6")
    request.add("costing_options", JsonObject().apply {
        add("motorcycle", JsonObject().apply {
            val avoidMotorways = options.avoidMotorways || options.profile == RouteProfile.AVOID_MOTORWAYS
            val preferAsphalt = options.avoidUnpaved || options.profile == RouteProfile.AVOID_UNPAVED
            val avoidTolls = options.avoidTolls || options.profile == RouteProfile.TOURING
            addProperty("use_tracks", 0)
            addProperty("use_trails", if (preferAsphalt) 0.0 else 0.5)
            addProperty("use_highways", when {
                avoidMotorways -> 0.0
                options.profile == RouteProfile.TOURING -> 0.2
                else -> 0.5
            })
            addProperty("use_tolls", if (avoidTolls) 0.0 else 0.5)
            // No exclude_*: experimental highway exclusions may be ignored by the server;
            // motorcycle currently does not apply exclude_unpaved in Allowed/AllowedReverse.
        })
    })
    return routeJson.toJson(request)
}

/** Decode the requested precision-6 shape, rejecting malformed, oversized and invalid coordinates. */
fun decodePolyline6(encoded: String): List<GeoPoint> {
    if (encoded.isEmpty() || encoded.length > MAX_RESPONSE_CHARS) invalid("Invalid route shape size")
    var cursor = 0
    var latitude = 0L
    var longitude = 0L
    fun component(): Long {
        var bits = 0L
        var shift = 0
        while (true) {
            if (cursor >= encoded.length) invalid("Truncated route shape")
            val value = encoded[cursor++].code - 63
            if (value !in 0..63 || shift > 30) invalid("Invalid route shape encoding")
            bits = bits or ((value and 31).toLong() shl shift)
            if (value < 32) break
            shift += 5
        }
        if (bits > 0xffffffffL) invalid("Route shape component overflow")
        return if ((bits and 1L) != 0L) -(bits shr 1) - 1 else bits shr 1
    }
    val points = ArrayList<GeoPoint>()
    while (cursor < encoded.length) {
        if (points.size >= MAX_SHAPE_POINTS) invalid("Too many route shape points")
        latitude += component()
        longitude += component()
        if (latitude !in -90_000_000L..90_000_000L || longitude !in -180_000_000L..180_000_000L) {
            invalid("Route shape coordinate outside valid range")
        }
        points.add(GeoPoint(latitude / 1_000_000.0, longitude / 1_000_000.0))
    }
    return points
}

/**
 * Parse a Valhalla /route response to road-snapped geometry and segment maneuvers.
 * Multi-leg indices are rebased onto the merged geometry. No navigation progress is inferred.
 */
fun parseValhallaRoute(json: String, destination: GeoPoint? = null): RouteResult {
    if (json.length > MAX_RESPONSE_CHARS || json.isBlank()) invalid("Invalid route response size")
    destination?.let(::validatePoint)
    try {
        val root = routeJson.fromJson(json, JsonElement::class.java).objectValue()
        val trip = root.required("trip").objectValue()
        if (trip.required("status").nonNegativeInt() != 0) invalid("Routing service returned an unsuccessful status")
        val metersPerUnit = when (trip.required("units").stringValue()) {
            "kilometers" -> 1000.0
            "miles" -> 1609.344
            else -> invalid("Unsupported routing distance units")
        }
        val summary = trip.required("summary").objectValue()
        val totalMeters = summary.required("length").scaledInt(metersPerUnit)
        val totalSeconds = summary.required("time").scaledInt(1.0)
        val legs = trip.required("legs").arrayValue()
        if (legs.size() !in 1..128) invalid("Invalid route leg count")
        val geometry = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        var anyUnpaved = false
        var allSurfacesKnown = true
        for (element in legs) {
            val leg = element.objectValue()
            val shape = decodePolyline6(leg.required("shape").stringValue())
            if (shape.size < 2) invalid("Route leg has insufficient geometry")
            val offset = if (geometry.isEmpty()) 0 else {
                if (geometry.last() != shape.first()) invalid("Disconnected route legs")
                geometry.size - 1
            }
            if (geometry.size + shape.size > MAX_SHAPE_POINTS + 1) invalid("Too many route shape points")
            geometry.addAll(if (geometry.isEmpty()) shape else shape.drop(1))
            val legManeuvers = leg.required("maneuvers").arrayValue()
            if (legManeuvers.size() > MAX_SHAPE_POINTS - maneuvers.size) invalid("Too many route maneuvers")
            for (item in legManeuvers) {
                val maneuver = item.objectValue()
                val rough = maneuver.get("rough")?.booleanValue()
                if (rough == null) allSurfacesKnown = false else if (rough) anyUnpaved = true
                val begin = maneuver.required("begin_shape_index").nonNegativeInt()
                val end = maneuver.required("end_shape_index").nonNegativeInt()
                if (begin > end || end >= shape.size) invalid("Maneuver indices outside route shape")
                val names = maneuver.get("street_names")?.arrayValue()?.map { it.stringValue() } ?: emptyList()
                maneuvers.add(Maneuver(
                    instruction = maneuver.required("instruction").stringValue(),
                    distanceMeters = maneuver.required("length").scaledInt(metersPerUnit),
                    type = maneuver.required("type").nonNegativeInt(),
                    beginShapeIndex = begin + offset,
                    endShapeIndex = end + offset,
                    durationSeconds = maneuver.required("time").scaledInt(1.0),
                    streetNames = names,
                ))
            }
        }
        if (maneuvers.isEmpty()) invalid("Route has no maneuvers")
        return RouteResult(geometry, maneuvers, totalMeters, totalSeconds, destination,
            hasUnpaved = if (anyUnpaved) true else if (allSurfacesKnown) false else null)
    } catch (error: ValhallaRouteException) {
        throw error
    } catch (_: RuntimeException) {
        // Do not leak backend messages, response JSON or coordinates into logs/UI.
        invalid("Malformed routing response")
    }
}

private fun validatePoint(point: GeoPoint) {
    if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
        point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) invalid("Invalid routing coordinate")
}
private fun invalid(message: String): Nothing = throw ValhallaRouteException(message)
private fun JsonObject.required(name: String): JsonElement = get(name) ?: invalid("Missing routing field")
private fun JsonElement.objectValue(): JsonObject = if (isJsonObject) asJsonObject else invalid("Invalid routing object")
private fun JsonElement.arrayValue(): JsonArray = if (isJsonArray) asJsonArray else invalid("Invalid routing array")
private fun JsonElement.stringValue(): String = if (isJsonPrimitive && asJsonPrimitive.isString) asString else invalid("Invalid routing text")
private fun JsonElement.numberValue(): Double {
    if (!isJsonPrimitive || !asJsonPrimitive.isNumber) invalid("Invalid routing number")
    return asDouble.also { if (!it.isFinite() || it < 0) invalid("Invalid routing number range") }
}
private fun JsonElement.nonNegativeInt(): Int {
    val number = numberValue()
    if (number > Int.MAX_VALUE || number % 1 != 0.0) invalid("Invalid routing integer")
    return number.toInt()
}
private fun JsonElement.scaledInt(factor: Double): Int {
    val result = numberValue() * factor
    if (!result.isFinite() || result > Int.MAX_VALUE) invalid("Routing measurement overflow")
    return result.roundToInt()
}

private fun JsonElement.booleanValue(): Boolean =
    if (isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else invalid("Invalid routing boolean")
