package dev.navframe.app

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMapManagerTest {
    private lateinit var context: Context
    @Before fun before() { context = RuntimeEnvironment.getApplication(); File(context.filesDir, "offline").deleteRecursively() }
    @After fun after() { File(context.filesDir, "offline").deleteRecursively() }

    /** Original header fixture. Tests validation/store, not native tile rendering. */
    private fun archive(bounds: OfflineBounds = OfflineBounds.ASTURIAS): ByteArray {
        val metadata = """{"name":"NavFrame Offline","version":"1.0.0","vector_layers":[{"id":"roads"},{"id":"place"}]}""".toByteArray()
        val bytes = ByteArray(129 + metadata.size)
        "PMTiles".toByteArray().copyInto(bytes)
        bytes[7] = 3
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(8, 127); buffer.putLong(16, 1)
        buffer.putLong(24, 128); buffer.putLong(32, metadata.size.toLong())
        buffer.putLong(40, (128 + metadata.size).toLong()); buffer.putLong(48, 0)
        buffer.putLong(56, (128 + metadata.size).toLong()); buffer.putLong(64, 1)
        bytes[97] = 1; bytes[98] = 1; bytes[99] = 1; bytes[100] = 6; bytes[101] = 14
        buffer.putInt(102, (bounds.west * 1e7).toInt()); buffer.putInt(106, (bounds.south * 1e7).toInt())
        buffer.putInt(110, (bounds.east * 1e7).toInt()); buffer.putInt(114, (bounds.north * 1e7).toInt())
        metadata.copyInto(bytes, 128)
        return bytes
    }

    @Test fun importingCommitsPrivateStyleWithOnlyLocalResourcesAndPersists() = runBlocking {
        val manager = OfflineMapManager(context)
        val bytes = archive()
        val pack = manager.importArchive(ByteArrayInputStream(bytes), "Region test", bytes.size.toLong())
        assertEquals(OfflinePackState.READY, pack.state)
        assertEquals(OfflineBounds.ASTURIAS, pack.bounds)
        assertTrue(pack.source.uri.startsWith("file:///"))
        assertTrue(manager.approvedSource(pack.source.uri))
        val style = JSONObject(File(java.net.URI(pack.source.uri)).readText())
        val url = style.getJSONObject("sources").getJSONObject("offline").getString("url")
        assertTrue(url.startsWith("pmtiles://file:///"))
        assertTrue(style.getString("glyphs").startsWith("asset://offline/fonts/"))
        assertFalse(style.toString().contains("https://"))
        val reloaded = OfflineMapManager(context)
        reloaded.refresh()
        assertEquals(pack, reloaded.packs.value.single())
        manager.delete(pack.id)
        assertTrue(manager.packs.value.isEmpty())
        assertFalse(manager.approvedSource(pack.source.uri))
    }

    @Test fun refreshRemovesUncommittedCopyAfterProcessDeathWithoutRemovingReadyPack() = runBlocking {
        val manager = OfflineMapManager(context)
        val ready = manager.importArchive(ByteArrayInputStream(archive()), "Ready")
        val interrupted = File(context.filesDir, "offline/00000000-0000-0000-0000-000000000000")
        interrupted.mkdirs()
        File(interrupted, "map.pmtiles").writeBytes(ByteArray(200))
        manager.refresh()
        assertFalse(interrupted.exists())
        assertEquals(ready, manager.packs.value.single())
    }

    @Test fun packagedAsturiasIsRealBoundedVectorArchiveWithBundledGlyphs() = runBlocking {
        val manager = OfflineMapManager(context)
        val pack = manager.installBundledAsturias()
        assertEquals(34_617_483L, pack.bytes)
        assertEquals(6, pack.minZoom)
        assertEquals(14, pack.maxZoom)
        assertTrue(pack.bounds.contains(43.36, -5.85))
        val glyph = context.assets.open("offline/fonts/Noto Sans Regular/0-255.pbf").use { it.readBytes() }
        assertTrue(glyph.size > 1_000)
    }

    @Test fun otherBoundedRegionsAreImportedFromHeaderRatherThanAsturiasPlaceholder() = runBlocking {
        val bounds = OfflineBounds(42.0, -3.0, 42.5, -2.5)
        val pack = OfflineMapManager(context).importArchive(ByteArrayInputStream(archive(bounds)), "Other region")
        assertEquals(bounds, pack.bounds)
    }

    @Test fun malformedOffsetsSchemaAndZoomAreRejectedAndPartialsRemoved() = runBlocking {
        val manager = OfflineMapManager(context)
        val badFiles = listOf(archive().apply { this[7] = 2 }, archive().apply { this[101] = 18 },
            archive().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(24, Long.MAX_VALUE) },
            archive().apply { this[99] = 2 }, ByteArray(12))
        for (bytes in badFiles) {
            try { manager.importArchive(ByteArrayInputStream(bytes), "Invalid"); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertTrue(manager.packs.value.isEmpty())
            assertTrue(File(context.filesDir, "offline").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellationClosesInputAndDeletesPartials() = runBlocking {
        val manager = OfflineMapManager(context)
        val started = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()
        val input = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                started.complete(Unit)
                Thread.sleep(5)
                bytes.fill(0, offset, offset + count)
                return count
            }
            override fun close() { closed.set(true) }
        }
        val job = launch { manager.importArchive(input, "Cancel") }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed.get())
        assertTrue(manager.packs.value.isEmpty())
        assertTrue(File(context.filesDir, "offline").listFiles().orEmpty().isEmpty())
    }

    @Test fun quotaAndPathValidationRejectBeforeModifyingAnExistingRegion() = runBlocking {
        val manager = OfflineMapManager(context)
        repeat(3) { manager.importArchive(ByteArrayInputStream(archive()), "Region $it") }
        try { manager.importArchive(ByteArrayInputStream(archive()), "Fourth"); fail("Expected quota rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, manager.packs.value.size)
        assertFalse(manager.approvedSource(File(context.filesDir, "elsewhere/style.json").toURI().toString()))
        try { manager.delete("../"); fail("Expected path rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(3, manager.packs.value.size)
    }

    @Test fun incompleteCopyAndDeclaredOversizeAreRejectedAndInputClosed() = runBlocking {
        val manager = OfflineMapManager(context)
        try { manager.importArchive(ByteArrayInputStream(archive()), "Short", 100_000); fail() }
        catch (_: IllegalArgumentException) { }
        val closed = AtomicBoolean()
        val input = object : ByteArrayInputStream(archive()) { override fun close() { closed.set(true); super.close() } }
        try { manager.importArchive(input, "Huge", OfflineMapManager.MAX_ARCHIVE_BYTES + 1); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(closed.get())
        assertTrue(manager.packs.value.isEmpty())
    }
}
