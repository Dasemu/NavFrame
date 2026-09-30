package dev.navframe.app

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Bounded v3 archive/header validation. Native MapLibre owns actual PMTiles directory/tile decoding. */
data class PmtilesInfo(val bounds: OfflineBounds, val minZoom: Int, val maxZoom: Int) {
    companion object {
        fun read(file: File): PmtilesInfo = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length in 127..OfflineMapManager.MAX_ARCHIVE_BYTES) { "Tamaño PMTiles inválido" }
            val bytes = ByteArray(127); input.readFully(bytes)
            require(bytes.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "PMTiles" && bytes[7].toInt() == 3) { "Se requiere PMTiles v3" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun section(offsetIndex: Int, sizeIndex: Int): Pair<Long, Long> {
                val offset = header.getLong(offsetIndex); val size = header.getLong(sizeIndex)
                require(offset >= 127 && size >= 0 && offset <= length && size <= length - offset) { "Sección PMTiles fuera del archivo" }
                return offset to size
            }
            val root = section(8, 16)
            val metadata = section(24, 32)
            val leaves = section(40, 48)
            val tiles = section(56, 64)
            require(root.second > 0 && metadata.second in 1..1024 * 1024 && tiles.second > 0) { "Archivo PMTiles incompleto" }
            val sections = listOf(root, metadata, leaves, tiles).filter { it.second > 0 }.sortedBy { it.first }
            sections.zipWithNext().forEach { (a, b) -> require(a.first + a.second <= b.first) { "Secciones PMTiles solapadas" } }
            require(bytes[97].toInt() in 1..2 && bytes[98].toInt() in 1..2 && bytes[99].toInt() == 1) { "Se requiere PMTiles vectorial MVT con gzip/none" }
            val minZoom = bytes[100].toInt() and 255; val maxZoom = bytes[101].toInt() and 255
            require(minZoom in 0..16 && maxZoom in minZoom..16) { "Rango zoom offline no admitido" }
            val bounds = OfflineBounds(header.getInt(106) / 1e7, header.getInt(102) / 1e7,
                header.getInt(114) / 1e7, header.getInt(110) / 1e7)
            input.seek(metadata.first)
            val compressed = ByteArray(metadata.second.toInt()); input.readFully(compressed)
            val source = ByteArrayInputStream(compressed)
            val data = (if (bytes[97].toInt() == 2) GZIPInputStream(source) else source).use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Metadatos PMTiles demasiado grandes" }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            val info = JSONObject(data.toString(Charsets.UTF_8))
            require(info.optString("name") == "NavFrame Offline" && info.optString("version") == "1.0.0") { "Importa un pack del esquema NavFrame Offline 1.0.0" }
            val layers = info.getJSONArray("vector_layers")
            val names = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }.toSet()
            require(names.containsAll(listOf("roads", "place"))) { "Faltan capas de carreteras/etiquetas offline" }
            PmtilesInfo(bounds, minZoom, maxZoom)
        }
    }
}
