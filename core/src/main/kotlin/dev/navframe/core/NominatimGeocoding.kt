package dev.navframe.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.Strictness

private const val MAX_GEOCODING_RESPONSE_CHARS = 512 * 1024
private val geocodingJson = GsonBuilder().setStrictness(Strictness.STRICT).create()
private val coordinatePattern = Regex("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")

/** Error messages deliberately omit addresses, queries, response JSON and coordinates. */
class GeocodingResponseException(message: String) : IllegalArgumentException(message)

/** Encode these parameters using the HTTP client's query encoder, never concatenate user text. */
fun nominatimSearchParameters(query: String): Map<String, String> {
    require(query.length <= 200 && query.none { it.isISOControl() && !it.isWhitespace() }) { "Invalid destination query" }
    val normalized = query.trim().replace(Regex("\\s+"), " ")
    require(normalized.length in 2..200) { "Destination query must contain 2 to 200 characters" }
    return linkedMapOf("q" to normalized, "format" to "jsonv2", "limit" to "5",
        "accept-language" to "es", "addressdetails" to "0")
}

/** Strict jsonv2 response adapter. No provider implementation code or live fixture is embedded. */
fun parseNominatimResults(json: String): List<SearchResult> {
    if (json.isBlank() || json.length > MAX_GEOCODING_RESPONSE_CHARS) invalidGeocoding("Invalid geocoding response size")
    try {
        val root = geocodingJson.fromJson(json, JsonElement::class.java)
        if (root == null || !root.isJsonArray) invalidGeocoding("Invalid geocoding result list")
        val list = root.asJsonArray
        if (list.size() > 40) invalidGeocoding("Too many geocoding results")
        return list.map { element ->
            if (!element.isJsonObject) invalidGeocoding("Invalid geocoding result")
            val item = element.asJsonObject
            val name = cleanLabel(item.get("display_name").requiredText(), 2_048)
            if (name.isEmpty()) invalidGeocoding("Missing destination label")
            val latitude = item.get("lat").coordinate(-90.0, 90.0)
            val longitude = item.get("lon").coordinate(-180.0, 180.0)
            val attribution = item.get("licence")?.let { cleanLabel(it.requiredText(), 2_048) }
                ?.takeIf { it.isNotEmpty() } ?: "© OpenStreetMap contributors"
            SearchResult(name, GeoPoint(latitude, longitude), attribution)
        }.distinctBy { Triple(it.name, it.position.latitude, it.position.longitude) }.take(5)
    } catch (error: GeocodingResponseException) {
        throw error
    } catch (_: RuntimeException) {
        invalidGeocoding("Malformed geocoding response")
    }
}

private fun invalidGeocoding(message: String): Nothing = throw GeocodingResponseException(message)
private fun JsonElement?.requiredText(): String =
    if (this != null && isJsonPrimitive && asJsonPrimitive.isString) asString else invalidGeocoding("Invalid geocoding text")
private fun JsonElement?.coordinate(min: Double, max: Double): Double {
    val text = requiredText()
    if (text.length > 64 || !coordinatePattern.matches(text)) invalidGeocoding("Invalid geocoding coordinate")
    return text.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max }
        ?: invalidGeocoding("Geocoding coordinate outside valid range")
}
private fun cleanLabel(text: String, maxLength: Int): String {
    if (text.length > maxLength) invalidGeocoding("Geocoding label too long")
    return text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().replace(Regex("\\s+"), " ")
}
