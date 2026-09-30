package dev.navframe.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NominatimGeocoderTest {
    private class Connection(private val status: Int = 200, private val body: String = "[]", private val block: Boolean = false) : HttpURLConnection(URL("https://example.test/search")) {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { closed.countDown() }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Retry-After") "60" else null
        override fun getInputStream(): InputStream {
            if (!block) return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
            return object : InputStream() {
                override fun read(): Int { reading.countDown(); closed.await(5, TimeUnit.SECONDS); return -1 }
            }
        }
    }
    @Test fun normalizedEmptyResultsAreCachedWithoutAnotherNetworkRequest() = runBlocking {
        var count = 0
        val http = Connection()
        val geocoder = NominatimGeocoder("https://cache.example.test/search") { count++; http }
        assertTrue(geocoder.search("  Oviedo   centro  ").isEmpty())
        assertTrue(geocoder.search("Oviedo centro").isEmpty())
        assertEquals(1, count)
        assertEquals("GET", http.requestMethod)
    }
    @Test fun unicodeQueryIsEncodedAndResultKeepsAddressContextWithoutSendingGps() = runBlocking {
        var url = ""
        val http = Connection(body = """[{"lat":"43.36","lon":"-5.85","display_name":"Plaza España, Oviedo, Asturias, España","licence":"© OpenStreetMap contributors"}]""")
        val geocoder = NominatimGeocoder("https://context.example.test/search") { url = it; http }
        val result = geocoder.search("Plaza España Oviedo").single()
        assertTrue(url.contains("Espa%C3%B1a"))
        assertFalse(url.contains("viewbox")); assertFalse(url.contains("lat=")); assertFalse(url.contains("lon="))
        assertTrue(result.name.contains("Asturias"))
        assertTrue(result.attribution.contains("OpenStreetMap"))
        assertTrue(http.getRequestProperty("User-Agent").startsWith("NavFrame/0.6"))
    }
    @Test fun cancellingSearchClosesItsConnectionAndBlockingRead() = runBlocking {
        val http = Connection(block = true)
        val geocoder = NominatimGeocoder("https://cancel.example.test/search") { http }
        val job = launch { geocoder.search("Oviedo") }
        withTimeout(5_000) { while (http.reading.count > 0) delay(10) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(0L, http.closed.count)
    }
    @Test fun rateRejectionBlocksAnotherExplicitAttemptWithoutRetryLoop() = runBlocking {
        var count = 0
        val geocoder = NominatimGeocoder("https://busy.example.test/search") { count++; Connection(429) }
        try { geocoder.search("Oviedo"); fail() } catch (error: IOException) { assertTrue(error.message!!.contains("HTTP429")) }
        try { geocoder.search("Gijón"); fail() } catch (error: IOException) { assertTrue(error.message!!.contains("pausa")) }
        assertEquals(1, count)
    }    @Test fun separateInstancesShareTheApplicationRequestCadence() = runBlocking {
        val starts = mutableListOf<Long>()
        val first = NominatimGeocoder("https://first-cadence.example.test/search") { starts += System.nanoTime(); Connection() }
        val second = NominatimGeocoder("https://second-cadence.example.test/search") { starts += System.nanoTime(); Connection() }
        first.search("Oviedo centro")
        second.search("Gijón centro")
        assertEquals(2, starts.size)
        assertTrue("Separate clients must retain the aggregate one-request/second limit", (starts[1] - starts[0]) / 1_000_000 >= 995)
    }
    @Test fun retryAfterHonorsLongDurationsAndHttpDatesWithoutShorteningServerWait() {
        assertEquals(90_000_000L, NominatimGeocoder.retryAfterDelayMs("90000", 0))
        assertEquals(Long.MAX_VALUE, NominatimGeocoder.retryAfterDelayMs(Long.MAX_VALUE.toString(), 0))
        val date = java.time.ZonedDateTime.parse("Thu, 01 Jan 1970 00:00:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).plusDays(2)
        assertEquals(172_800_000L, NominatimGeocoder.retryAfterDelayMs(date.format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME), 0))
    }

}
