# MapLibre Native: direct TFT rendering

Research dated 2026-09-29 for phase 4. Goal: render real cartography and GPS position into an original 480×240 JPEG without an Activity-bound MapView or screen capture. This phase does not calculate routes or maneuvers.

## Selected dependency and compatibility

Pinned recommendation: `org.maplibre.gl:android-sdk-opengl:13.5.2`, published on Maven Central. Its [POM](https://repo.maven.apache.org/maven2/org/maplibre/gl/android-sdk-opengl/13.5.2/android-sdk-opengl-13.5.2.pom) and sources at tag `android-v13.5.2` were checked. The POM declares Kotlin stdlib 2.2.10, compatible with the project's 2.2.21, and coroutines 1.10.2; align dependencies to avoid mixing runtime expectations in tests.

Version 13.6.1 also exists, but 13.5.2 received later texture/snapshotter synchronization and Adreno GPU fixes. Pin a checked version without a dynamic range or prerelease. [Release 13.5.2](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.5.2), [release 13.6.1](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.6.1).

The current `android-sdk` artifact uses Vulkan; `android-sdk-opengl` explicitly selects OpenGL ES. This avoids initially packaging two backends. It does not guarantee compatibility with every GPU: test on the Xiaomi and an emulator. See [official rendering engines](https://maplibre.org/maplibre-native/android/examples/data/rendering-engine/).

## Offscreen API and resource ownership

`MapSnapshotter` accepts applicationContext, dimensions, style, and camera without requiring a MapView. Its renderer is **native and GPU-backed** and returns a `Bitmap`; do not describe the map itself as CPU-only rendering. The Canvas overlay and JPEG are additional NavFrame-owned steps.

API checked in [MapSnapshotter.kt at the selected tag](https://github.com/maplibre/maplibre-native/blob/android-v13.5.2/platform/android/MapLibreAndroid/src/main/java/org/maplibre/android/snapshotter/MapSnapshotter.kt):

```kotlin
MapLibre.getInstance(applicationContext)
val options = MapSnapshotter.Options(480, 240)
    .withPixelRatio(1f)
    .withStyleBuilder(Style.Builder().fromUri(styleUrl))
    .withCameraPosition(CameraPosition.Builder()
        .target(LatLng(latitude, longitude))
        .zoom(zoom).bearing(bearingDegrees).tilt(0.0).build())
val snapshotter = MapSnapshotter(applicationContext, options)
```

Create and operate the snapshotter on Main; the class is annotated `@UiThread`. `start(readyCallback, errorHandler)` is asynchronous. Reuse `setCameraPosition` between successful captures, with only one capture pending. New fixes replace pending state; do not queue snapshots. On stop/cancel/timeout, call `cancel()` on Main and discard the instance. There is no public `close()`/`destroy()`; cancellation and dropping references are the exposed mechanism. Measure memory during long sessions.

Important detail in the pinned code: `onSnapshotReady` calls the callback and then `reset()`. Resuming a continuation immediately inside the callback and calling `start()` again can produce `Snapshotter was already started`. Posting the continuation with `Handler(Main).post` avoids reentry. Apply the same deferred dispatch after an error callback. A cancelled capture must not publish a frame or update the scheduler baseline; use request identity/generation to reject stale callbacks. After cancellation/error, create a fresh snapshotter so late completion cannot leak into a new capture.

The camera supports bearing and zoom. `Options.withPadding` is documented to take effect only when `region != null`; do not assume cameraPosition alone places GPS at 72% height. For the first map, project the GPS marker with `snapshot.pixelForLatLng(position)` and draw it at that point. Tune the camera/anchor only after verifying the projection. The same API can project a future polyline; for long routes, GeoJSON Source + LineLayer is preferable without adding routing to this phase.

[Official snapshot examples](https://maplibre.org/maplibre-native/android/examples/snapshotter/), [MapSnapshot API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.snapshotter/-map-snapshot/index.html).

## Service pipeline

The service owns GPS, state, snapshotter, and latest JPEG. Request a 480×240 / pixelRatio 1 snapshot, verify actual dimensions, draw the marker and original overlay without hiding attribution, encode on Default, then publish the JPEG. Call `RenderScheduler.markRendered` only after the complete frame exists. Bluetooth keepalive may continue sending the cached JPEG while the map loads; never create a second NaviLite reader/writer.

Start with at most **one snapshot per second** while moving and none for unchanged state. This is an initial power policy, not a measured SDK capacity. Rendering may take longer than a second over network/GPU: keep it serial and use the latest state. Separate `snapshotMs`, `encodeMs`, `frameAgeMs`, and ACK metrics without logging coordinates. On network/style failure, show an explicit unavailable/stale map state; do not draw decorative roads as real cartography. GPS loss must invalidate current measurements even if a cached image remains.

## Configurable vector source

Initial provider: OpenFreeMap. The `https://tiles.openfreemap.org/styles/dark` style was studied; integration ultimately chose an **original minimal NavFrame style**, `app/src/main/assets/map/tft-style.json`, with a vector source at `https://tiles.openfreemap.org/planet` and provider-hosted glyphs. The Dark JSON is not incorporated. Settings accepts another HTTPS style URL for a provider or self-host. The `MapDataSource` abstraction should keep source selection/configuration separate from the renderer and not turn the initial endpoint into a required backend. The [provider quick start](https://openfreemap.org/quick_start/) allows its styles in mobile apps using MapLibre Native and documents self-hosting. Its [style repository](https://github.com/hyperknot/openfreemap-styles) documents the Dark endpoint and origin.

OpenFreeMap advertises access without accounts/API keys, no view/request limits, and commercial use; it provides no SLA. These statements are not availability guarantees or authorization for mass regional downloads: distinguish documented SDK map use from unauthorized automated collection. Do not implement bulk prefetch in this phase. [Provider information](https://openfreemap.org/), [terms, updated 2026-09-09](https://openfreemap.org/tos/).

While investigating the unused Dark alternative, a Python request to `/styles/dark` with its default User-Agent returned HTTP 403. Repeating from the same machine with a browser User-Agent and a descriptive app User-Agent (`NavFrame/0.3 MapLibre/13.5.2 (Android 14)`) both returned HTTP 200 and 20,959 bytes of JSON. This is representative, not the exact User-Agent emitted by the SDK. Resources for the selected original style, `/planet` and glyphs, were checked separately with curl and returned HTTP 200. `/planet` returned TileJSON with vector PBF tiles, zoom 0–14, and OpenFreeMap/OpenMapTiles/OpenStreetMap attribution. This confirms HTTP availability under those conditions, pending native Android loading. Handle errors and allow source changes; never conceal failures with invented data.

A style may reference sprites/glyphs/TileJSON and additional tiles; a configurable HTTPS URL must also serve those resources. The local original style uses `Style.Builder.fromJson` and absolute HTTPS URLs. A configurable remote style uses `fromUri`, avoiding broken relative URLs caused by manually downloading/transforming JSON. To improve TFT legibility, hide unnecessary building/layers using a style observer or implement a compatible transformation after checking IDs and licenses. The fetched Dark JSON has no POI layers; it contains `building`, `highway_name_other`, `highway_name_motorway`, boundaries, and `place_*`. Its sources are `openmaptiles` vector and `ne2_shaded` Natural Earth raster; shading is not needed on TFT and may be removed if active. Its sprite/glyph/TileJSON references are absolute HTTPS URLs.

Do not use `tile.openstreetmap.org` as a production backend or capture another app's screen. Online tile requests disclose the viewed map area to the provider even if the app does not send GPS history; document that in settings. OpenFreeMap may use a CDN and security logs: [provider privacy policy](https://openfreemap.org/privacy/). Offline PMTiles/MBTiles and regional downloads belong to a later phase; a cached map does not imply offline coverage.

The footer retains `OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors`, including error/GPS-loss frames. For custom styles, check all attribution in MapLibre style metadata and add provider-required credits. Keep credits legible in the 480×240 footer and link terms from the phone's license screen. The SDK is BSD-2-Clause; NavFrame's TFT style does not copy the Dark theme. See [MapLibre research](MAPLIBRE_RESEARCH.md) and [Licensing](LICENSING.md).

An invalid style or connection should show `MAPA NO DISPONIBLE` and remove stale speed/bearing values; do not replace the real map with an illustrated road or fabricated position. Snapshot requests are asynchronous: create, start, and cancel `MapSnapshotter` from Main; its callback runs on the calling thread, and restart must wait for the SDK reset. Old/cancelled callbacks must not publish frames after STOP or a mode change.

## Phase limits

Phase 4 added cartography and position; phase 5 adds an explicit route plan. Neither screen is suitable for driving guidance. Robolectric can test composition/overlay but does not run JNI/OpenGL or load tiles on a device. Runtime Android and physical TFT map/route tests are still required. Demo routes must never be confused with real routes. See the [product brief](../yamaha_tft_native_navigator_codex.md) for later phases.

## Phase 5: coordinate route plan without guidance

The screen offers origin **DEMO Oviedo · simulated** or **GPS real · last position**, destination latitude/longitude, and an HTTPS `/route` endpoint. There is no geocoder. A GPS origin is accepted only if the provider is enabled and the fix is younger than 15 seconds. On explicit calculation, it sends a Valhalla `costing=motorcycle` request (beta profile); result text identifies origin, distance, estimated duration, and maneuver count. It retains response geometry for drawing a line; the received shape is not progress tracking or turn-by-turn navigation.

The polyline is shown as **PREVIEW** or **PLAN … no guidance**. The marker continues to show current GPS or demo position; movement does not imply map matching, route adherence detection, turn distance, or dynamically calculated ETA. Switching ordinary sources clears route/overlay; showing a plan explicitly selects the source appropriate to its origin. **Stop route · keep GPS/TFT** removes the plan/overlay without itself stopping GPS/map or transport.

Coordinates are sent to the chosen endpoint only when the user explicitly calculates a route. The endpoint receives origin/destination and connection metadata; the request body and coordinates are not written to local logs. The implementation limits requests to one per second and does not retry automatically. The public Valhalla/FOSSGIS server is for development/fair use and has no SLA. See [ROUTING.md](ROUTING.md) and [Licensing](LICENSING.md); check terms and data retention before switching endpoints.

This phase has no turn-by-turn, active route progress, live turns, off-route detection, rerouting, voice, geocoding, or verification that roads are safe for motorcycles. Do not use the line or route data to ride. A successful synthetic service request does not validate user GPS origin, MapLibre rendering on the phone, or route transmission to the TFT. Those physical tests remain pending.

## Phase 6: active route guidance

**Start guidance for calculated route** activates the GPS tracker or DEMO simulation over the geometry. The HUD displays icon, distance to maneuver, remaining distance, and approximate ETA. NO GPS, OFF ROUTE, and RECALCULATING hide stale estimates. Arrival requires distinct position evidence. The service preserves continuous transport and its stop controls.

Native composition is checked with `guidance.png` over a test background. This does not certify MapLibre GPU or physical navigation. See [testing](TESTING.md) and [thresholds/limits](ROUTING.md).
