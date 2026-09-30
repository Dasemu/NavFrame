package dev.navframe.core

import java.util.Locale

/** App-owned speech templates; Valhalla provides maneuver instructions in the same locale. */
enum class GuidanceLanguage(val tag: String, val nativeName: String) {
    SPANISH("es-ES", "Español"), ENGLISH("en-US", "English"),
    GERMAN("de-DE", "Deutsch"), FRENCH("fr-FR", "Français"), ITALIAN("it-IT", "Italiano");

    fun status(status: NavigationStatus): String = when (this) {
        SPANISH -> when (status) {
            NavigationStatus.ARRIVED -> "Has llegado a tu destino."
            NavigationStatus.REROUTING -> "Recalculando ruta."
            NavigationStatus.OFF_ROUTE -> "Te has desviado de la ruta."
            else -> "Señal GPS insuficiente. Espera nuevas indicaciones."
        }
        GERMAN -> when (status) {
            NavigationStatus.ARRIVED -> "Du hast dein Ziel erreicht."
            NavigationStatus.REROUTING -> "Route wird neu berechnet."
            NavigationStatus.OFF_ROUTE -> "Du bist von der Route abgewichen."
            else -> "GPS-Signal unzureichend. Warte auf neue Anweisungen."
        }
        FRENCH -> when (status) {
            NavigationStatus.ARRIVED -> "Vous êtes arrivé à destination."
            NavigationStatus.REROUTING -> "Recalcul de l'itinéraire."
            NavigationStatus.OFF_ROUTE -> "Vous avez quitté l'itinéraire."
            else -> "Signal GPS insuffisant. Attendez de nouvelles instructions."
        }
        ITALIAN -> when (status) {
            NavigationStatus.ARRIVED -> "Hai raggiunto la destinazione."
            NavigationStatus.REROUTING -> "Ricalcolo del percorso."
            NavigationStatus.OFF_ROUTE -> "Hai lasciato il percorso."
            else -> "Segnale GPS insufficiente. Attendi nuove indicazioni."
        }
        ENGLISH -> when (status) {
            NavigationStatus.ARRIVED -> "You have arrived at your destination."
            NavigationStatus.REROUTING -> "Recalculating route."
            NavigationStatus.OFF_ROUTE -> "You have left the route."
            else -> "GPS signal is insufficient. Wait for new directions."
        }
    }
    fun maneuver(instruction: String, distance: Int, now: Boolean): String = when (this) {
        SPANISH -> if (now) "Ahora, $instruction" else "En $distance metros, $instruction"
        ENGLISH -> if (now) "Now, $instruction" else "In $distance meters, $instruction"
        GERMAN -> if (now) "Jetzt, $instruction" else "In $distance Metern, $instruction"
        FRENCH -> if (now) "Maintenant, $instruction" else "Dans $distance mètres, $instruction"
        ITALIAN -> if (now) "Ora, $instruction" else "Tra $distance metri, $instruction"
    }
    fun camera(distance: Int): String = when (this) {
        SPANISH -> "Posible radar fijo cerca, a unos $distance metros."
        ENGLISH -> "Possible fixed speed camera nearby, about $distance meters away."
        GERMAN -> "Möglicher stationärer Blitzer in etwa $distance Metern."
        FRENCH -> "Radar fixe possible à environ $distance mètres."
        ITALIAN -> "Possibile autovelox fisso a circa $distance metri."
    }
    companion object {
        /** Unsupported system locales consistently use English for TTS, templates and routing. */
        fun resolve(tag: String): GuidanceLanguage = entries.firstOrNull { Locale.forLanguageTag(it.tag).language == Locale.forLanguageTag(tag.replace('_', '-')).language } ?: ENGLISH
    }
}
