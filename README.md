# NavFrame

NavFrame is an experimental Android navigation app that renders its own 480×240 images for compatible Yamaha motorcycle TFTs through NaviLite over Bluetooth. It does not capture StreetCross, Google Maps, Waze, or another app's screen.

## Current status

The owner has confirmed static and synthetic NavFrame frames on a Xiaomi 11 Lite 5G NE running Android 14 and a 2026 Yamaha MT-07. The owner has also confirmed that the bundled Asturias map works offline on the phone. These results do not validate the current MapLibre map renderer on the motorcycle TFT: the last reported TFT map attempt showed `MAPA NO DISPONIBLE`, and no later physical map-render result is recorded.

Version 0.13.0 adds voice/routing language selection for Spanish, English, German, French, and Italian, plus a system-language option; unsupported system locales resolve to English. Speech still depends on the Android TextToSpeech engine and its installed voice data. The app includes offline map and place data for Asturias, plus installable regional packages. Worldwide regions can be discovered and built on demand through the [regional package workflow](docs/GLOBAL_OFFLINE.md); this is not complete worldwide coverage. Installed packages currently provide map and local place/camera data. Offline route calculation, global camera completeness, and complete worldwide font coverage are not provided.

Route calculation and address search use configurable online services. Spoken output uses the selected language when Android provides voice data; voice availability and offline behavior depend on the device's TextToSpeech engine. Physical validation of route guidance, voice playback, camera alerts, TFT map rendering, and a 30-minute screen-off session remains pending unless a newer test report says otherwise.

## Build

Requirements: JDK 17, Android SDK with API 36, and the Android SDK components requested by Gradle. Gradle is provided by the wrapper.

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To run the automated checks:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :navilite:testDebugUnitTest :core:test
python3 -m unittest discover -s tools/offline -p 'test_*.py'
```

Set up `local.properties` for your own Android SDK when needed. It is excluded from Git. See [Contributing](CONTRIBUTING.md) for the development and review process.

## Documentation

- [Documentation index](docs/README.md)
- [Architecture and data flow](docs/ARCHITECTURE.md)
- [Offline maps and regional packages](docs/OFFLINE.md)
- [Worldwide region build and publication](docs/GLOBAL_OFFLINE.md)
- [Routing and geocoding service limits](docs/ROUTING.md) and [search policy](docs/GEOCODING.md)
- [Voice directions](docs/VOICE.md) and [fixed camera advisories](docs/RADARS.md)
- [Licenses and attribution](docs/LICENSING.md)
- [Testing and physical validation record](docs/TESTING.md)
- [English summary of original product brief](docs/PRODUCT_BRIEF_EN.md)
- [Security reporting](SECURITY.md)

## License notes

There is no single permissive license covering every part of this repository. In particular, NaviLite contains code adapted from Pillion under PolyForm Noncommercial 1.0.0, which restricts commercial use. Other code, datasets, fonts, and dependencies have their own terms and notices. Review [Licensing](docs/LICENSING.md) and the included notices before copying, redistributing, or using any part of this project. OpenStreetMap-derived offline data is distributed under ODbL 1.0 with attribution.
