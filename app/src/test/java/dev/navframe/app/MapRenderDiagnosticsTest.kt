package dev.navframe.app

import org.junit.Assert.*
import org.junit.Test

class MapRenderDiagnosticsTest {
    @Test fun sdkFailuresAreClassifiedWithoutRetainingSensitiveNativeMessages() {
        val inputs = listOf(
            "EGL framebuffer failed at 43.361234,-5.856789 https://private.example/map?token=secret" to MapRenderFailure.GPU,
            "DNS resolve failed for https://private.example/map?token=secret" to MapRenderFailure.NETWORK,
            "Failed to parse style JSON at coordinate 43.361234,-5.856789" to MapRenderFailure.STYLE,
            "Bitmap dimensions invalid" to MapRenderFailure.BITMAP,
            "Unknown native error private-address" to MapRenderFailure.SDK,
        )
        inputs.forEach { (raw, expected) ->
            val failure = MapRenderException(classifyMapRenderFailure(raw), MapRenderStage.STYLE_LOADING)
            assertEquals(expected, failure.failure)
            assertEquals("${expected.name}:STYLE_LOADING:UNKNOWN/UNKNOWN/SDK_CALLBACK", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("secret"))
            assertFalse(failure.toString().contains("43.361234"))
        }
    }
    @Test fun combinedNativeResourceErrorPreservesUsefulReasonButDiscardsThePrivateUrl() {
        val raw = "Failed to load style glyph https://private.example/fonts.pbf?token=secret: HTTP status 403; location 43.361234,-5.856789"
        assertEquals(MapRenderFailure.NETWORK, classifyMapRenderFailure(raw))
        val detail = mapErrorDetail(raw)
        assertTrue(detail.safeText().startsWith("GLYPH/FORBIDDEN/HTTP_403/SDK_CALLBACK/WORDS_"))
        assertEquals(MapRenderFailure.NETWORK, classifyMapRenderFailure("Failed to load style: could not resolve private-host"))
        assertFalse(detail.safeText().contains("private"))
        assertFalse(detail.safeText().contains("secret"))
        assertFalse(detail.safeText().contains("43.361234"))
    }
    @Test fun missingBundledAssetIsReportedAsNotFound() {
        // MapLibre's AssetManagerFileSource message for an absent glyph range or other asset.
        assertEquals("UNKNOWN/NOT_FOUND/SDK_CALLBACK/WORDS_COULD_NOT_READ_ASSET", mapErrorDetail("Could not read asset").safeText())
    }
    @Test fun timeoutHasSeparateStageAndDoesNotPretendThatTheNetworkFailed() {
        val failure = MapRenderException(MapRenderFailure.TIMEOUT, MapRenderStage.STYLE_READY)
        assertEquals("TIMEOUT:STYLE_READY:UNKNOWN/UNKNOWN/SDK_CALLBACK", failure.message)
        assertNotEquals(MapRenderFailure.NETWORK, failure.failure)
    }
    @Test fun unknownCallbackRetainsOnlyDiagnosticVocabulary() {
        val raw = "Snapshot failed: no tile data private-address 43.361234,-5.856789 https://private.example/style?token=secret /data/user/0/private-map.pmtiles"
        val detail = mapErrorDetail(raw)
        assertEquals("SNAPSHOT_FAILED_NO_TILE_DATA", detail.sdkEvidence)
        assertEquals("EMPTY", mapErrorDetail(null).sdkEvidence)
        assertEquals(MapFailureReason.EMPTY, mapErrorDetail("").reason)
        assertEquals("UNCLASSIFIED", mapErrorDetail("private-secret-name").sdkEvidence)
        assertEquals(MapFailureReason.IO, mapErrorDetail("Cannot read PMTiles archive: permission denied").reason)
        assertEquals(MapResourceKind.PMTILES, mapErrorDetail("Cannot read PMTiles archive: permission denied").resource)
        assertTrue(safeSdkEvidence("failed ".repeat(1000)).length <= 240)
        assertEquals("FAILED", safeSdkEvidence("failed \"private map tile\" https://host/map/tile/43 /private/map/file"))
    }

}
