package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class NominatimGeocodingTest {
    // Original synthetic fixture following jsonv2's documented numeric strings.
    private val fixture = """[
        {"place_id":123,"lat":"43.36","lon":"-5.85","display_name":"Destino de prueba, Asturias, España","licence":"Data © OpenStreetMap contributors, ODbL 1.0. https://osm.org/copyright"},
        {"lat":"0","lon":"180","display_name":"Punto de prueba"}
    ]"""

    @Test fun `jsonv2 preserves display label coordinates and attribution`() {
        val results = parseNominatimResults(fixture)
        assertEquals(2, results.size)
        assertEquals("Destino de prueba, Asturias, España", results[0].name)
        assertEquals(GeoPoint(43.36, -5.85), results[0].position)
        assertTrue(results[0].attribution.contains("ODbL 1.0"))
        assertEquals("© OpenStreetMap contributors", results[1].attribution)
    }

    @Test fun `empty list is a successful no match result`() {
        assertTrue(parseNominatimResults("[]").isEmpty())
    }

    @Test fun `result count and duplicate labels are bounded`() {
        val item = """{"lat":"43.36","lon":"-5.85","display_name":"Test"}"""
        assertEquals(1, parseNominatimResults("[$item,$item]").size)
        val distinct = (0..5).joinToString(",") { """{"lat":"$it","lon":"0","display_name":"Test $it"}""" }
        assertEquals(5, parseNominatimResults("[$distinct]").size)
        rejects { parseNominatimResults("[" + List(41) { item }.joinToString(",") + "]") }
    }

    @Test fun `strict malformed and backend errors omit private data`() {
        listOf("", "null", "{}", "[null]", fixture + "{}", "//comment\n$fixture",
            """{"error":"private search text"}""").forEach { value -> rejects { parseNominatimResults(value) } }
        try {
            parseNominatimResults("""{"error":"private search text"}""")
            fail("Expected rejection")
        } catch (error: GeocodingResponseException) {
            assertFalse(error.message!!.contains("private search text"))
        }
    }

    @Test fun `coordinates are strict finite numeric strings within geographic range`() {
        listOf("NaN", "Infinity", "91", "-91", "1e999", "0x1p0", "", " 43.36").forEach { latitude ->
            rejects { parseNominatimResults(fixture.replace("43.36", latitude)) }
        }
        rejects { parseNominatimResults(fixture.replace("-5.85", "181")) }
        rejects { parseNominatimResults(fixture.replace("\"lat\":\"43.36\"", "\"lat\":43.36")) }
        rejects { parseNominatimResults(fixture.replace("\"lon\":\"-5.85\"", "\"lon\":null")) }
    }

    @Test fun `required names and response sizes are validated`() {
        rejects { parseNominatimResults("""[{"lat":"0","lon":"0","display_name":" "}]""") }
        rejects { parseNominatimResults(fixture.replace("Destino de prueba, Asturias, España", "x".repeat(2_049))) }
        rejects { parseNominatimResults(" ".repeat(512 * 1024 + 1)) }
        val label = parseNominatimResults("""[{"lat":"0","lon":"0","display_name":"Test\nplace"}]""")[0].name
        assertEquals("Test place", label)
    }

    @Test fun `parameters normalize whitespace preserve Unicode and do not carry GPS`() {
        val params = nominatimSearchParameters("  Peña   de  prueba  ")
        assertEquals("Peña de prueba", params["q"])
        assertEquals("jsonv2", params["format"])
        assertEquals("5", params["limit"])
        assertEquals("es", params["accept-language"])
        assertFalse(params.containsKey("viewbox"))
        assertFalse(params.containsKey("lat"))
        assertEquals("A&B? q=other", nominatimSearchParameters("A&B? q=other")["q"])
    }

    @Test fun `invalid explicit query rejected before HTTP`() {
        listOf("", " ", "x", "x".repeat(201), "Test\u0000place").forEach { query ->
            try { nominatimSearchParameters(query); fail("Expected query rejection") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected response rejection") }
        catch (_: GeocodingResponseException) { /* expected */ }
    }
}
