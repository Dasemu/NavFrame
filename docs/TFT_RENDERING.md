# TFT rendering and navigation data

This document distinguishes visual demo data from real GPS data. Output for the Yamaha is an original **480 × 240** image rendered directly; the phone preview should show the same JPEG bytes before they are sent.

## Phase 2: synthetic demo

Start the demo with **Start animated demo**. It supports TFT layout iteration and transport tests. Position, motion, road segment, route, maneuver, and distances are invented by the app. The UI says `DEMO · carretera y maniobras ficticias`; the TFT says `DEMO · RUTA FICTICIA` and `ETA … · DEMO`. These values do not come from the phone, OSM, route calculation, or motorcycle. Demo values are not riding directions.

Drawing uses a logical 480 × 240 `Canvas`, prioritizing the maneuver, route shape, and position marker. `RenderScheduler` keeps only the latest state and avoids repaints for small changes: initial thresholds are 2 m position, 3° bearing, 0.5 m/s speed, and 10 m for visible distance changes. Initial maximum redraw rates are 1 FPS stopped, 5 FPS moving, and 10 FPS turning. These limit redraws; NaviLite keepalive sending is a separate configurable cadence (0.5/1/2 s) that waits for ACK and never queues frames. An ACK delay must not build a frame queue or block the demo.

The accompanying camera policy keeps the marker near 72% screen height and changes simulated zoom by speed/turn distance: 17 below 30 km/h, 16 through 70 km/h, 15 above, and 17.5 within 150 m of a turn. In this phase the renderer draws an illustrative road directly on Canvas; it does not project tiles or a map. These parameters only support future map integration.

Check by starting the simulated demo and watching the preview for at least one minute. Position/bearing should change, frames should remain 480 × 240, and `DEMO` should remain visible. Stopping the demo should halt its animation and update source. This check does not test GPS or real navigation.

## Phase 3: GPS position

The real source uses Android `LocationManager` with `GPS_PROVIDER` from the navigation service. On **Start real GPS (no map/route)**, Android requests precise location (`ACCESS_FINE_LOCATION`) if it is not already granted. The foreground service uses type `location`, or `location|connectedDevice` when TFT Bluetooth is also active. The app does not request permanent/background location: the service starts from the visible Activity. Revisit this policy if the start flow changes.

