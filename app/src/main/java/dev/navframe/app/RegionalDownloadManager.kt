package dev.navframe.app

import dev.navframe.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.io.FilterInputStream
import java.io.InputStream

/** User-configured HTTPS catalogs only. Downloads live with the activity and cancel on destruction. */
class RegionalDownloadManager(private val packs: RegionalPackManager) {
    suspend fun fetchCatalog(address: String): RegionalCatalog = withContext(Dispatchers.IO) {
        withTimeout(60_000) { withConnection(address) { connection ->
            require(connection.contentLengthLong <= MAX_CATALOG_BYTES) { "Catálogo demasiado grande" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CATALOG_BYTES) { "Catálogo demasiado grande" }
                    output.write(buffer, 0, count)
                }
                parseRegionalCatalog(output.toString("UTF-8"))
            }
        } }
    }

    suspend fun install(catalogAddress: String, entry: RegionalCatalogEntry): InstalledRegion = withContext(Dispatchers.IO) {
        require(entry.archive.bytes <= MAX_PACKAGE_BYTES) { "Región demasiado grande para este dispositivo" }
        val address = entry.archive.url ?: validateAddress(catalogAddress).resolve(entry.archive.path).toString()
        withTimeout(30 * 60_000L) { withConnection(address) { connection ->
            require(connection.contentLengthLong < 0 || connection.contentLengthLong == entry.archive.bytes) { "Tamaño de descarga incorrecto" }
            connection.inputStream.use { input ->
                val verified = VerifiedArchiveStream(input, entry.archive.bytes, entry.archive.sha256)
                packs.importPackage(verified, entry.archive.bytes, entry)
            }
        } }
    }

    private suspend fun <T> withConnection(address: String, action: suspend (HttpURLConnection) -> T): T {
        var uri = validateAddress(address)
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 5) { "Demasiadas redirecciones" }
                    val location = connection.getHeaderField("Location") ?: error("Redirección incompleta")
                    uri = validateAddress(uri.resolve(location).toString())
                } else {
                    require(status == 200) { "Servidor de regiones no disponible" }
                    return action(connection)
                }
            } finally { connection.disconnect() }
        }
        error("Demasiadas redirecciones")
    }

    companion object {
        const val MAX_CATALOG_BYTES = 32 * 1024 * 1024
        const val MAX_PACKAGE_BYTES = 600L * 1024 * 1024
        fun validateAddress(address: String): URI {
            require(address.length in 1..2048) { "URL de catálogo inválida" }
            val uri = URI(address.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "Se requiere una URL HTTPS sin credenciales" }
            return uri
        }
    }
}

/** Checks the archive before the importer is allowed to validate and commit its extracted contents. */
internal class VerifiedArchiveStream(input: InputStream, private val expected: Long, private val checksum: String) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var verified = false
    override fun read(): Int {
        val value = `in`.read()
        if (value < 0) finish() else { count++; require(count <= expected) { "Descarga demasiado grande" }; digest.update(value.toByte()) }
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val size = `in`.read(buffer, offset, length)
        if (size < 0) finish() else { count += size; require(count <= expected) { "Descarga demasiado grande" }; digest.update(buffer, offset, size) }
        return size
    }
    private fun finish() {
        if (verified) return
        require(count == expected) { "Descarga incompleta" }
        require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == checksum) { "Checksum de descarga incorrecto" }
        verified = true
    }
}
