package dev.navframe.app

import dev.navframe.core.GuidanceLanguage
import org.junit.Assert.*
import org.junit.Test

class NavigationVoicePreferencesTest {
    @Test fun explicitLanguageOverridesSystemAndUnsupportedSystemFallsBackToEnglish() {
        assertEquals(GuidanceLanguage.SPANISH, NavigationVoicePreferences.resolve("es-ES", "en-US"))
        assertEquals(GuidanceLanguage.FRENCH, NavigationVoicePreferences.resolve("system", "fr-CA"))
        assertEquals(GuidanceLanguage.ENGLISH, NavigationVoicePreferences.resolve("system", "ja-JP"))
    }
}
