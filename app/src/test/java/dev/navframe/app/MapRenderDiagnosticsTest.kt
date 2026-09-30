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
        assertEquals("GLYPH/FORBIDDEN/HTTP_403/SDK_CALLBACK", detail.safeText())
        assertEquals(MapRenderFailure.NETWORK, classifyMapRenderFailure("Failed to load style: could not resolve private-host"))
        assertFalse(detail.safeText().contains("private"))
        assertFalse(detail.safeText().contains("secret"))
        assertFalse(detail.safeText().contains("43.361234"))
    }
    @Test fun timeoutHasSeparateStageAndDoesNotPretendThatTheNetworkFailed() {
        val failure = MapRenderException(MapRenderFailure.TIMEOUT, MapRenderStage.STYLE_READY)
        assertEquals("TIMEOUT:STYLE_READY:UNKNOWN/UNKNOWN/SDK_CALLBACK", failure.message)
        assertNotEquals(MapRenderFailure.NETWORK, failure.failure)
    }
}
