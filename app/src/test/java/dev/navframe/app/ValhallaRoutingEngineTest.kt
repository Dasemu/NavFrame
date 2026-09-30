package dev.navframe.app

import dev.navframe.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ValhallaRoutingEngineTest {
    private class Connection(private val status: Int, val blockRead: Boolean = false) : HttpURLConnection(URL("https://example.test/route")) {
        val body = ByteArrayOutputStream()
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var disconnects = 0
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnects++; closed.countDown() }
        override fun getResponseCode() = status
        override fun getOutputStream(): OutputStream = body
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                if (blockRead) closed.await(5, TimeUnit.SECONDS)
                return -1
            }
        }
    }
    @Test fun serverRateRejectionIsOneRequestWithoutAutomaticRetry() = runBlocking {
        val http = Connection(429)
        var opens = 0
        val engine = ValhallaRoutingEngine("https://example.test/route", "dev.navframe.app/0.4-prototype") { opens++; http }
        try { engine.calculateRoute(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.845), RouteOptions()); fail() }
        catch (error: IOException) { assertEquals("Servidor de rutas: HTTP 429", error.message) }
        assertEquals(1, opens)
        assertEquals("POST", http.requestMethod)
        assertEquals("dev.navframe.app/0.4-prototype", http.getRequestProperty("X-Client-Id"))
        assertTrue(http.body.toString("UTF-8").contains("es-ES"))
        assertTrue(http.disconnects > 0)
    }
    @Test fun selectedGuidanceLanguageIsSentToRoutingServer() = runBlocking {
        val http = Connection(429)
        val engine = ValhallaRoutingEngine("https://example.test/route", "app-test", language = { "en-GB" }) { http }
        try { engine.calculateRoute(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.845), RouteOptions()); fail() }
        catch (_: IOException) { }
        assertTrue(http.body.toString("UTF-8").contains("en-US"))
        assertFalse(http.body.toString("UTF-8").contains("es-ES"))
    }
    @Test fun cancellingRequestClosesConnectionAndStopsBlockingRead() = runBlocking {
        val http = Connection(200, true)
        val engine = ValhallaRoutingEngine("https://example.test/route", "app-test") { http }
        val job = launch { engine.calculateRoute(GeoPoint(43.36, -5.85), GeoPoint(43.365, -5.845), RouteOptions()) }
        withTimeout(5_000) { while (http.reading.count > 0) delay(10) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(0L, http.closed.count)
        assertTrue(http.disconnects > 0)
    }
}
