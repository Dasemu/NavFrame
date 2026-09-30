package dev.navframe.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.navframe.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionalPackManagerTest {
    private lateinit var context: Context
    @Before fun before() { context = RuntimeEnvironment.getApplication(); File(context.filesDir, "offline").deleteRecursively() }
    @After fun after() { File(context.filesDir, "offline").deleteRecursively() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun pack(version: String = "1", badHash: Boolean = false, extra: Boolean = false): ByteArray {
        val map = context.assets.open("offline/asturias.pmtiles").use { it.readBytes() }
        val pois = context.assets.open(OfflinePlaceRepository.ASSET).use { it.readBytes() }
        val manifest = JSONObject().put("schemaVersion",1).put("id","es-asturias").put("name","Asturias test").put("version",version)
            .put("osmTimestamp","2026-09-28T00:00:00Z").put("bounds",JSONObject().put("south",42.9).put("west",-7.2).put("north",43.75).put("east",-4.45))
            .put("coverage",JSONObject("""{"type":"Polygon","coordinates":[[[-7.2,42.9],[-4.45,42.9],[-4.45,43.75],[-7.2,43.75],[-7.2,42.9]]]}"""))
            .put("minZoom",6).put("maxZoom",14).put("attribution","© OpenStreetMap contributors").put("mapSchema","NavFrame Offline/1.0.0").put("poiSchema",1).put("glyphs","noto-sans-regular-0-2047")
            .put("capabilities",org.json.JSONArray(listOf("map","poi")))
            .put("source",JSONObject().put("provider","Geofabrik").put("extractUrl","https://download.geofabrik.de/europe/spain/asturias-latest.osm.pbf").put("pbfSha256","a".repeat(64)).put("coverageUrl","https://download.geofabrik.de/europe/spain/asturias.poly").put("coverageSha256","b".repeat(64)).put("license","ODbL-1.0").put("recipe","NavFrame regional pack 1.0.0").put("planetiler","0.10.2").put("poiBuilder","NavFrame POI 1.0.0"))
            .put("map",JSONObject().put("path","map.pmtiles").put("bytes",map.size).put("sha256",if(badHash) "0".repeat(64) else hash(map)))
            .put("pois",JSONObject().put("path","pois.sqlite").put("bytes",pois.size).put("sha256",hash(pois)))
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name,bytes) in listOf("manifest.json" to manifest.toString().toByteArray(), "map.pmtiles" to map, "pois.sqlite" to pois)) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            if(extra) { zip.putNextEntry(ZipEntry("../escape")); zip.write(1); zip.closeEntry() }
        } }.toByteArray()
    }
    @Test fun remoteCatalogManifestMismatchNeverCommits() = runBlocking {
        val bytes = pack()
        val manifest = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            assertEquals("manifest.json", zip.nextEntry.name)
            parseRegionalManifest(zip.readBytes().toString(Charsets.UTF_8))
        }
        val expected = RegionalCatalogEntry(manifest.copy(name = "Different coverage label"), RegionalArchive("region.navframe", bytes.size.toLong(), hash(bytes)))
        val manager = RegionalPackManager(context)
        try {
            manager.importPackage(ByteArrayInputStream(bytes), bytes.size.toLong(), expected)
            fail("Expected remote manifest mismatch")
        } catch (_: IllegalArgumentException) { }
        manager.refresh()
        assertTrue(manager.regions.value.isEmpty())
        assertFalse(File(context.filesDir, "offline").walkTopDown().any { it.name == "region.json" })
    }
    @Test fun realCantabriaPackageImportsAndSearchesSantanderAlongsideLegacyAsturias() = runBlocking {
        val artifact = File(System.getProperty("navframe.regionalArtifact", "../dist/es-cantabria-20260928.1.navframe"))
        assertTrue("Build the real regional package before this integration test", artifact.isFile)
        val legacy = OfflineMapManager(context).installBundledAsturias()
        val manager = RegionalPackManager(context)
        val region = manager.importPackage(artifact.inputStream(), artifact.length())
        assertEquals("es-cantabria", region.manifest.id)
        assertTrue(region.manifest.covers(GeoPoint(43.46,-3.81)))
        val repo = OfflinePlaceRepository(context)
        val santander = repo.search(GeoPoint(43.46,-3.81), PlaceCategory.FUEL)
        assertTrue(santander.isNotEmpty())
        assertTrue(santander.all { region.manifest.covers(it.position) })
        val asturias = repo.search(GeoPoint(43.36,-5.85), PlaceCategory.FUEL)
        assertTrue(asturias.isNotEmpty())
        val maps = OfflineMapManager(context); maps.refresh(); assertEquals(listOf(legacy), maps.packs.value)
        Unit
    }
    @Test fun unifiedImportRetainsLegacyAndVersionsAndDeduplicatesCatalogs() = runBlocking {
        val legacy = OfflineMapManager(context).installBundledAsturias()
        val manager = RegionalPackManager(context)
        val first = manager.importPackage(ByteArrayInputStream(pack()))
        val second = manager.importPackage(ByteArrayInputStream(pack("2")))
        assertEquals(2,manager.regions.value.size)
        assertTrue(OfflineMapManager.isApprovedSource(context, second.source.uri))
        val maps = OfflineMapManager(context); maps.refresh(); assertEquals(listOf(legacy),maps.packs.value)
        val places = OfflinePlaceRepository(context).search(GeoPoint(43.36,-5.85),PlaceCategory.FUEL)
        assertEquals(places.size,places.map { it.id }.distinct().size)
        assertTrue(places.size <= 100)
        manager.delete(first.folderId)
        assertEquals(second,manager.regions.value.single())
        Unit
    }
    @Test fun checksumAndUnexpectedPathRejectWithoutChangingInstalledRegion() = runBlocking {
        val manager = RegionalPackManager(context)
        val first = manager.importPackage(ByteArrayInputStream(pack()))
        for (bytes in listOf(pack("2",badHash=true),pack("2",extra=true))) {
            try { manager.importPackage(ByteArrayInputStream(bytes)); fail("Expected invalid package") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(first),manager.regions.value)
            assertFalse(File(context.filesDir,"offline").listFiles().orEmpty().any { it.name.startsWith(".region-") })
        }
        Unit
    }
}
