package dev.navframe.app

import android.content.Context
import dev.navframe.core.GuidanceLanguage
import java.util.Locale

internal object NavigationVoicePreferences {
    const val SYSTEM = "system"
    fun selection(context: Context): String = context.getSharedPreferences("navigation_voice", Context.MODE_PRIVATE).getString("language", "es-ES") ?: "es-ES"
    fun language(context: Context): GuidanceLanguage = resolve(selection(context), Locale.getDefault().toLanguageTag())
    fun resolve(selection: String, systemTag: String): GuidanceLanguage = GuidanceLanguage.resolve(if (selection == SYSTEM) systemTag else selection)
    fun save(context: Context, selection: String) {
        require(selection == SYSTEM || GuidanceLanguage.entries.any { it.tag == selection })
        context.getSharedPreferences("navigation_voice", Context.MODE_PRIVATE).edit().putString("language", selection).apply()
    }
}
