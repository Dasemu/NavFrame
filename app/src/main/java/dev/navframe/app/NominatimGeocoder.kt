package dev.navframe.app

import dev.navframe.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Button-triggered prototype search only: no autocomplete, GPS, viewbox or persisted query history. */
class NominatimGeocoder(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : Geocoder {
    init { validateEndpoint(endpoint) }
    override suspend fun search(query: String): List<SearchResult> {
        val parameters = nominatimSearchParameters(query)
        val encoded = parameters.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val key = endpoint.trim() + "|" + encoded
        cached(key)?.let { return it }
        return withTimeout(20_000) {
            gate.withLock {
                cached(key)?.let { return@withLock it }
                val now = clockMs()
                if (now < (blockedUntil[endpoint.trim()] ?: 0L)) throw IOException("Proveedor en pausa: espera antes de buscar de nuevo")
                val wait = 1_000 - (now - lastRequestMs)
                if (wait > 0) delay(wait)
                val response = request(endpoint.trim() + (if (endpoint.contains('?')) "&" else "?") + encoded)
                val results = withContext(Dispatchers.Default) { parseNominatimResults(response) }
                synchronized(cache) {
                    cache[key] = CacheEntry(clockMs(), results)
                    while (cache.size > 100) cache.remove(cache.keys.first())
                }
                results
            }
        }
    }
    private suspend fun request(url: String): String = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val worker = launch(Dispatchers.IO) {
                try {
                    ensureActive()
                    lastRequestMs = clockMs()
                    val http = openConnection(url)
                    connection.set(http)
                    ensureActive()
                    try {
                        http.requestMethod = "GET"
                        http.connectTimeout = 8_000; http.readTimeout = 12_000
                        http.instanceFollowRedirects = false
                        http.setRequestProperty("User-Agent", "NavFrame/0.6 (Android navigation prototype)")
                        http.setRequestProperty("Accept", "application/json")
                        val status = http.responseCode
                        if (status != 200) {
                            if (status == 429) {
                                val waitMs = retryAfterDelayMs(http.getHeaderField("Retry-After"))
                                val now = clockMs()
                                blockedUntil[endpoint.trim()] = if (waitMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + waitMs
                            }
                            throw IOException(if (status == 429) "Proveedor ocupado (HTTP429): espera antes de buscar de nuevo" else "Búsqueda no disponible (HTTP$status)")
                        }
                        val bytes = http.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 1_000_000) { "Respuesta de búsqueda demasiado grande" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
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
        const val DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org/search"
        private data class CacheEntry(val storedMs: Long, val results: List<SearchResult>)
        private val cache = LinkedHashMap<String, CacheEntry>()
        private val gate = Mutex()
        private var lastRequestMs = 0L
        private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private fun clockMs() = System.nanoTime() / 1_000_000
        private fun cached(key: String): List<SearchResult>? = synchronized(cache) {
            cache[key]?.takeIf { clockMs() - it.storedMs < 86_400_000 }?.results
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun retryAfterDelayMs(header: String?, epochMs: Long = System.currentTimeMillis()): Long {
            val normalized = header?.trim()
            normalized?.toLongOrNull()?.takeIf { it >= 0 }?.let {
                return if (it > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else (it * 1_000).coerceAtLeast(1_000)
            }
            return try {
                (java.time.ZonedDateTime.parse(normalized, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - epochMs).coerceAtLeast(1_000)
            } catch (_: Exception) { 60_000 }
        }
        fun validateEndpoint(value: String) {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Endpoint HTTPS inválido" }
        }
    }
}