Platform references: [location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), and [restrictions on starting a service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

When switching from demo to GPS, the image stops identifying itself as demo. A local grid moves with real position, and the marker rotates to the real bearing if Android provides one; speed and accuracy also come from the fix. This is a schematic visual aid without cartography, not a scaled map or route. Fixes older than 15 seconds are discarded. If GPS is lost, the TFT shows `SIN SEÑAL`, `--`, and `Esperando una posición GPS reciente`. Do not replace missing measurements with invented values. Do not persist exact coordinates in logs.

The GPS phase has no map, destination search, calculated route, or real turn-by-turn instructions. A decorative line that follows position is not a navigable route. Do not use this screen as riding guidance.

To check it, grant precise location and test while parked in an open area; validate loss/recovery states without sharing coordinates in screenshots or logs. **Stop navigation · keep TFT** must cancel GPS listening without ending an existing TFT session; **Disconnect TFT** also ends Bluetooth. This phase has not yet had runtime visual verification; review the exported Canvas image and physical test separately. Emulator location simulation can aid development but does not replace testing on the Xiaomi.

## Phase 4: MapLibre in a 480 × 240 frame

The app includes MapLibre Native OpenGL and `MapSnapshotter` to request a GPU snapshot at **480 × 240** from the service; it then draws its own marker/state, attribution, and text and encodes the NaviLite JPEG. It does not use an Activity `MapView` or capture StreetCross. The default style is a reduced dark vector JSON in NavFrame assets, using OpenFreeMap tile/vector sources and glyphs. An HTTPS URL setting can replace the remote style; attribution is configured alongside the URL. The MapLibre layer is reused and cancelled on stop/mode change. Snapshots are serial and limited to about 1 Hz moving and 0.5 Hz at rest to avoid a queue; Bluetooth send/ACK cadence is independent. According to the [MapSnapshotter API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.snapshotter/-map-snapshotter/index.html), the object is used on the UI thread, draws off-thread, calls back on the caller thread, and `cancel()` must be called from its creation thread. The implementation defers continuation resumption until the callback's internal reset finishes.

**Start demo map (simulated position)** places synthetic position on real cartography and displays `MAPA · POSICIÓN SIMULADA`, without invented turn/route instructions. **Start GPS map (no route)** uses granted GPS permission and centers on real fixes, displaying `MAPA GPS · SIN RUTA`. Earlier GPS-only and demo-only modes remain separate. This phase draws position on a map; it does not search destinations, calculate routes, geocode, or provide navigation instructions.

Remote styles request resources for the visible map area. This reveals the viewed area and network metadata to the configured source even though NavFrame does not store GPS history or log coordinates. OpenFreeMap states that it does not retain IPs in routine logs but may use a CDN and temporarily log IPs during incidents; see its [privacy policy](https://openfreemap.org/privacy/) and [terms](https://openfreemap.org/tos/). At the time this phase was implemented, offline use required an authorized source and later implementation; it did not download regions or promise offline coverage. Regional package downloads are now documented in [OFFLINE.md](OFFLINE.md) and [GLOBAL_OFFLINE.md](GLOBAL_OFFLINE.md). Do not substitute raster tiles from `tile.openstreetmap.org`; its separate rules prohibit preloading/offline use.

The footer retains `OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors`, including error/GPS-loss frames. For custom styles, check all attribution in MapLibre metadata and add provider-required credits; the configuration stores attribution with the URL. Credits must remain legible in the 480 × 240 footer and the phone license screen must link to terms. The SDK is BSD-2-Clause; NavFrame's TFT style does not copy Dark. See [MapLibre research](MAPLIBRE_RESEARCH.md) and [Licensing](LICENSING.md).

A connection/style error should show `MAPA NO DISPONIBLE` and remove speed/bearing figures that are no longer current; do not replace the real map with an illustrative road or fabricate a position. Snapshots are asynchronous: create, start, and cancel `MapSnapshotter` on Main; its callback runs on the caller thread, and restarting must wait for the SDK reset. Old/cancelled callbacks must not publish frames after STOP or a mode change.

## Phase limits

Phase 4 added map and position; phase 5 added an explicit route plan. Neither screen is suitable for riding guidance. Robolectric can check composition/overlay but does not run JNI/OpenGL or tiles on a device. Runtime Android and physical map/route tests on the TFT are required. Demo routes must never be confused with real routes. Later phases are in the [product brief](../yamaha_tft_native_navigator_codex.md).

## Phase 5: coordinate route plan without guidance

The screen offers origin **DEMO Oviedo · simulated** or **GPS real · last position**, destination latitude/longitude, and HTTPS `/route` endpoint. There is no geocoder. A GPS origin is accepted only when the provider is enabled and the fix is younger than 15 seconds. Calculation is user initiated and uses Valhalla `costing=motorcycle` (beta profile); the result identifies origin, distance, estimated duration, and maneuver count. Response geometry is retained to draw a line; it is not progress tracking or turn-by-turn navigation.

The polyline is shown as **PREVIEW** or **PLAN … no guidance**. The marker continues to show current GPS or demo position; movement does not mean NavFrame performs map matching, detects route adherence, measures turn distance, or dynamically calculates ETA. Switching ordinary sources clears the route/overlay; showing a plan explicitly selects the source appropriate to its origin. **Stop route · keep GPS/TFT** removes the plan and overlay without stopping GPS/map or transport by itself.

Coordinates are sent to the chosen endpoint only on explicit route calculation. The endpoint receives origin/destination and connection metadata; request body and coordinates are not written to local logs. The implementation limits requests to one per second and does not retry automatically. The public Valhalla/FOSSGIS server is for development/fair use, with no SLA. See [ROUTING.md](ROUTING.md) and [Licensing](LICENSING.md); review terms and data retention before changing endpoint.

This phase has no turn-by-turn, active progress, live turns, off-route detection, rerouting, voice, geocoding, or motorcycle road-safety verification. Do not ride using the line or route data. A successful synthetic service request does not validate user GPS origin, MapLibre rendering on the phone, or a route sent to the TFT. Those physical tests remain pending.

## Phase 6: guidance on an active route

**Start guidance for calculated route** activates the GPS tracker or DEMO simulation over route geometry. The HUD shows icon, distance to maneuver, remaining distance, and approximate ETA. NO GPS, OFF ROUTE, and RECALCULATING hide stale estimates. Arrival requires distinct position observations. The service retains continuous transport and stop controls.

Native composition is checked using `guidance.png` on a test base. This does not certify MapLibre GPU or physical navigation. See [testing](TESTING.md) and [thresholds and limitations](ROUTING.md).
