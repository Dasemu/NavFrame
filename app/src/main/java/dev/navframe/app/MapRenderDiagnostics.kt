package dev.navframe.app

/** Only fixed labels cross this boundary; SDK messages can contain coordinates, URLs and access tokens. */
enum class MapRenderStage { INITIALIZE, STYLE_LOADING, STYLE_READY, SNAPSHOT_READY, COMPOSE }
enum class MapRenderFailure { TIMEOUT, NETWORK, STYLE, GPU, BITMAP, SDK }
enum class MapResourceKind { GLYPH, SPRITE, TILE, GEOJSON, STYLE, BITMAP, UNKNOWN }
enum class MapFailureReason { DNS, FORBIDDEN, NOT_FOUND, CONTEXT_LOST, EGL, MEMORY, PARSE, CONNECTION, UNKNOWN }
data class MapErrorDetail(val resource: MapResourceKind = MapResourceKind.UNKNOWN, val reason: MapFailureReason = MapFailureReason.UNKNOWN, val httpStatus: Int? = null, val errorClass: String = "SDK_CALLBACK") {
    fun safeText(): String = "${resource.name}/${reason.name}${httpStatus?.let { "/HTTP_$it" } ?: ""}/${errorClass.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,80}")) } ?: "OTHER"}"
}
class MapRenderException(val failure: MapRenderFailure, val stage: MapRenderStage, val detail: MapErrorDetail = MapErrorDetail()) : Exception("${failure.name}:${stage.name}:${detail.safeText()}")
internal fun mapErrorDetail(message: String?, errorClass: String = "SDK_CALLBACK"): MapErrorDetail {
    val text = message.orEmpty().lowercase(java.util.Locale.ROOT)
    val status = Regex("\\b(?:http(?: status| error)?|status(?: code)?|server returned)\\D{0,12}([45]\\d{2})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
    val resource = when {
        listOf("glyph", "font").any(text::contains) -> MapResourceKind.GLYPH
        text.contains("sprite") -> MapResourceKind.SPRITE
        text.contains("geojson") -> MapResourceKind.GEOJSON
        text.contains("tile") || text.contains(".pbf") -> MapResourceKind.TILE
        text.contains("style") || text.contains("json") -> MapResourceKind.STYLE
        text.contains("bitmap") || text.contains("canvas") -> MapResourceKind.BITMAP
        else -> MapResourceKind.UNKNOWN
    }
    val reason = when {
        text.contains("dns") || text.contains("resolve") -> MapFailureReason.DNS
        status == 403 || text.contains("forbidden") -> MapFailureReason.FORBIDDEN
        status == 404 || text.contains("not found") -> MapFailureReason.NOT_FOUND
        text.contains("context lost") -> MapFailureReason.CONTEXT_LOST
        text.contains("egl") -> MapFailureReason.EGL
        text.contains("memory") -> MapFailureReason.MEMORY
        text.contains("parse") -> MapFailureReason.PARSE
        text.contains("connection") || text.contains("socket") -> MapFailureReason.CONNECTION
        else -> MapFailureReason.UNKNOWN
    }
    return MapErrorDetail(resource, reason, status, errorClass)
}
internal fun classifyMapRenderFailure(message: String?): MapRenderFailure {
    val text = message.orEmpty().lowercase(java.util.Locale.ROOT)
    return when {
        listOf("egl", "opengl", "framebuffer", "shader", "gl context", "gl_context", "graphics context", "gpu").any(text::contains) -> MapRenderFailure.GPU
        mapErrorDetail(message).httpStatus != null || listOf("resolve", "dns", "network", "connection", "socket", "server returned", "request failed", "failed to load resource", "failed to load tile", "failed to load glyph", "download").any(text::contains) -> MapRenderFailure.NETWORK
        listOf("parse", "json", "style", "layer", "geojson").any(text::contains) -> MapRenderFailure.STYLE
        listOf("bitmap", "canvas", "image size", "dimensions").any(text::contains) -> MapRenderFailure.BITMAP
        else -> MapRenderFailure.SDK
    }
}
