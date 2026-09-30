# Navigation voice languages

Settings offers Spanish (the existing default), English, German, French, Italian,
and a system-language option. Regional language variants map to the supported
Valhalla locale. An unsupported system language uses English consistently for
routing instructions, speech templates and Android TTS.

The preference applies to newly calculated routes. An active route retains the
language of its server-provided maneuver text until it is replaced, so existing
instructions are not spoken with a newly selected language's voice. Status
announcements and optional speed-camera speech use that route's language too.

Android's configured TTS engine must provide the selected language. NavFrame
prefers an installed voice that works offline and reports whether the selected
voice needs Internet. If language data is missing or unsupported, speech is
unavailable; install voice data using the Android voice settings button. Map
rendering and navigation remain available. Valhalla routing itself still needs
Internet.

Tests cover preserved Spanish defaults, locale mapping, English checkpoints,
localized alerts, selected request language, and system-language fallback.

API references: [Valhalla locale files](https://github.com/valhalla/valhalla/tree/master/locales),
[Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech),
and [Android Voice](https://developer.android.com/reference/android/speech/tts/Voice).
