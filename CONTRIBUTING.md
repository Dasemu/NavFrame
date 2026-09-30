# Contributing

Contributions are welcome. NavFrame is experimental software for motorcycle navigation displays, so clear boundaries between simulation, online services, offline data, and physical device results matter.

## Before making a change

- Open an issue or discussion for a substantial feature or protocol change.
- Keep user-visible claims aligned with verified app behavior. Label simulated route/location data as demo data.
- Do not add copied OsmAnd code, assets, or databases. Do not use public map, geocoder, or routing services for bulk harvesting or unapproved load tests.
- Preserve OSM attribution and source dates/hashes for derived data. Check third-party licenses before adding code, fonts, images, or data.
- Keep endpoints configurable where appropriate. Never commit API keys, signing keys, personal coordinates, Bluetooth identifiers, or local machine configuration.

## Build and checks

Use JDK 17 and an Android SDK with API 36. The Gradle wrapper pins the Gradle version.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug :navilite:testDebugUnitTest :core:test
python3 -m unittest discover -s tools/offline -p 'test_*.py'
```

Run checks relevant to the change and include the results in the pull request. Tests do not exercise Android MapLibre JNI/OpenGL, Bluetooth hardware, a Yamaha TFT, motorcycle route suitability, or audible TTS behavior. Describe those as unverified unless tested on physical hardware, with model and software versions and without sharing precise location or device identifiers.

## Pull requests

Explain the user-visible behavior and the problem it addresses. Include relevant tests and any validation limits. For data or package changes, include the source URL, source timestamp, checksums, license/attribution, and reproducible build inputs. Do not put generated regional datasets or APKs in Git history; use the documented release workflow.

The worldwide package workflow is documented in [GLOBAL_OFFLINE.md](docs/GLOBAL_OFFLINE.md). It publishes public release assets, so changes to that workflow need particular care around permissions, immutable package versions, and catalog integrity.
