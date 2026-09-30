package dev.navframe.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.navframe.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.abs

data class InstalledRegion(val folderId: String, val manifest: RegionalManifest, val source: TileSource, val bytes: Long, val catalogFile: File)
data class RegionInstallProgress(val completed: Long, val total: Long?, val stage: String)

/** Local unified packs. A new immutable directory is committed only after both resources validate. */
class RegionalPackManager(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "offline")
    private val mutableRegions = MutableStateFlow<List<InstalledRegion>>(emptyList())
    val regions = mutableRegions.asStateFlow()
    private val mutableProgress = MutableStateFlow<RegionInstallProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    suspend fun refresh() = withContext(Dispatchers.IO) { gate.withLock { scan().also { mutableRegions.value = it } } }
    /** Holds the same lock as deletion until callers have closed every SQLite cursor/connection. */
    suspend fun <T> withCatalogs(block: suspend (List<InstalledRegion>) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock { block(scan().also { mutableRegions.value = it }) }
    }
    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun importPackage(input: InputStream, totalBytes: Long? = null, expectedEntry: RegionalCatalogEntry? = null): InstalledRegion = input.use { stream -> withContext(Dispatchers.IO) {
        require(totalBytes == null || totalBytes in 1..MAX_PACKAGE_BYTES) { "Tamaño de paquete regional inválido" }
        root.mkdirs()
        require(root.usableSpace >= (totalBytes ?: 0) * 2 + RESERVE_BYTES) { "Espacio libre insuficiente" }
        val id = UUID.randomUUID().toString()
        val stage = File(root, ".region-$id")
        activeStages.add(stage.absolutePath)
        check(stage.mkdir())
        val archive = File(stage, "package.navframe")
        val folder = File(stage, "contents").apply { check(mkdir()) }
        val committed = File(root, id)
        var installed = false
        try {
            mutableProgress.value = RegionInstallProgress(0, totalBytes, "Copiando paquete")
            archive.outputStream().use { output -> copyBounded(stream, output, totalBytes, MAX_PACKAGE_BYTES) { bytes -> mutableProgress.value = RegionInstallProgress(bytes, totalBytes, "Copiando paquete") } }
            val manifestText: String
            val manifest: RegionalManifest
            ZipFile(archive).use { zip ->
                val entries = mutableListOf<java.util.zip.ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    require(entries.size < 3) { "Paquete regional: demasiados archivos" }
                    entries += enumeration.nextElement()
                }
                require(entries.size == 3 && entries.map { it.name }.toSet() == setOf("manifest.json", "map.pmtiles", "pois.sqlite") && entries.none { it.isDirectory }) { "Paquete regional: se requieren manifest.json, map.pmtiles y pois.sqlite" }
                val entry = zip.getEntry("manifest.json")
                require(entry.size in 1..MAX_MANIFEST_BYTES) { "Manifest regional demasiado grande" }
                manifestText = zip.getInputStream(entry).use { readBounded(it, MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8) }
                manifest = parseRegionalManifest(manifestText)
                require(manifest.totalBytes <= MAX_PACKAGE_BYTES) { "Región demasiado grande" }
                require(root.usableSpace >= manifest.totalBytes + RESERVE_BYTES) { "Espacio libre insuficiente" }
                for (resource in listOf(manifest.map, manifest.pois)) {
                    currentCoroutineContext().ensureActive()
                    val item = zip.getEntry(resource.path)
                    require(item.size == resource.bytes) { "Tamaño de recurso regional incorrecto" }
                    mutableProgress.value = RegionInstallProgress(0, resource.bytes, "Verificando ${resource.path}")
                    val target = File(folder, resource.path)
                    val hash = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(item).use { source -> target.outputStream().use { output ->
                        copyBounded(source, output, resource.bytes, resource.bytes, hash) { bytes -> mutableProgress.value = RegionInstallProgress(bytes, resource.bytes, "Verificando ${resource.path}") }
                        output.fd.sync()
                    } }
                    require(hex(hash.digest()) == resource.sha256) { "Checksum regional incorrecto" }
                }
            }
            val map = PmtilesInfo.read(File(folder, "map.pmtiles"))
            require(map.minZoom == manifest.minZoom && map.maxZoom == manifest.maxZoom &&
                abs(map.bounds.south - manifest.bounds.south) < 1e-6 && abs(map.bounds.north - manifest.bounds.north) < 1e-6 &&
                abs(map.bounds.west - manifest.bounds.west) < 1e-6 && abs(map.bounds.east - manifest.bounds.east) < 1e-6) { "Cobertura/zoom del mapa no coincide con manifest" }
            validatePois(File(folder, "pois.sqlite"), manifest)
            require(expectedEntry == null || manifest == expectedEntry.manifest) { "Manifest no coincide con el catálogo remoto" }
            val listed = expectedEntry ?: catalog()?.regions?.firstOrNull { it.manifest.id == manifest.id && it.manifest.version == manifest.version }
            if (listed != null) {
                require(archive.length() == listed.archive.bytes && digest(archive) == listed.archive.sha256 && manifest.map == listed.manifest.map && manifest.pois == listed.manifest.pois) { "Paquete no coincide con el catálogo local" }
            }
            gate.withLock {
                currentCoroutineContext().ensureActive()
                require(storageBytes() - archive.length() <= OfflineMapManager.MAX_TOTAL_BYTES) { "Límite de almacenamiento offline: 1 GiB" }
                val current = scan()
                current.firstOrNull { it.manifest.id == manifest.id && it.manifest.version == manifest.version }?.let { old ->
                    require(old.manifest.map == manifest.map && old.manifest.pois == manifest.pois) { "La versión instalada tiene otro checksum" }
                    return@withLock old
                }
                require(current.size < 12) { "Elimina versiones antiguas antes de importar más regiones" }
                val style = OfflineMapStyle.create(localUri(File(committed, "map.pmtiles")))
                File(folder, "style.json").writeText(style)
                File(folder, "region.json").writeText(manifestText)
                File(folder, "pack.json").writeText(JSONObject().put("version", 1).put("name", "${manifest.name} · ${manifest.version}").put("bytes", manifest.map.bytes).toString())
                Files.move(folder.toPath(), committed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                installed = true
                scan().also { mutableRegions.value = it }.first { it.folderId == id }
            }
        } finally {
            stage.deleteRecursively()
            activeStages.remove(stage.absolutePath)
            if (!installed) committed.deleteRecursively()
            mutableProgress.value = null
        }
    } }

    /** UI must release an active MapView/Snapshotter source before calling this. */
    suspend fun delete(folderId: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            require(ID.matches(folderId)) { "Identificador regional inválido" }
            val folder = File(root, folderId)
            require(File(folder, "region.json").isFile) { "Región no instalada" }
            check(folder.deleteRecursively()) { "No se pudo borrar la región" }
            mutableRegions.value = scan()
        }
    }

    suspend fun importCatalog(input: InputStream) = input.use { withContext(Dispatchers.IO) {
        val text = readBounded(it, MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8)
        parseRegionalCatalog(text)
        val file = File(context.filesDir, "regional-catalog.json")
        val part = File(file.path + ".part")
        try { part.writeText(text); Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) } finally { part.delete() }
    } }
    suspend fun catalog(): RegionalCatalog? = withContext(Dispatchers.IO) {
        val stored = File(context.filesDir, "regional-catalog.json")
        try {
            if (stored.isFile) parseRegionalCatalog(stored.inputStream().use { readBounded(it, MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8) })
            else context.assets.open("offline/regional-catalog.json").use { parseRegionalCatalog(readBounded(it, MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8)) }
        } catch (_: Exception) { null }
    }
    private fun scan(): List<InstalledRegion> {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".region-") && it.absolutePath !in activeStages }.forEach { it.deleteRecursively() }
        return root.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }.mapNotNull { folder ->
        try {
            val file = File(folder, "region.json")
            if (!file.isFile || file.length() !in 1..MAX_MANIFEST_BYTES || !File(folder, "pack.json").isFile) return@mapNotNull null
            val manifest = parseRegionalManifest(file.readText())
            if (File(folder, "map.pmtiles").length() != manifest.map.bytes || File(folder, "pois.sqlite").length() != manifest.pois.bytes || !File(folder, "style.json").isFile) return@mapNotNull null
            InstalledRegion(folder.name, manifest, TileSource(localUri(File(folder, "style.json")), manifest.attribution), manifest.totalBytes, File(folder, "pois.sqlite"))
        } catch (_: Exception) { null }
    }.sortedBy { it.manifest.name }
    }
    private fun validatePois(file: File, manifest: RegionalManifest) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            require(db.version == manifest.poiSchema) { "Esquema de lugares incompatible" }
            db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "Catálogo SQLite inválido" } }
            db.rawQuery("SELECT id,name,search_name,category,latitude,longitude,address,opening_hours,website,phone FROM places LIMIT 0", null).close()
            val hasCameras = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='speed_cameras'", null).use { it.moveToFirst() }
            if (hasCameras) {
                db.rawQuery("SELECT id,latitude,longitude,direction FROM speed_cameras LIMIT 0", null).close()
                db.rawQuery("SELECT COUNT(*) FROM speed_cameras WHERE id IS NULL OR length(id) NOT BETWEEN 1 AND 80 OR latitude IS NULL OR longitude IS NULL OR latitude NOT BETWEEN ? AND ? OR longitude NOT BETWEEN ? AND ? OR length(direction) > 40", arrayOf(manifest.bounds.south,manifest.bounds.north,manifest.bounds.west,manifest.bounds.east).map { it.toString() }.toTypedArray()).use {
                    require(it.moveToFirst() && it.getLong(0) == 0L) { "Cámaras fuera de cobertura o inválidas" }
                }
            }
            db.rawQuery("SELECT COUNT(*) FROM places WHERE latitude IS NULL OR longitude IS NULL OR latitude NOT BETWEEN ? AND ? OR longitude NOT BETWEEN ? AND ? OR category NOT IN ('FUEL','WORKSHOP','FOOD','LODGING','SHOPPING') OR name IS NULL OR length(name) NOT BETWEEN 1 AND 2048", arrayOf(manifest.bounds.south,manifest.bounds.north,manifest.bounds.west,manifest.bounds.east).map { it.toString() }.toTypedArray()).use { require(it.moveToFirst() && it.getLong(0) == 0L) { "Lugares fuera de cobertura o inválidos" } }
        }
    }
    private suspend fun copyBounded(input: InputStream, output: OutputStream, expected: Long?, maximum: Long, digest: MessageDigest? = null, progress: (Long) -> Unit) {
        var bytes = 0L; var published = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer); if (count < 0) break
            bytes += count; require(bytes <= maximum) { "Paquete regional demasiado grande" }
            output.write(buffer, 0, count); digest?.update(buffer, 0, count)
            if (bytes - published >= 1024 * 1024) { progress(bytes); published = bytes }
        }
        require(expected == null || bytes == expected) { "Paquete regional incompleto" }
        progress(bytes)
    }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hex(hash.digest())
    }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "Manifest demasiado grande" }; output.write(buffer,0,count) }
        return output.toByteArray()
    }
    companion object {
        private val gate get() = OfflineMapManager.gate
        private val activeStages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun latest(regions: List<InstalledRegion>): List<InstalledRegion> = regions.groupBy { it.manifest.id }.values.map { versions ->
            versions.maxWith(compareBy<InstalledRegion> { it.manifest.osmTimestamp }.thenComparator { a, b -> compareVersions(a.manifest.version, b.manifest.version) })
        }
        fun compareVersions(a: String, b: String): Int {
            val parts = Regex("[0-9]+|[^0-9]+")
            val left = parts.findAll(a).map { it.value }.toList(); val right = parts.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i].toBigIntegerOrNull(); val y = right[i].toBigIntegerOrNull()
                val result = if (x != null && y != null) x.compareTo(y) else left[i].compareTo(right[i])
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }
        private const val MAX_PACKAGE_BYTES = 600L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val RESERVE_BYTES = 50L * 1024 * 1024
        fun localUri(file: File) = file.toURI().toASCIIString().replaceFirst("file:/", "file:///")
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
