package dev.navframe.app

import dev.navframe.core.*
import java.net.URI

/** The boundary supplies a style endpoint rather than binding navigation to a particular provider. */
@Suppress("UNUSED_PARAMETER")
class ConfiguredMapDataSource(styleUrl: String = "", attribution: String = DEFAULT_ATTRIBUTION, context: android.content.Context? = null) : MapDataSource {
    private val source: TileSource
    init {
        val value = styleUrl.trim()
        if (value.isNotEmpty()) {
            val parsed = URI(value)
            val local = parsed.scheme == "file" && context != null && OfflineMapManager.isApprovedSource(context, value)
            require(local || (parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null)) { "Usa HTTPS o un paquete offline instalado por NavFrame" }
            require(parsed.host != "tile.openstreetmap.org") { "Usa un proveedor de teselas vectoriales" }
        }
        val credits = when { value.isEmpty() -> DEFAULT_ATTRIBUTION; value.startsWith("file:") -> OfflineMapManager.ATTRIBUTION; else -> BASE_ATTRIBUTION }
        source = TileSource(value.ifEmpty { "asset://map/tft-style.json" }, credits)
    }
    override fun tileSource() = source
    override suspend fun prepareRegion(region: MapRegion) {
        throw UnsupportedOperationException("Instala o importa un paquete PMTiles desde Configuración · Mapas offline")
    }
    companion object { const val BASE_ATTRIBUTION = "Fuente sin atribución declarada"; const val DEFAULT_ATTRIBUTION = "OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors" }
}
