package dev.navframe.app

/** Only fixed labels cross this boundary; SDK messages can contain coordinates, URLs and access tokens. */
enum class MapRenderStage { INITIALIZE, STYLE_LOADING, STYLE_READY, CAMERA_UPDATE, ROUTE_UPDATE, SNAPSHOT_REQUESTED, SNAPSHOT_READY, COMPOSE }
enum class MapRenderFailure { TIMEOUT, NETWORK, STYLE, GPU, BITMAP, SDK }
enum class MapResourceKind { GLYPH, SPRITE, TILE, GEOJSON, STYLE, BITMAP, PMTILES, FILE, UNKNOWN }
enum class MapFailureReason { DNS, FORBIDDEN, NOT_FOUND, CONTEXT_LOST, EGL, MEMORY, PARSE, CONNECTION, IO, CANCELLED, EMPTY, UNSUPPORTED, UNKNOWN }
data class MapErrorDetail(val resource: MapResourceKind = MapResourceKind.UNKNOWN, val reason: MapFailureReason = MapFailureReason.UNKNOWN, val httpStatus: Int? = null, val errorClass: String = "SDK_CALLBACK", val sdkEvidence: String = "") {
    fun safeText(): String = "${resource.name}/${reason.name}${httpStatus?.let { "/HTTP_$it" } ?: ""}/${errorClass.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,80}")) } ?: "OTHER"}${if (sdkEvidence.isEmpty()) "" else "/WORDS_${sdkEvidence}"}"
}
class MapRenderException(val failure: MapRenderFailure, val stage: MapRenderStage, val detail: MapErrorDetail = MapErrorDetail()) : Exception("${failure.name}:${stage.name}:${detail.safeText()}")
internal fun mapErrorDetail(message: String?, errorClass: String = "SDK_CALLBACK"): MapErrorDetail {
    val text = safeSdkEvidence(message).lowercase(java.util.Locale.ROOT).replace('_', ' ')
    val status = Regex("\\b(?:http(?: status| error)?|status(?: code)?|server returned)\\D{0,12}([45]\\d{2})\\b").find(message.orEmpty().lowercase(java.util.Locale.ROOT))?.groupValues?.get(1)?.toIntOrNull()
    val resource = when {
        text.contains("pmtiles") -> MapResourceKind.PMTILES
        listOf("glyph", "font").any(text::contains) -> MapResourceKind.GLYPH
        text.contains("sprite") -> MapResourceKind.SPRITE
        text.contains("geojson") -> MapResourceKind.GEOJSON
        text.contains("tile") || text.contains(".pbf") -> MapResourceKind.TILE
        text.contains("style") || text.contains("json") -> MapResourceKind.STYLE
        text.contains("bitmap") || text.contains("canvas") -> MapResourceKind.BITMAP
        text.contains("file") || text.contains("archive") -> MapResourceKind.FILE
        else -> MapResourceKind.UNKNOWN
    }
    val reason = when {
        text.contains("dns") || text.contains("resolve") -> MapFailureReason.DNS
        status == 403 || text.contains("forbidden") -> MapFailureReason.FORBIDDEN
        status == 404 || text.contains("not found") || text.contains("could not read asset") -> MapFailureReason.NOT_FOUND
        text.contains("context lost") -> MapFailureReason.CONTEXT_LOST
        text.contains("egl") -> MapFailureReason.EGL
        text.contains("memory") -> MapFailureReason.MEMORY
        text.contains("parse") -> MapFailureReason.PARSE
        text.contains("connection") || text.contains("socket") -> MapFailureReason.CONNECTION
        text.contains("cancel") -> MapFailureReason.CANCELLED
        text.contains("unsupported") || text.contains("not supported") -> MapFailureReason.UNSUPPORTED
        text.contains("no such file") || text.contains("permission denied") || text.contains("read error") || text.contains("failed read") || text.contains("failed to read") || text.contains("failed open") || text.contains("failed to open") || text.contains("cannot read") || text.contains("cannot open") || text.contains("i/o") -> MapFailureReason.IO
        message.isNullOrBlank() -> MapFailureReason.EMPTY
        else -> MapFailureReason.UNKNOWN
    }
    return MapErrorDetail(resource, reason, status, errorClass, safeSdkEvidence(message))
}
internal fun classifyMapRenderFailure(message: String?): MapRenderFailure {
    val text = safeSdkEvidence(message).lowercase(java.util.Locale.ROOT).replace('_', ' ')
    return when {
        listOf("egl", "opengl", "framebuffer", "shader", "gl context", "gl_context", "graphics context", "gpu").any(text::contains) -> MapRenderFailure.GPU
        mapErrorDetail(message).httpStatus != null || listOf("resolve", "dns", "network", "connection", "socket", "server returned", "request failed", "failed to load resource", "failed to load tile", "failed to load glyph", "download").any(text::contains) -> MapRenderFailure.NETWORK
        listOf("parse", "json", "style", "layer", "geojson").any(text::contains) -> MapRenderFailure.STYLE
        listOf("bitmap", "canvas", "image size", "dimensions").any(text::contains) -> MapRenderFailure.BITMAP
        else -> MapRenderFailure.SDK
    }
}

/** Preserve only known diagnostic vocabulary, never arbitrary SDK text or identifiers. */
internal fun safeSdkEvidence(message: String?): String {
    if (message.isNullOrBlank()) return "EMPTY"
    val allowed = setOf("failed", "failure", "error", "exception", "load", "loading", "loaded", "style", "map", "snapshot", "snapshotter", "render", "rendering", "fully", "tile", "tiles", "data", "no", "not", "missing", "empty", "invalid", "corrupt", "unsupported", "resource", "request", "cancelled", "canceled", "cancel", "timeout", "timed", "out", "file", "archive", "pmtiles", "read", "write", "open", "permission", "denied", "such", "directory", "end", "unexpected", "parse", "parsing", "json", "geojson", "glyph", "glyphs", "font", "sprite", "image", "bitmap", "memory", "allocation", "context", "lost", "egl", "opengl", "framebuffer", "shader", "gpu", "network", "connection", "socket", "resolve", "dns", "http", "status", "code", "server", "returned", "forbidden", "found", "active", "already", "started", "finished", "destroyed", "source", "sources", "size", "dimensions", "download", "unable", "cannot", "could", "compression", "compressed", "gzip", "zstd", "protobuf", "decode", "decoding", "truncated", "eof", "i", "o", "to", "of", "is", "been", "has", "was", "be", "gl", "graphics", "canvas", "layer", "supported")
    // Strip entire URLs, paths and quoted payloads before vocabulary extraction.
    val stripped = message.lowercase(java.util.Locale.ROOT)
        .replace(Regex("""[a-z][a-z0-9+.-]*://\S+"""), " ")
        .replace(Regex("""[/\\]\S+"""), " ")
        .replace(Regex("""["'][^"']*["']"""), " ")
    val words = Regex("[a-z]+").findAll(stripped).map { it.value }.filter { it in allowed }.take(24).toList()
    return if (words.isEmpty()) "UNCLASSIFIED" else words.joinToString("_").uppercase(java.util.Locale.ROOT).take(240).trimEnd('_')
}
