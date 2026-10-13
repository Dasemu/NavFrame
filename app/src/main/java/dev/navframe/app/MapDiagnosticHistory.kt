package dev.navframe.app

import android.content.Context
import android.util.AtomicFile
import dev.navframe.core.ConnectionState
import dev.navframe.core.NavigationStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant

internal enum class MapDiagnosticKind { FAILURE, RECOVERED }
internal enum class MapDiagnosticSource { LOCAL, ONLINE }
internal enum class MapDiagnosticNetwork { CONNECTED, DISCONNECTED, UNKNOWN }

/** Only structured labels and numbers are persisted, never native messages or navigation payloads. */
internal data class MapDiagnosticEvent(
    val kind: MapDiagnosticKind,
    val timestampMs: Long,
    val source: MapDiagnosticSource,
    val network: MapDiagnosticNetwork,
    val stage: MapRenderStage,
    val failure: MapRenderFailure? = null,
    val resource: MapResourceKind = MapResourceKind.UNKNOWN,
    val reason: MapFailureReason = MapFailureReason.UNKNOWN,
    val httpStatus: Int? = null,
    val durationMs: Long,
    val attempts: Int,
    val retryMs: Long,
    val mode: NavigationMode,
    val navigation: NavigationStatus,
    val connection: ConnectionState,
    val androidApi: Int,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val errorClass: String = "SDK_CALLBACK",
    val sdkEvidence: String = "",
    val firstTimestampMs: Long = timestampMs,
    val occurrences: Int = 1,
    val screenInteractive: Boolean? = null,
    val lowMemory: Boolean? = null,
    val availableMemoryMb: Long? = null,
) {
    init {
        require(timestampMs >= 0 && durationMs >= 0 && retryMs >= 0 && attempts in 1..4)
        require(androidApi > 0 && appVersion.matches(Regex("[0-9]+(?:\\.[0-9]+){1,3}")))
        require(availableMemoryMb == null || availableMemoryMb >= 0)
        require(firstTimestampMs in 0..timestampMs && occurrences > 0)
        require(errorClass.matches(Regex("[A-Za-z0-9_$]{1,80}")))
        require(sdkEvidence.isEmpty() || sdkEvidence.matches(Regex("[A-Z_]{1,256}")))
        require(httpStatus == null || httpStatus in 400..599)
        require((kind == MapDiagnosticKind.FAILURE) == (failure != null))
    }

    fun reportLine(): String = "${Instant.ofEpochMilli(timestampMs)} · ${kind.name} · NavFrame $appVersion · Android $androidApi · MapLibre 13.5.2\n" +
        "${source.name} · red ${network.name} · ${failure?.name ?: "OK"}/${stage.name} · ${resource.name}/${reason.name}${httpStatus?.let { "/HTTP_$it" } ?: ""}/$errorClass${if (sdkEvidence.isEmpty()) "" else "/WORDS_$sdkEvidence"}\n" +
        "$durationMs ms · intento $attempts · reintento $retryMs ms · ${mode.name} · ${navigation.name} · TFT ${connection.name}" +
        "\nPantalla ${screenInteractive?.let { if (it) "ENCENDIDA" else "APAGADA" } ?: "UNKNOWN"} · memoria baja ${lowMemory ?: "UNKNOWN"} · disponible ${availableMemoryMb ?: "UNKNOWN"} MiB" +
        if (occurrences == 1) "" else "\nAgrupado: $occurrences fallos · primero ${Instant.ofEpochMilli(firstTimestampMs)} · último ${Instant.ofEpochMilli(timestampMs)} · estados del último fallo"

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("timestampMs", timestampMs)
        .put("source", source.name).put("network", network.name).put("stage", stage.name)
        .put("failure", failure?.name ?: JSONObject.NULL).put("resource", resource.name).put("reason", reason.name)
        .put("httpStatus", httpStatus ?: JSONObject.NULL).put("durationMs", durationMs).put("attempts", attempts)
        .put("retryMs", retryMs).put("mode", mode.name).put("navigation", navigation.name)
        .put("connection", connection.name).put("androidApi", androidApi).put("appVersion", appVersion)
        .put("errorClass", errorClass).put("sdkEvidence", sdkEvidence)
        .put("firstTimestampMs", firstTimestampMs).put("occurrences", occurrences)
        .put("screenInteractive", screenInteractive ?: JSONObject.NULL).put("lowMemory", lowMemory ?: JSONObject.NULL)
        .put("availableMemoryMb", availableMemoryMb ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject) = MapDiagnosticEvent(
            kind = MapDiagnosticKind.valueOf(value.getString("kind")), timestampMs = value.getLong("timestampMs"),
            source = MapDiagnosticSource.valueOf(value.getString("source")), network = MapDiagnosticNetwork.valueOf(value.getString("network")),
            stage = MapRenderStage.valueOf(value.getString("stage")),
            failure = if (value.isNull("failure")) null else MapRenderFailure.valueOf(value.getString("failure")),
            resource = MapResourceKind.valueOf(value.getString("resource")), reason = MapFailureReason.valueOf(value.getString("reason")),
            httpStatus = if (value.isNull("httpStatus")) null else value.getInt("httpStatus"),
            durationMs = value.getLong("durationMs"), attempts = value.getInt("attempts"), retryMs = value.getLong("retryMs"),
            mode = NavigationMode.valueOf(value.getString("mode")), navigation = NavigationStatus.valueOf(value.getString("navigation")),
            connection = ConnectionState.valueOf(value.getString("connection")), androidApi = value.getInt("androidApi"),
            appVersion = value.getString("appVersion"),
            errorClass = value.optString("errorClass", "SDK_CALLBACK"), sdkEvidence = value.optString("sdkEvidence", ""),
            firstTimestampMs = value.optLong("firstTimestampMs", value.getLong("timestampMs")),
            occurrences = value.optInt("occurrences", 1),
            screenInteractive = if (value.isNull("screenInteractive")) null else value.getBoolean("screenInteractive"),
            lowMemory = if (value.isNull("lowMemory")) null else value.getBoolean("lowMemory"),
            availableMemoryMb = if (value.isNull("availableMemoryMb")) null else value.getLong("availableMemoryMb"),
        )
    }
}

