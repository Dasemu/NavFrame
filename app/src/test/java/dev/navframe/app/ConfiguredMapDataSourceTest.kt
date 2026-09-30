package dev.navframe.app

import org.junit.Assert.*
import org.junit.Test

class ConfiguredMapDataSourceTest {
    @Test fun defaultUsesBundledVectorStyleAndRequiredProviderCredits() {
        val source = ConfiguredMapDataSource().tileSource()
        assertEquals("asset://map/tft-style.json", source.uri)
        assertTrue(source.attribution.contains("OpenFreeMap"))
        assertTrue(source.attribution.contains("OpenStreetMap"))
    }
    @Test fun remoteStyleMustUseHttpsWithoutEmbeddedUserCredentials() {
        for (url in listOf("http://example.com/map.json", "https://user:secret@example.com/style.json", "file:///map.json", "https://tile.openstreetmap.org/style.json")) {
            assertThrows(IllegalArgumentException::class.java) { ConfiguredMapDataSource(url) }
        }
    }
    @Test fun privateQueryTokenRemainsButCustomStyleCannotImpersonateDefaultProvider() {
        val uri = "https://maps.example.com/style.json?key=private-user-key"
        val source = ConfiguredMapDataSource(uri, "© Example · © OpenStreetMap contributors").tileSource()
        assertEquals(uri, source.uri)
        assertEquals(ConfiguredMapDataSource.BASE_ATTRIBUTION, source.attribution)
        assertFalse(source.attribution.contains("OpenFreeMap"))
        assertFalse(source.attribution.contains("Example"))
        assertFalse(source.attribution.contains("OpenStreetMap"))
    }
    @Test fun userOverrideCannotEraseMandatoryProviderCredits() {
        assertEquals(ConfiguredMapDataSource.DEFAULT_ATTRIBUTION, ConfiguredMapDataSource("", " ").tileSource().attribution)
        assertEquals(ConfiguredMapDataSource.BASE_ATTRIBUTION, ConfiguredMapDataSource("https://example.com/style.json", "fake").tileSource().attribution)
    }
}
