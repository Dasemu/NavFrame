package dev.navframe.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.AtomicFile
import dev.navframe.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MapDiagnosticHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = MapDiagnosticHistory(context)
    class PhoneWithoutGpu : MainActivity() { override fun createPhoneMap(savedState: Bundle?): PhoneMapView? = null }

    @Before fun cleanHistory() { assertTrue(store.clear()) }
    @After fun removeHistory() { store.file.deleteRecursively(); java.io.File(store.file.path + ".new").deleteRecursively(); store.clear() }

    private fun event(timestamp: Long = 1_792_000_000_000, kind: MapDiagnosticKind = MapDiagnosticKind.FAILURE) = MapDiagnosticEvent(
        kind = kind, timestampMs = timestamp, source = MapDiagnosticSource.LOCAL,
        network = MapDiagnosticNetwork.DISCONNECTED, stage = MapRenderStage.STYLE_READY,
        failure = if (kind == MapDiagnosticKind.FAILURE) MapRenderFailure.TIMEOUT else null,
        durationMs = 10_001, attempts = 1, retryMs = if (kind == MapDiagnosticKind.FAILURE) 2_000 else 0,
        mode = NavigationMode.MAP_GPS, navigation = NavigationStatus.NAVIGATING,
        connection = ConnectionState.CONNECTED, androidApi = 34,
    )

    @Test fun committedFailuresAndRecoverySurviveNewStoreInstancesWithoutNativeMessages() {
        val raw = "Failed to load glyph https://private.example/fonts?token=secret HTTP status 403 at 43.361234,-5.856789"
        val detail = mapErrorDetail(raw)
        val failure = event().copy(failure = classifyMapRenderFailure(raw), resource = detail.resource, reason = detail.reason, httpStatus = detail.httpStatus,
            sdkEvidence = detail.sdkEvidence, errorClass = "SDK_CALLBACK", screenInteractive = false, lowMemory = true, availableMemoryMb = 42)
        assertTrue(store.record(failure))
        assertTrue(store.record(event(kind = MapDiagnosticKind.RECOVERED)))
        val reopened = MapDiagnosticHistory(context)
        assertTrue(reopened.file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        val report = reopened.report()
        assertTrue(report.contains("(2/100)"))
        assertTrue(report.contains("NETWORK/STYLE_READY"))
        assertTrue(report.contains("GLYPH/FORBIDDEN/HTTP_403"))
        assertTrue(report.contains("RECOVERED"))
        assertTrue(report.contains("LOCAL · red DISCONNECTED"))
        for (text in listOf("secret", "private.example", "43.361234", "-5.856789", "https://")) {
            assertFalse(report.contains(text))
            assertFalse(reopened.file.readText().contains(text))
        }
    }

    @Test fun retentionKeepsNewestHundredEventsInOrderAndExplicitClearPersists() {
        for (index in 0..104) assertTrue(store.record(event(timestamp = index.toLong())))
        val report = MapDiagnosticHistory(context).report()
        assertTrue(report.contains("(100/100)"))
        assertFalse(report.contains("1970-01-01T00:00:00.004Z"))
        assertTrue(report.indexOf("1970-01-01T00:00:00.005Z") < report.indexOf("1970-01-01T00:00:00.104Z"))
        assertTrue(store.file.length() <= MapDiagnosticHistory.MAX_BYTES)
        assertTrue(store.clear())
        assertTrue(MapDiagnosticHistory(context).report().contains("Sin eventos guardados"))
        assertFalse(store.file.exists())
    }

    @Test fun repeatedSaturatedFailuresPreserveOnsetAndRecoveryInsteadOfEvictingIt() {
        for (index in 1..3) assertTrue(store.record(event(timestamp = index.toLong()).copy(attempts = index)))
        for (index in 4..204) assertTrue(store.record(event(timestamp = index.toLong()).copy(attempts = 4)))
        assertTrue(store.record(event(timestamp = 205, kind = MapDiagnosticKind.RECOVERED).copy(attempts = 4)))
        val report = MapDiagnosticHistory(context).report()
        assertTrue(report.contains("(5/100)"))
        assertTrue(report.contains("1970-01-01T00:00:00.001Z"))
        assertTrue(report.contains("Agrupado: 201 fallos"))
        assertTrue(report.contains("primero 1970-01-01T00:00:00.004Z"))
        assertTrue(report.contains("último 1970-01-01T00:00:00.204Z"))
        assertTrue(report.contains("RECOVERED"))
        assertTrue(store.record(event(timestamp = 206)))
        assertTrue(store.report().contains("(6/100)"))
    }

    @Test fun legacyHistoryRemainsReadableAndNewEvidenceIsPersisted() {
        val json = event().toJson()
        for (key in listOf("errorClass", "sdkEvidence", "firstTimestampMs", "occurrences", "screenInteractive", "lowMemory", "availableMemoryMb")) json.remove(key)
        store.file.parentFile!!.mkdirs()
        store.file.writeText(org.json.JSONArray().put(json).toString())
        assertTrue(store.report().contains("TIMEOUT/STYLE_READY"))
        val raw = "Snapshot failed no tile data https://private.example?token=secret"
        val detail = mapErrorDetail(raw)
        assertTrue(store.record(event(timestamp = 1_792_000_000_001).copy(sdkEvidence = detail.sdkEvidence,
            screenInteractive = false, lowMemory = true, availableMemoryMb = 42)))
        val report = MapDiagnosticHistory(context).report()
        assertTrue(report.contains("WORDS_SNAPSHOT_FAILED_NO_TILE_DATA"))
        assertTrue(report.contains("Pantalla APAGADA"))
        assertTrue(report.contains("disponible 42 MiB"))
        assertFalse(store.file.readText().contains("private.example"))
    }

    @Test fun interruptedAtomicReplacementLeavesTheLastCommittedHistoryReadable() {
        assertTrue(store.record(event()))
        val previous = store.report()
        val interrupted = AtomicFile(store.file).startWrite()
        interrupted.write("incomplete private URL https://private.example".toByteArray())
        interrupted.fd.sync()
        interrupted.close() // Simulate process loss before finishWrite; do not commit the replacement.
        assertEquals(previous, MapDiagnosticHistory(context).report())
    }

    @Test fun corruptAndOversizedFilesAreNotExportedAndNextEventRepairsTheHistory() {
        store.file.parentFile!!.mkdirs()
        store.file.writeText("private URL https://private.example?token=secret")
        assertTrue(store.report().contains("no se pudo leer"))
        assertFalse(store.report().contains("secret"))
        assertTrue(store.record(event()))
        assertTrue(store.report().contains("(1/100)"))
        store.file.writeBytes(ByteArray(MapDiagnosticHistory.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertTrue(store.report().contains("no se pudo leer"))
        assertTrue(store.record(event()))
        assertTrue(store.report().contains("(1/100)"))
    }

    @Test fun unavailableStorageReturnsFailureWithoutExportingExceptions() {
        store.file.writeText("not a directory")
        val blocked = MapDiagnosticHistory(store.file)
        assertFalse(blocked.record(event()))
        assertTrue(blocked.report().contains("no se pudo leer"))
        assertFalse(blocked.report().contains(context.noBackupFilesDir.path))
    }

    @Test fun servicePersistsFailureThenRecoveryBeforeRecreation() {
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        var renders = 0
        service.mapRenderOverride = { _, _ ->
            if (renders++ == 0) throw MapRenderException(MapRenderFailure.GPU, MapRenderStage.STYLE_READY, MapErrorDetail(reason = MapFailureReason.EGL))
            TftFrame(encodedBytes = byteArrayOf(1), timestampMs = 0)
        }
        try {
            service.startDemo(true)
            await { store.report().contains("RECOVERED") }
            val report = store.report()
            assertTrue(report.contains("FAILURE"))
            assertTrue(report.contains("GPU/STYLE_READY"))
            assertTrue(report.contains("UNKNOWN/EGL"))
            assertTrue(report.contains("RECOVERED"))
            assertTrue(report.indexOf("FAILURE") < report.indexOf("RECOVERED"))
        } finally { controller.destroy() }
        val recreated = Robolectric.buildService(TftService::class.java).create()
        try {
            assertTrue(MapDiagnosticHistory(recreated.get()).report().contains("GPU/STYLE_READY"))
        } finally { recreated.destroy() }
    }

    @Test fun serviceStillPublishesErrorFrameAndReportsWhenHistoryCannotBeWritten() {
        assertTrue(store.record(event()))
        val blockedReplacement = java.io.File(store.file.path + ".new")
        assertTrue(blockedReplacement.mkdir())
        java.io.File(blockedReplacement, "blocker").writeText("not removable as a file")
        val controller = Robolectric.buildService(TftService::class.java).create()
        val service = controller.get()
        service.mapRenderOverride = { _, _ -> throw MapRenderException(MapRenderFailure.GPU, MapRenderStage.STYLE_READY) }
        try {
            service.startDemo(true)
            await { service.lastFrame.value != null }
            assertTrue(service.diagnosticReport().contains("TFT_MAP_HISTORY_WRITE_FAILED"))
            assertEquals(NavigationMode.MAP_DEMO, service.navigationMode.value)
            assertTrue(store.report().contains("(1/100)"))
        } finally { controller.destroy(); blockedReplacement.deleteRecursively() }
    }

    @Test fun diagnosisWithoutBoundServiceSharesSavedHistoryAndClearRequiresConfirmation() {
        assertTrue(store.record(event()))
        val controller = Robolectric.buildActivity(PhoneWithoutGpu::class.java).create()
        val activity = controller.get()
        fun open() {
            MainActivity::class.java.getDeclaredMethod("showMapDiagnostic").apply { isAccessible = true }.invoke(activity)
            await { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
        }
        try {
            open()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(dialog).message.toString().contains("TIMEOUT/STYLE_READY"))
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, share.action)
            assertTrue(share.getStringExtra(Intent.EXTRA_TEXT)!!.contains("TIMEOUT/STYLE_READY"))
            open()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(store.report().contains("(1/100)"))
            open()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { store.report().contains("Sin eventos guardados") }
        } finally { controller.destroy() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        do {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("Timed out waiting for diagnostic IO/UI completion")
    }
}