/** Bounded, private history; call on IO. AtomicFile syncs completed writes before replacing the old file. */
internal class MapDiagnosticHistory(private val directory: File) {
    constructor(context: Context) : this(context.applicationContext.noBackupFilesDir)
    internal val file = File(directory, "tft-map-diagnostics.json")
    private val atomic = AtomicFile(file)

    fun record(event: MapDiagnosticEvent): Boolean = synchronized(gate) {
        try {
            // An unreadable history cannot supply diagnostic text; the next event starts a clean history.
            val previous = try { readEvents() } catch (_: Exception) { emptyList() }
            val last = previous.lastOrNull()
            // Keep the initial retries, then summarize identical saturated retries without losing onset.
            val repeated = last != null && event.kind == MapDiagnosticKind.FAILURE && last.kind == event.kind &&
                last.attempts == 4 && event.attempts == 4 && event.timestampMs >= last.timestampMs &&
                last.source == event.source && last.failure == event.failure && last.stage == event.stage &&
                last.resource == event.resource && last.reason == event.reason && last.httpStatus == event.httpStatus &&
                last.errorClass == event.errorClass && last.sdkEvidence == event.sdkEvidence &&
                last.mode == event.mode && last.appVersion == event.appVersion && last.androidApi == event.androidApi
            val events = if (repeated) previous.dropLast(1) + event.copy(
                firstTimestampMs = last!!.firstTimestampMs, occurrences = (last.occurrences.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            ) else (previous + event).takeLast(MAX_EVENTS)
            val bytes = JSONArray(events.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_BYTES)
            val output = atomic.startWrite()
            try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            readEvents() == events
        } catch (_: Exception) { false }
    }

    fun report(): String = synchronized(gate) {
        try {
            val events = readEvents()
            "Historial persistente TFT (${events.size}/$MAX_EVENTS)\n" +
                "Fechas UTC · Sin coordenadas, URLs, consultas, rutas ni identificadores Bluetooth.\n\n" +
                if (events.isEmpty()) "Sin eventos guardados." else events.joinToString("\n\n") { it.reportLine() }
        } catch (_: Exception) { "Historial persistente TFT no disponible: no se pudo leer el archivo." }
    }

    fun clear(): Boolean = synchronized(gate) {
        try {
            atomic.delete()
            !file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()
        } catch (_: Exception) { false }
    }

    private fun readEvents(): List<MapDiagnosticEvent> {
        val input = try { atomic.openRead() } catch (error: java.io.FileNotFoundException) {
            if (!file.exists() && (!directory.exists() || directory.isDirectory)) return emptyList() else throw error
        }
        val bytes = input.use {
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = it.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_BYTES) throw IOException("Diagnostic history too large")
            buffer.copyOf(count)
        }
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        if (array.length() > MAX_EVENTS) throw IOException("Too many diagnostic events")
        return (0 until array.length()).map { MapDiagnosticEvent.fromJson(array.getJSONObject(it)) }
    }

    companion object {
        const val MAX_EVENTS = 100
        const val MAX_BYTES = 128 * 1024
        // Serializes service writes and activity export/clear across separate store instances.
        private val gate = Any()
    }
}
