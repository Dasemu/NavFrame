package dev.navframe.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.navframe.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.*

/** Bundled POIs are an independent regional catalog: installing it never replaces an installed map pack. */
class OfflinePlaceRepository(context: Context) {
    private val context = context.applicationContext
    private val file = File(this.context.filesDir, "places/asturias.sqlite")
    private val regional = RegionalPackManager(this.context)
    private var ready = false
    private fun database() = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    suspend fun install() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (ready && file.isFile) return@withLock
            file.parentFile?.mkdirs()
            if (!file.isFile || digest(file) != BUNDLED_SHA256) {
                val pending = File(file.path + ".part")
                try {
                    context.assets.open(ASSET).use { input -> pending.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    } }
                    require(pending.length() == BUNDLED_BYTES && digest(pending) == BUNDLED_SHA256) { "Catálogo de lugares inválido" }
                    currentCoroutineContext().ensureActive()
                    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally { pending.delete() }
            }
            database().use { db -> require(db.version == 1) { "Catálogo de lugares incompatible" } }
            ready = true
        }
    }
    /** Name/category text queries span installed catalogs; empty category browsing stays within 50 km. */
    suspend fun search(center: GeoPoint, category: PlaceCategory? = null, query: String = "", limit: Int = 100): List<OfflinePlace> = withContext(Dispatchers.IO) {
        require(center.latitude.isFinite() && center.longitude.isFinite())
        require(query.length <= 200) { "Busca un nombre de hasta 200 caracteres" }
        require(limit in 1..100)
        val normalized = normalizePlaceSearch(query)
        if (normalized.isNotEmpty() || OfflineBounds.ASTURIAS.contains(center.latitude, center.longitude)) install()
        regional.withCatalogs { installed ->
        val catalogs = RegionalPackManager.latest(installed)
        val sources = catalogs.map { it.catalogFile to it.manifest } + if (file.isFile) listOf(file to null) else emptyList()
        val radius = if (normalized.isEmpty()) 50_000.0 else Double.POSITIVE_INFINITY
        val latDelta = 50_000.0 / 111_000
        val lonDelta = latDelta / cos(Math.toRadians(center.latitude))
        val where = if (normalized.isEmpty()) mutableListOf("latitude BETWEEN ? AND ?", "longitude BETWEEN ? AND ?") else mutableListOf()
        val args = if (normalized.isEmpty()) mutableListOf((center.latitude - latDelta).toString(), (center.latitude + latDelta).toString(), (center.longitude - lonDelta).toString(), (center.longitude + lonDelta).toString()) else mutableListOf()
        if (category != null) { where += "category = ?"; args += category.name }
        if (normalized.isNotEmpty()) {
            val matchingCategories = PlaceCategory.entries.filter { normalizePlaceSearch(it.labelSpanish).contains(normalized) || normalizePlaceSearch(it.name).contains(normalized) }
            where += "(search_name LIKE ? ESCAPE '\\'" + matchingCategories.joinToString("") { " OR category = ?" } + ")"
            args += "%${escapePlaceLike(normalized)}%"
            args += matchingCategories.map { it.name }
        }
        val weight = cos(Math.toRadians(center.latitude)).pow(2)
        args += listOf(center.latitude, center.latitude, center.longitude, center.longitude, weight).map { it.toString() }
        val sql = "SELECT id,name,category,latitude,longitude,address,opening_hours,website,phone FROM places WHERE ${where.joinToString(" AND ")} ORDER BY (latitude-?)*(latitude-?)+(longitude-?)*(longitude-?)*? LIMIT 500"
        try {
            val found = mutableListOf<OfflinePlace>()
            for ((source, manifest) in sources) {
            SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db -> db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val point = GeoPoint(cursor.getDouble(3), cursor.getDouble(4))
                    val distance = distance(center, point)
                    if (distance > radius || (manifest != null && !manifest.covers(point)) ||
                        (manifest == null && !OfflineBounds.ASTURIAS.contains(point.latitude, point.longitude))) continue
                    fun optional(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
                    found += OfflinePlace(cursor.getString(0), cursor.getString(1), PlaceCategory.valueOf(cursor.getString(2)), point, optional(5), optional(6), optional(7), optional(8), distance)
                }
            } }
            }
            found.sortedBy { it.distanceMeters }.distinctBy { it.id }.take(limit)
        } catch (error: android.database.SQLException) { ready = false; throw IOException("No se pudo leer el catálogo de lugares") }
        }
    }
    /** Camera-only local query: never obtains location or contacts a network provider. */
    suspend fun visible(viewport: PlaceViewport, limit: Int = 300): List<OfflinePlace> = withContext(Dispatchers.IO) {
        require(limit in 1..500)
        install()
        regional.withCatalogs { installed ->
            val sources = RegionalPackManager.latest(installed).map { it.catalogFile to it.manifest } + listOf(file to null)
            val longitude = if (viewport.west <= viewport.east) "longitude BETWEEN ? AND ?" else "(longitude >= ? OR longitude <= ?)"
            val sql = "SELECT id,name,category,latitude,longitude,address,opening_hours,website,phone FROM places WHERE latitude BETWEEN ? AND ? AND $longitude LIMIT 500"
            val args = arrayOf(viewport.south.toString(), viewport.north.toString(), viewport.west.toString(), viewport.east.toString())
            val found = mutableListOf<OfflinePlace>()
            for ((source, manifest) in sources) {
                currentCoroutineContext().ensureActive()
                SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.rawQuery(sql, args).use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val point = GeoPoint(cursor.getDouble(3), cursor.getDouble(4))
                            if (manifest != null && !manifest.covers(point) || manifest == null && !OfflineBounds.ASTURIAS.contains(point.latitude, point.longitude)) continue
                            fun optional(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
                            found += OfflinePlace(cursor.getString(0), cursor.getString(1), PlaceCategory.valueOf(cursor.getString(2)), point, optional(5), optional(6), optional(7), optional(8))
                        }
                    }
                }
            }
            found.distinctBy { it.id }.take(limit)
        }
    }
    /** Optional additive table: older regional packs continue to work without camera data. */
    suspend fun speedCameras(center: GeoPoint): List<SpeedCamera> = withContext(Dispatchers.IO) {
        require(center.latitude.isFinite() && center.longitude.isFinite() && center.latitude in -90.0..90.0 && center.longitude in -180.0..180.0)
        install()
        regional.withCatalogs { installed ->
            val sources = RegionalPackManager.latest(installed).map { it.catalogFile to it.manifest } + listOf(file to null)
            val found = mutableListOf<SpeedCamera>()
            val delta = 700.0 / 111_000
            val longitudeDelta = delta / cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01)
            for ((source, manifest) in sources) {
                currentCoroutineContext().ensureActive()
                SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    val hasCameras = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='speed_cameras'", null).use { it.moveToFirst() }
                    if (hasCameras) db.rawQuery("SELECT id,latitude,longitude,direction FROM speed_cameras WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT 500", arrayOf(
                        (center.latitude-delta).toString(), (center.latitude+delta).toString(), (center.longitude-longitudeDelta).toString(), (center.longitude+longitudeDelta).toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            val point = GeoPoint(cursor.getDouble(1), cursor.getDouble(2))
                            if (cameraDistance(center, point) <= 700 && (manifest?.covers(point) ?: OfflineBounds.ASTURIAS.contains(point.latitude, point.longitude)))
                                found += SpeedCamera(cursor.getString(0), point, if (cursor.isNull(3)) null else cursor.getString(3))
                        }
                    }
                }
            }
            found.distinctBy { it.id }.sortedBy { cameraDistance(center, it.position) }
        }
    }
    suspend fun covers(center: GeoPoint): Boolean = OfflineBounds.ASTURIAS.contains(center.latitude, center.longitude) || regional.withCatalogs { regions -> regions.any { it.manifest.covers(center) } }
    private fun digest(value: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        value.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    companion object {
        const val ASSET = "offline/asturias-pois.sqlite"
        const val BUNDLED_BYTES = 2347008L
        const val BUNDLED_SHA256 = "c5d8413f260b0b3e5bdc3da4c9b5e33f8e66f5acd80cc284a7e8668f039798f9"
        private val gate = Mutex()
        private fun distance(a: GeoPoint, b: GeoPoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6_371_008.8 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
