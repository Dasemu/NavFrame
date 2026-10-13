# NavFrame

NavFrame is an experimental Android navigation app that renders its own 480×240 images for compatible Yamaha motorcycle TFTs through NaviLite over Bluetooth. It does not capture StreetCross, Google Maps, Waze, or another app's screen.

## Current status

The owner has confirmed static and synthetic NavFrame frames on a Xiaomi 11 Lite 5G NE running Android 14 and a 2026 Yamaha MT-07, and the bundled Asturias map working offline on the phone. On 2026-10-12, the owner reported that the latest version displayed the map on the TFT for about 20 minutes, then showed `Mapa no disponible` in La Corredoria (Oviedo) for the rest of the trip while the phone map continued working. An offline map was installed. The exact installed build, diagnostic code and background state were not recorded; the cause is under investigation. This establishes a successful map display followed by a persistent failure, not sustained TFT reliability.

The current checkout is version 0.13.4. The 0.13 series adds voice/routing language selection for Spanish, English, German, French, and Italian, plus a system-language option; unsupported system locales resolve to English. Speech still depends on the Android TextToSpeech engine and its installed voice data. The app includes offline map and place data for Asturias, plus installable regional packages. Worldwide regions can be discovered and built on demand through the [regional package workflow](docs/GLOBAL_OFFLINE.md); this is not complete worldwide coverage. Installed packages currently provide map and local place/camera data. Offline route calculation, global camera completeness, and complete worldwide font coverage are not provided.

Route calculation and address search use configurable online services. Spoken output uses the selected language when Android provides voice data; voice availability and offline behavior depend on the device's TextToSpeech engine. Physical validation of route guidance, voice playback, camera alerts, sustained TFT map rendering, and a 30-minute screen-off session remains pending unless a newer test report says otherwise.

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

## Location and compass

Version 0.13.3 uses available native Android location sources and recent cached fixes to avoid waiting exclusively for a new satellite fix. The map can recenter on a recent approximate position with accuracy up to 100 metres; starting route guidance still requires 25 metres. A compass heading orients the map at low speed, while usable movement course takes priority when riding. See [Location and compass](docs/LOCATION_AND_COMPASS.md) for behavior and device-validation limits.

## TFT diagnostic history

Version 0.13.2 automatically saves the latest 100 TFT map failures and recoveries on the phone. After a reboot or empty battery, open **Information and licenses → TFT map diagnosis → Share** to export the saved history. It excludes coordinates, URLs, route/search text, and Bluetooth identifiers. See [Persistent TFT diagnostics](docs/TFT_DIAGNOSTICS.md) for retention and limitations.

## Automated builds

GitHub Actions uploads a debug APK artifact for pushes to `development`. Pushes to `main` publish a signed production APK release after build, tests, lint and signature checks pass. Production signing requires the repository secrets described in [Android builds and releases](docs/ANDROID_RELEASES.md). Pull requests run checks without publishing releases.

## Imported routes

Choose **☰ → Cargar ruta GPX** to load a standard GPX route or track and start following it once precise GPS is available. Imported traces retain their geometry without online recalculation or invented turn announcements. See [GPX import](docs/IMPORTED_ROUTES.md).

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

No project-wide license is granted for original NavFrame code or documentation. Public repository visibility alone does not grant permission to copy, modify or redistribute that material. The Pillion-derived `navilite` module retains **PolyForm Noncommercial 1.0.0** and its required notices; its commercial-use restrictions must be respected.

Map data, fonts, dependencies and other third-party material retain their respective terms. OpenStreetMap-derived offline data is distributed under ODbL 1.0 with attribution. Review [Licensing](docs/LICENSING.md) and the included notices before copying, redistributing or using project material. Before redistributing a release, verify the terms for original material and every included component.
