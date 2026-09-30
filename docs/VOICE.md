# Voice directions

NavFrame supports spoken guidance templates and routing-language requests for Spanish, English, German, French, and Italian. Settings also offers the Android system language; unsupported system locales resolve to English. The selected route language controls maneuver text and voice templates. Route preview and demo mode are silent. Speech is owned by the navigation foreground service, so it does not depend on the activity remaining open. Android power-management policy may still stop or restrict background work.

In Settings, the voice toggle enables or mutes announcements, and the language selector chooses a route/voice language or the system language. **Configure Android voice** opens system TextToSpeech settings so a user can install or select voice data. NavFrame prefers an installed voice for the selected language that does not require a network connection when the Android engine exposes one. It does not download voice data automatically. Template/routing-language support does not guarantee that a matching Android voice is installed or that it works offline; availability depends on the engine and installed data, and a device may lack the locale or require a connection.

Each maneuver can be announced at approximately 300 m, 100 m, and 30 m. Starting near a turn announces only the current threshold. GPS jitter does not replay earlier thresholds. Arrival, off-route state, reroute, and inadequate GPS are announced once per route state. Muting consumes passed checkpoints, so enabling voice later does not replay old directions.

Speech requests Android's navigation-guidance audio usage and transient audio focus, allowing other audio to duck. The output follows Android's selected audio route, including a compatible Bluetooth intercom; NavFrame does not force a Yamaha TFT audio route or Bluetooth SCO connection. Camera alerts have a separate setting, respect mute, and do not interrupt an active maneuver announcement.

`VoiceGuidancePolicyTest` covers thresholds, GPS changes, route replacement, previews, and one-time events. These automated checks do not prove that sound is audible through a particular phone, helmet, intercom, or motorcycle. Physical validation of voice playback, audio mixing, intercom routing, and screen-off behavior remains pending.

References: [Android TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech) and [audio focus](https://developer.android.com/media/optimize/audio-focus).
