package dev.navframe.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RegionalPackTest {
    private val fixture = """{"schemaVersion":1,"id":"es-test","name":"Region test","version":"2026-09-28.1",
      "osmTimestamp":"2026-09-28T20:23:05Z","bounds":{"south":42.7,"west":-4.9,"north":43.9,"east":-3.1},
      "minZoom":6,"maxZoom":14,"attribution":"© OpenStreetMap contributors","mapSchema":"NavFrame Offline/1.0.0",
      "poiSchema":1,"glyphs":"noto-sans-regular-0-2047","capabilities":["map","poi"],
      "source":{"provider":"Geofabrik","extractUrl":"https://download.geofabrik.de/europe/spain/cantabria-latest.osm.pbf",
        "pbfSha256":"${"d".repeat(64)}","coverageUrl":"https://download.geofabrik.de/europe/spain/cantabria.poly",
        "coverageSha256":"${"e".repeat(64)}","license":"ODbL-1.0","recipe":"NavFrame regional pack 1.0.0","planetiler":"0.10.2","poiBuilder":"NavFrame POI 1.0.0"},
      "coverage":{"type":"Polygon","coordinates":[[[-4.9,42.7],[-3.1,42.7],[-3.1,43.9],[-4.9,43.9],[-4.9,42.7]]]},
      "map":{"path":"map.pmtiles","bytes":10000,"sha256":"${"a".repeat(64)}"},
      "pois":{"path":"pois.sqlite","bytes":4096,"sha256":"${"b".repeat(64)}"}}"""
    private fun rejects(value: String) {
        try { parseRegionalManifest(value); fail("Expected rejection") } catch (error: IllegalArgumentException) {
            assertEquals("Paquete regional inválido o incompatible", error.message)
        }
    }
    @Test fun `manifest has two original schemas local components and bounded sizes`() {
        val pack = parseRegionalManifest(fixture)
        assertEquals(14096L, pack.totalBytes)
        assertEquals("map.pmtiles", pack.map.path)
        assertEquals("pois.sqlite", pack.pois.path)
        assertTrue(pack.covers(GeoPoint(43.4, -3.8)))
        assertFalse(pack.covers(GeoPoint(43.36, -5.85)))
    }
    @Test fun `unsupported routing schemas missing attribution and unsafe paths are rejected`() {
        listOf(
            fixture.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            fixture.replace("\"poiSchema\":1", "\"poiSchema\":2"),
            fixture.replace("NavFrame Offline/1.0.0", "OpenMapTiles"),
            fixture.replace("\"map\",\"poi\"", "\"map\",\"routing\""),
            fixture.replace("map.pmtiles", "../map.pmtiles"),
            fixture.replace("© OpenStreetMap contributors", "unknown"),
            fixture.replace("noto-sans-regular-0-2047", "https://font.invalid"),
            fixture.replace("es-test", "../../private"),
            fixture.replace("Geofabrik", "Unknown"),
            fixture.replace("download.geofabrik.de/europe/spain/cantabria-latest", "attacker.invalid/cantabria-latest"),
            fixture.replace("ODbL-1.0", "Proprietary"),
            fixture.replace("NavFrame regional pack 1.0.0", "unknown recipe"),
        ).forEach(::rejects)
    }
    @Test fun `numbers hashes dates and JSON must be strict rather than coerced`() {
        listOf(
            fixture.replace("10000", "1.5"), fixture.replace("10000", "9223372036854775808"),
            fixture.replace("10000", "524288001"), fixture.replace("10000", "\"10000\""),
            fixture.replace("4096", "-1"), fixture.replace("\"maxZoom\":14", "\"maxZoom\":19"),
            fixture.replace("43.9", "1e309"), fixture.replace("42.7", "44.0"),
            fixture.replace("2026-09-28T20:23:05Z", "2026-02-30T20:23:05Z"),
            fixture.replace("a".repeat(64), "G".repeat(64)), fixture + "{}", "// comment\n$fixture",
        ).forEach(::rejects)
    }
    @Test fun `actual coverage differentiates irregular regions holes and edge points`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        root.add("coverage", JsonParser.parseString("""{"type":"Polygon","coordinates":[
          [[-4.8,42.8],[-3.2,42.8],[-4.8,43.8],[-4.8,42.8]],
          [[-4.7,42.9],[-4.6,42.9],[-4.6,43.0],[-4.7,43.0],[-4.7,42.9]]]}"""))
        val pack = parseRegionalManifest(root.toString())
        assertTrue(pack.covers(GeoPoint(43.3, -4.7)))
        assertFalse(pack.covers(GeoPoint(43.7, -3.3))) // Inside bbox, outside triangle.
        assertFalse(pack.covers(GeoPoint(42.95, -4.65))) // Hole.
        assertTrue(pack.covers(GeoPoint(42.8, -4.8))) // Outer boundary.
        assertFalse(pack.covers(GeoPoint(42.9, -4.7))) // Hole boundary.
    }
    @Test fun `malformed coverage rings and unbounded coordinates fail closed`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        listOf("""{"type":"Polygon","coordinates":[[[-4,43],[-3.5,43],[-4,43.5]]]}""",
            """{"type":"Polygon","coordinates":[[[-20,43],[-3.5,43],[-4,43.5],[-20,43]]]}""",
            """{"type":"LineString","coordinates":[]}""").forEach {
            root.add("coverage", JsonParser.parseString(it)); rejects(root.toString())
        }
    }
    @Test fun `worldwide Geofabrik paths and multipolygon coverage parse`() {
        val root = JsonParser.parseString(fixture).asJsonObject
        root.add("bounds", JsonParser.parseString("""{"south":-50,"west":-130,"north":75,"east":-30}"""))
        val source = root.getAsJsonObject("source")
        source.addProperty("extractUrl", "https://download.geofabrik.de/north-america/us/texas-latest.osm.pbf")
        source.addProperty("coverageUrl", "https://download.geofabrik.de/north-america/us/texas.poly")
        root.add("coverage", JsonParser.parseString("""{"type":"MultiPolygon","coordinates":[
          [[[ -100,30],[-99,30],[-99,31],[-100,31],[-100,30]]],
          [[[-98,32],[-97,32],[-97,33],[-98,33],[-98,32]]]]}"""))
        val manifest = parseRegionalManifest(root.toString())
        assertTrue(manifest.covers(GeoPoint(30.5, -99.5)))
        assertTrue(manifest.covers(GeoPoint(32.5, -97.5)))
        assertFalse(manifest.covers(GeoPoint(31.5, -98.5)))
    }
    @Test fun `worldwide recipe accepts camera-enabled catalogs without breaking legacy packs`() {
        val updated = fixture.replace("NavFrame regional pack 1.0.0", "NavFrame regional pack 1.1.0")
            .replace("NavFrame POI 1.0.0", "NavFrame POI 1.1.0")
        assertEquals("NavFrame regional pack 1.1.0", parseRegionalManifest(updated).source.recipe)
        assertEquals("NavFrame regional pack 1.0.0", parseRegionalManifest(fixture).source.recipe)
    }
    @Test fun `local catalog validates original entries without fabricated remote endpoints`() {
        val item = """{"manifest":$fixture,"archive":{"path":"es-test-20260928.navframe","bytes":20000,"sha256":"${"c".repeat(64)}"}}"""
        val json = """{"schemaVersion":1,"regions":[$item]}"""
        val catalog = parseRegionalCatalog(json)
        assertEquals("es-test-20260928.navframe", catalog.regions.single().archive.path)
        listOf(json.replace("es-test-20260928.navframe", "../../private.navframe"),
            json.replace("es-test-20260928.navframe", "https://endpoint.invalid/file.navframe"),
            """{"schemaVersion":1,"regions":[$item,$item]}""").forEach {
            try { parseRegionalCatalog(it); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `global catalog optional release URL and region metadata preserve local entries`() {
        val item = """{"manifest":$fixture,"archive":{"path":"es-test-20260928.navframe","bytes":20000,
          "sha256":"${"c".repeat(64)}","url":"https://github.com/example/navframe/releases/download/v1/es-test-20260928.navframe"},
          "countryCode":"ES","country":"Spain","region":"spain/cantabria","cameraCount":22}"""
        val entry = parseRegionalCatalog("""{"schemaVersion":1,"regions":[$item]}""").regions.single()
        assertEquals("https://github.com/example/navframe/releases/download/v1/es-test-20260928.navframe", entry.archive.url)
        assertEquals("ES", entry.countryCode)
        assertEquals("Spain", entry.country)
        assertEquals("spain/cantabria", entry.region)
        assertEquals(22, entry.cameraCount)

        val old = """{"manifest":$fixture,"archive":{"path":"es-test-20260928.navframe","bytes":20000,"sha256":"${"c".repeat(64)}"}}"""
        assertNull(parseRegionalCatalog("""{"schemaVersion":1,"regions":[$old]}""").regions.single().archive.url)
        listOf(item.replace("https://github.com", "http://github.com"), item.replace("\"ES\"", "\"Spain\""), item.replace("\"cameraCount\":22", "\"cameraCount\":-1")).forEach {
            try { parseRegionalCatalog("""{"schemaVersion":1,"regions":[$it]}"""); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }
}
