package dev.navframe.app

import android.content.Context
import dev.navframe.core.TileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.UUID

/** Private, persistent PMTiles packs; no tile scraping and no offline routing database. */
class OfflineMapManager(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "offline")
    private val mutablePacks = MutableStateFlow<List<OfflineMapPack>>(emptyList())
    val packs: StateFlow<List<OfflineMapPack>> = mutablePacks.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) { gate.withLock { refreshLocked() } }

    suspend fun installBundledAsturias(): OfflineMapPack {
        val input = context.assets.open("offline/asturias.pmtiles")
        return importArchive(input, "Asturias · OSM 2026-09-28", BUNDLED_ASTURIAS_BYTES)
    }

    /** Owns and closes input, including validation errors and cancellation. Cancellation removes partial copies. */
    suspend fun importArchive(input: InputStream, name: String, totalBytes: Long? = null): OfflineMapPack = input.use { stream ->
        require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() }) { "Nombre offline inválido" }
        require(totalBytes == null || totalBytes in 127..MAX_ARCHIVE_BYTES) { "Archivo offline demasiado grande o vacío" }
        withContext(Dispatchers.IO) {
            gate.withLock {
                refreshLocked()
                require(mutablePacks.value.size < MAX_PACKS) { "Elimina una región antes de importar otra (máximo 3)" }
                val existingBytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val remainingBudget = minOf(MAX_ARCHIVE_BYTES, MAX_TOTAL_BYTES - existingBytes)
                require(totalBytes == null || totalBytes <= remainingBudget) { "Límite de almacenamiento offline: 1 GiB" }
                root.mkdirs()
                require(root.usableSpace >= (totalBytes ?: MAX_ARCHIVE_BYTES) + 50L * 1024 * 1024) { "Espacio libre insuficiente para el mapa" }
                val id = UUID.randomUUID().toString()
                val folder = File(root, id)
                check(folder.mkdir()) { "No se pudo crear la región offline" }
                val archive = File(folder, "map.pmtiles")
                val progress = OfflineMapPack(id, name.trim(), TileSource(localUri(File(folder, "style.json")), ATTRIBUTION),
                    0, OfflineBounds.ASTURIAS, 6, 14, OfflinePackState.IMPORTING, 0, totalBytes)
                fun publish(bytes: Long) {
                    mutablePacks.value = mutablePacks.value.filterNot { it.id == id } + progress.copy(bytes = bytes, completedBytes = bytes)
                }
                publish(0)
                var committed = false
                try {
                    var bytes = 0L
                    var published = 0L
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            bytes += count
                            require(bytes <= remainingBudget) { "Límite de almacenamiento offline excedido" }
                            output.write(buffer, 0, count)
                            if (bytes - published >= 1024 * 1024) { publish(bytes); published = bytes }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (totalBytes != null) require(bytes == totalBytes) { "Copia offline incompleta" }
                    val info = PmtilesInfo.read(archive)
                    val style = OfflineMapStyle.create(localUri(archive))
                    File(folder, "style.json").writeText(style)
                    val metadata = JSONObject().put("version", 1).put("name", name.trim()).put("bytes", bytes)
                    File(folder, "pack.json").writeText(metadata.toString()) // Commit marker written after validation/resources.
                    committed = true
                    refreshLocked()
                    mutablePacks.value.first { it.id == id }.also {
                        check(it.bounds == info.bounds) { "Metadatos offline inconsistentes" }
                    }
                } finally {
                    if (!committed) {
                        folder.deleteRecursively()
                        mutablePacks.value = mutablePacks.value.filterNot { it.id == id }
                    }
                }
            }
        }
    }

    /** Caller must deselect this source and release renderers/MapView before deleting an active pack. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            require(ID_PATTERN.matches(id)) { "Identificador offline inválido" }
            val folder = File(root, id)
            require(folder.canonicalFile.parentFile == root.canonicalFile) { "Región offline inválida" }
            check(!folder.exists() || folder.deleteRecursively()) { "No se pudo eliminar la región offline" }
            refreshLocked()
        }
    }

    fun approvedSource(uri: String): Boolean = isApprovedSource(context, uri)

    private fun refreshLocked() {
        root.mkdirs()
        mutablePacks.value = root.listFiles().orEmpty().filter { it.isDirectory && ID_PATTERN.matches(it.name) }.mapNotNull { folder ->
            try {
                if (File(folder, "region.json").isFile) return@mapNotNull null
                val marker = File(folder, "pack.json")
                if (!marker.isFile) {
                    folder.deleteRecursively() // Recover an interrupted copy; the shared gate excludes active imports.
                    return@mapNotNull null
                }
                if (marker.length() > 4096) return@mapNotNull null
                val metadata = JSONObject(marker.readText())
                if (metadata.getInt("version") != 1 || !File(folder, "style.json").isFile) return@mapNotNull null
                val archive = File(folder, "map.pmtiles")
                val info = PmtilesInfo.read(archive)
                OfflineMapPack(folder.name, metadata.getString("name").take(100),
                    TileSource(localUri(File(folder, "style.json")), ATTRIBUTION), archive.length(), info.bounds,
                    info.minZoom, info.maxZoom, OfflinePackState.READY, archive.length(), archive.length())
            } catch (_: Exception) { null } // Incomplete/invalid packs are never selectable.
        }.sortedBy { it.name }
    }

    companion object {
        const val ATTRIBUTION = "© OpenStreetMap contributors"
        const val BUNDLED_ASTURIAS_BYTES = 34_617_483L
        const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        const val MAX_PACKS = 3
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        internal val gate = Mutex()
        fun isApprovedSource(context: Context, uri: String): Boolean = try {
            val value = URI(uri)
            if (value.scheme != "file" || value.authority != null || value.query != null || value.fragment != null) false else {
                val file = File(value).canonicalFile
                val root = File(context.applicationContext.filesDir, "offline").canonicalFile
                val folder = file.parentFile
                file.name == "style.json" && file.isFile && folder != null && ID_PATTERN.matches(folder.name) &&
                    folder.parentFile == root && File(folder, "pack.json").isFile
            }
        } catch (_: Exception) { false }
    }
}

enum class OfflinePackState { IMPORTING, READY }
data class OfflineBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south, west, north, east).all { it.isFinite() })
        require(south >= -85.051129 && north <= 85.051129 && south < north && west >= -180 && east <= 180 && west < east)
        require(north - south <= 5 && east - west <= 10) { "Importa una región acotada, no el planeta" }
    }
    fun contains(latitude: Double, longitude: Double): Boolean = latitude in south..north && longitude in west..east
    companion object { val ASTURIAS = OfflineBounds(42.9, -7.2, 43.75, -4.45) }
}
data class OfflineMapPack(val id: String, val name: String, val source: TileSource, val bytes: Long,
    val bounds: OfflineBounds, val minZoom: Int, val maxZoom: Int, val state: OfflinePackState,
    val completedBytes: Long, val totalBytes: Long?)

private fun localUri(file: File): String = file.toURI().toASCIIString().replaceFirst("file:/", "file:///")
