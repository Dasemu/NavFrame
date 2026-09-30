package dev.navframe.core

import java.text.Normalizer
import java.util.Locale

/** Original local-POI contract. Categories describe OSM tags, not verified services/open status. */
enum class PlaceCategory(val labelSpanish: String) {
    FUEL("Gasolina"), WORKSHOP("Talleres"), FOOD("Comer"), LODGING("Dormir"), SHOPPING("Compras")
}

data class OfflinePlace(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val position: GeoPoint,
    val address: String? = null,
    val openingHours: String? = null,
    val website: String? = null,
    val phone: String? = null,
    /** Straight-line distance from the explicitly selected search origin, never route distance. */
    val distanceMeters: Double? = null,
)

/** Same algorithm as tools/offline/build-pois.py, including whitespace and combining marks. */
fun normalizePlaceSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s\\p{Z}\\p{Cc}\\p{Cf}]+"), " ")
    .trim()

/** For a parameterized LIKE query using ESCAPE '\\'; parameterization alone does not escape wildcards. */
fun escapePlaceLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
