package dev.navframe.app

import org.json.JSONArray
import org.json.JSONObject

/** Original local style for the original NavFrame MVT schema; no network resources. */
object OfflineMapStyle {
    fun create(archiveFileUri: String): String {
        require(archiveFileUri.startsWith("file:///")) { "Archivo offline local inválido" }
        val layers = JSONArray()
        fun layer(id: String, type: String, sourceLayer: String?, paint: JSONObject, layout: JSONObject? = null, minZoom: Int? = null) {
            val item = JSONObject().put("id", id).put("type", type).put("paint", paint)
            if (sourceLayer != null) item.put("source", "offline").put("source-layer", sourceLayer)
            if (layout != null) item.put("layout", layout)
            if (minZoom != null) item.put("minzoom", minZoom)
            layers.put(item)
        }
        layer("background", "background", null, JSONObject().put("background-color", "#101820"))
        layer("green", "fill", "landuse", JSONObject().put("fill-color", "#172d26"))
        layer("water", "fill", "water", JSONObject().put("fill-color", "#173b57"))
        layer("rivers", "line", "waterway", JSONObject().put("line-color", "#245777").put("line-width", 1.5))
        layer("buildings", "fill", "building", JSONObject().put("fill-color", "#283039"), minZoom = 14)
        layer("roads-case", "line", "roads", JSONObject().put("line-color", "#0a0e14")
            .put("line-width", JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],6,1.5,14,6,18,13]")),
            JSONObject().put("line-cap", "round").put("line-join", "round"))
        layer("roads", "line", "roads", JSONObject()
            .put("line-color", JSONArray("[\"match\",[\"get\",\"class\"],[\"motorway\",\"motorway_link\",\"trunk\",\"trunk_link\"],\"#e6a34c\",[\"primary\",\"primary_link\",\"secondary\",\"secondary_link\"],\"#d6cba8\",[\"path\",\"footway\",\"cycleway\",\"steps\",\"track\"],\"#6e8187\",\"#aabac3\"]"))
            .put("line-width", JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],6,0.5,14,3,18,9]")),
            JSONObject().put("line-cap", "round").put("line-join", "round"))
        val textPaint = JSONObject().put("text-color", "#ecf1f4").put("text-halo-color", "#101820").put("text-halo-width", 1.5)
        layer("road-labels", "symbol", "roads", textPaint, JSONObject().put("symbol-placement", "line")
            .put("text-field", JSONArray("[\"coalesce\",[\"get\",\"name\"],[\"get\",\"ref\"],\"\"]"))
            .put("text-font", JSONArray().put("Noto Sans Regular")).put("text-size", 11).put("text-max-angle", 30), 14)
        layer("places", "symbol", "place", textPaint, JSONObject().put("text-field", JSONArray("[\"get\",\"name\"]"))
            .put("text-font", JSONArray().put("Noto Sans Regular")).put("text-size", 13)
            .put("text-max-width", 9).put("text-padding", 3))
        return JSONObject().put("version", 8).put("name", "NavFrame offline TFT")
            .put("glyphs", "asset://offline/fonts/{fontstack}/{range}.pbf")
            .put("sources", JSONObject().put("offline", JSONObject().put("type", "vector")
                .put("url", "pmtiles://$archiveFileUri").put("attribution", "© OpenStreetMap contributors")))
            .put("layers", layers).toString()
    }
}
