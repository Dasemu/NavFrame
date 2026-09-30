package dev.navframe.app

import dev.navframe.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RoutingHttpException(val status: Int, val retryAfterMs: Long) : IOException("Servidor de rutas: HTTP $status")

/** Explicit prototype requests only. No automatic recalc, request retry or location history. */
class ValhallaRoutingEngine(private val endpoint: String, private val clientId: String, private val language: () -> String = { "es-ES" }, private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) : RoutingEngine {
    init { validateEndpoint(endpoint) }
    override suspend fun calculateRoute(origin: GeoPoint, destination: GeoPoint, options: RouteOptions): RouteResult {
        validatePoint(origin); validatePoint(destination)
        val locale = GuidanceLanguage.resolve(language()).tag
        val body = buildValhallaRequest(origin, destination, options, locale)
        val response = withTimeout(25_000) {
            rateGate.withLock {
                val wait = 1_000 - (System.nanoTime() / 1_000_000 - lastRequestMs)
                if (wait > 0) delay(wait)
                lastRequestMs = System.nanoTime() / 1_000_000
            }
            request(body)
        }
        return withContext(Dispatchers.Default) { parseValhallaRoute(response, destination).copy(guidanceLanguage = locale) }
    }
    override suspend fun recalculateRoute(origin: GeoPoint, previousRoute: RouteResult, options: RouteOptions): RouteResult =
        calculateRoute(origin, requireNotNull(previousRoute.destination) { "La ruta no tiene destino" }, options)

    private suspend fun request(body: String): String = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val worker = launch(Dispatchers.IO) {
                try {
                    val http = openConnection(endpoint.trim())
                    connection.set(http)
                    ensureActive()
                    try {
                        http.requestMethod = "POST"; http.doOutput = true
                        http.connectTimeout = 10_000; http.readTimeout = 15_000
                        http.instanceFollowRedirects = false
                        http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        http.setRequestProperty("User-Agent", "NavFrame/0.5 (Android prototype)")
                        http.setRequestProperty("X-Client-Id", clientId)
                        http.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val status = http.responseCode
                        if (status != 200) {
                            val retryAfter = http.getHeaderField("Retry-After")?.trim()
                            val waitMs = retryAfter?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1_000) ?: run {
                                try { (java.time.ZonedDateTime.parse(retryAfter, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86_400_000) }
                                catch (_: Exception) { 0L }
                            }
                            throw RoutingHttpException(status, waitMs)
                        }
                        val bytes = http.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 2_000_000) { "Respuesta de ruta demasiado grande" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(bytes.size <= 2_000_000) { "Respuesta de ruta demasiado grande" }
                        if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8))
                    } finally { http.disconnect(); connection.set(null) }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); worker.cancel() }
        }
    }
    companion object {
        const val DEFAULT_ENDPOINT = "https://valhalla1.openstreetmap.de/route"
        private val rateGate = Mutex()
        private var lastRequestMs = 0L
        fun validatePoint(point: GeoPoint) {
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude.isFinite() && point.longitude in -180.0..180.0) { "Coordenadas fuera de rango" }
        }
        fun validateEndpoint(value: String) {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Endpoint HTTPS inválido" }
        }
    }
}
