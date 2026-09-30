package dev.navframe.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.navframe.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflinePlaceRepositoryTest {
    private lateinit var context: Context
    private val gijon = GeoPoint(43.36, -5.85)
    @Before fun before() { context = RuntimeEnvironment.getApplication(); File(context.filesDir, "places").deleteRecursively() }
    @After fun after() { File(context.filesDir, "places").deleteRecursively() }

    @Test fun realBundledCatalogInstallsVerifiedRowsWithoutChangingExistingMapPack() = runBlocking {
        val oldPack = File(context.filesDir, "offline/existing-08/map.pmtiles").apply { parentFile.mkdirs(); writeText("existing map") }
        try {
            OfflinePlaceRepository(context).install()
            val dbFile = File(context.filesDir, "places/asturias.sqlite")
            assertEquals(OfflinePlaceRepository.BUNDLED_BYTES, dbFile.length())
            assertEquals(OfflinePlaceRepository.BUNDLED_SHA256, MessageDigest.getInstance("SHA-256").digest(dbFile.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                db.rawQuery("SELECT category,COUNT(*) FROM places GROUP BY category", null).use { cursor ->
                    val counts = mutableMapOf<String, Int>(); while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1)
                    assertEquals(mapOf("FUEL" to 259, "WORKSHOP" to 196, "FOOD" to 4393, "LODGING" to 1383, "SHOPPING" to 5226), counts)
                }
            }
            assertEquals("existing map", oldPack.readText())
        } finally { oldPack.parentFile.deleteRecursively() }
        Unit
    }

    @Test fun accentInsensitiveNamesAndSqlSpecialCharactersUseActualCatalogSafely() = runBlocking {
        val repo = OfflinePlaceRepository(context)
        val result = repo.search(gijon, PlaceCategory.FOOD, "  CAFE SR. O  ")
        assertTrue(result.any { it.name == "Café Sr. Ó" })
        assertTrue(repo.search(gijon, query = "%' OR 1=1 --").isEmpty())
        assertTrue(repo.search(gijon, query = "%_").isEmpty())
        Unit
    }

    @Test fun categoryBrowsingIsBoundedSortedAndOfflineOutsideAsturiasIsEmpty() = runBlocking {
        val repo = OfflinePlaceRepository(context)
        assertTrue(repo.search(GeoPoint(40.4, -3.7), PlaceCategory.FUEL).isEmpty())
        assertFalse(File(context.filesDir, "places/asturias.sqlite").exists())
        val found = repo.search(gijon, PlaceCategory.FUEL, limit = 12)
        assertEquals(12, found.size)
        assertTrue(found.all { it.category == PlaceCategory.FUEL && it.distanceMeters!! <= 50_000 })
        assertEquals(found.map { it.distanceMeters }.sortedBy { it }, found.map { it.distanceMeters })
        Unit
    }

    @Test fun textSearchFindsInstalledPlacesFromOutsideCoverageAndCategoryLabels() = runBlocking {
        val repo = OfflinePlaceRepository(context)
        assertTrue(repo.search(GeoPoint(40.4, -3.7), query = "Café Sr. Ó").any { it.name == "Café Sr. Ó" })
        val category = repo.search(gijon, query = "Gasolina")
        assertTrue(category.isNotEmpty())
        assertTrue(category.all { it.category == PlaceCategory.FUEL || normalizePlaceSearch(it.name).contains("gasolina") })
        val viewport = repo.visible(PlaceViewport(43.3, -5.95, 43.6, -5.5), limit = 25)
        assertTrue(viewport.isNotEmpty())
        assertTrue(viewport.size <= 25)
        assertEquals(viewport.size, viewport.distinctBy { it.id }.size)
        assertTrue(viewport.all { it.position.latitude in 43.3..43.6 && it.position.longitude in -5.95..-5.5 })
        Unit
    }

    @Test fun corruptPrivateCatalogIsReplacedByVerifiedBundledCatalog() = runBlocking {
        val repo = OfflinePlaceRepository(context)
        repo.install()
        File(context.filesDir, "places/asturias.sqlite").writeText("truncated database")
        val recovered = OfflinePlaceRepository(context).search(gijon, PlaceCategory.FUEL, limit = 1)
        assertEquals(1, recovered.size)
        assertFalse(File(context.filesDir, "places/asturias.sqlite.part").exists())
        Unit
    }
}
