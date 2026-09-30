package dev.navframe.core

import org.junit.Assert.*
import org.junit.Test

class GuidanceLanguageTest {
    @Test fun regionalAndUnsupportedLocalesUseConsistentSupportedLanguage() {
        assertEquals(GuidanceLanguage.SPANISH, GuidanceLanguage.resolve("es-MX"))
        assertEquals(GuidanceLanguage.ENGLISH, GuidanceLanguage.resolve("en-GB"))
        assertEquals(GuidanceLanguage.GERMAN, GuidanceLanguage.resolve("de_AT"))
        assertEquals(GuidanceLanguage.ENGLISH, GuidanceLanguage.resolve("ja-JP"))
    }
    @Test fun requestUsesSelectedLocaleAndDefaultsRemainSpanish() {
        val origin = GeoPoint(43.3, -5.8); val destination = GeoPoint(43.4, -5.9)
        assertTrue(buildValhallaRequest(origin, destination).contains("es-ES"))
        assertTrue(buildValhallaRequest(origin, destination, language = "fr-CA").contains("fr-FR"))
        assertTrue(buildValhallaRequest(origin, destination, language = "unsupported").contains("en-US"))
    }
    @Test fun englishCheckpointsStatusesAndCameraAlertsDoNotUseSpanish() {
        val maneuver = Maneuver("Turn left", 100, 15, 1, 2)
        val route = RouteResult(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)), listOf(maneuver), 1000, 100, guidanceLanguage = "en-US")
        val state = NavigationState(route = route, nextManeuver = maneuver, distanceToNextManeuverMeters = 250, navigationStatus = NavigationStatus.NAVIGATING)
        assertEquals("En 250 metros, Turn left", VoiceGuidancePolicy().announcement(state))
        val policy = VoiceGuidancePolicy(GuidanceLanguage.ENGLISH)
        assertEquals("In 250 meters, Turn left", policy.announcement(state))
        assertNull(policy.announcement(state))
        assertEquals("Now, Turn left", policy.announcement(state.copy(distanceToNextManeuverMeters = 20)))
        assertEquals("You have arrived at your destination.", policy.announcement(state.copy(navigationStatus = NavigationStatus.ARRIVED)))
        assertTrue(GuidanceLanguage.ENGLISH.camera(200).contains("200 meters"))
        assertTrue(GuidanceLanguage.SPANISH.camera(200).contains("200 metros"))
    }
    @Test fun everySelectableLanguageProvidesLocalizedSpeech() {
        GuidanceLanguage.entries.forEach { language ->
            assertTrue(language.maneuver("instruction", 250, false).contains("250"))
            assertTrue(language.camera(200).contains("200"))
            assertTrue(language.status(NavigationStatus.GPS_LOST).isNotBlank())
        }
    }
}
