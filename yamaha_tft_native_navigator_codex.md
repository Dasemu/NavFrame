# Yamaha TFT Native Navigator — Project Brief for Codex

## 1. Goal

Build an Android navigation app for Yamaha motorcycles compatible with Garmin StreetCross / NaviLite.

The first target motorcycle is:

- Yamaha MT-07 2026
- TFT navigation resolution target: 480×240
- Transport: Bluetooth to the Yamaha/Garmin CCU
- Protocol: NaviLite-compatible transport, reusing or adapting the reverse-engineered work from Pillion where licensing permits

The central design goal is to avoid screen recording / MediaProjection.

The app must render its navigation UI directly at the TFT's target resolution and send those frames to the motorcycle.

---

## 2. Core idea

Do NOT build this:

```text
Google Maps / Waze
        ↓
Android screen at phone resolution
        ↓
MediaProjection / screen capture
        ↓
crop / scale
        ↓
480×240
        ↓
JPEG
        ↓
Bluetooth / NaviLite
        ↓
Yamaha TFT
```

Build this:

```text
                Android GPS
                    │
           ┌────────┴────────┐
           │                 │
       Map data          Routing
           │                 │
           └────────┬────────┘
                    │
               Navigation state
                    │
                    ▼
            Native map renderer
                 480×240
                    │
               TFT overlay UI
                    │
                    ▼
             framebuffer/image
                    │
          NaviLite frame encoder
                    │
                Bluetooth
                    │
                    ▼
                Yamaha TFT
```

The phone should never need to render a full-screen navigation application just to capture it afterward.

---

# 3. MVP features

The first working version should support:

1. Obtain GPS position from Android.
2. Search for a destination.
3. Calculate a route.
4. Display:
   - map
   - current position
   - route polyline
   - next maneuver
   - distance to next maneuver
   - remaining distance
   - estimated arrival time
5. Render the navigation view directly at 480×240.
6. Encode frames in the format expected by NaviLite.
7. Connect to the Yamaha CCU through Bluetooth.
8. Display the custom navigation interface on the TFT.
9. Recalculate route when the rider deviates from it.
10. Keep navigation functional with the phone screen off.

Do not attempt to clone every feature of Google Maps or Waze in the MVP.

---

# 4. Proposed technology stack

## Android

Preferred implementation:

- Kotlin
- Android SDK
- Coroutines / Flow
- Foreground navigation service
- Android Location APIs
- Bluetooth Classic / RFCOMM where required by NaviLite
- Jetpack components where useful

Avoid adding large frameworks unless there is a clear benefit.

---

# 5. Map data

Use OpenStreetMap data.

Do NOT depend on the public `tile.openstreetmap.org` raster tile server as the production backend.

Preferred approaches:

### Online / prototype

Use a vector tile provider with a reasonable free tier, or a self-hosted vector tile source.

### Offline / later phase

Support downloaded regional maps using one of:

- PMTiles
- MBTiles
- another efficient vector-tile package supported by the selected renderer

Initial offline target regions may include:

- Asturias
- Galicia
- Cantabria
- Castilla y León

The architecture must not hard-code a specific tile provider.

Create a `MapDataSource` abstraction.

Example:

```kotlin
interface MapDataSource {
    suspend fun prepareRegion(region: MapRegion)
    fun tileSource(): TileSource
}
```

---

# 6. Map renderer

Preferred renderer:

- MapLibre Native for Android

The rendering target should be a dedicated surface or framebuffer sized as close as possible to:

```text
480 × 240
```

Do not render at the physical phone resolution and then scale down unless forced by an API limitation.

Create a TFT-specific map style.

Prioritize legibility over cartographic detail.

Suggested visual hierarchy:

- dark background
- secondary roads: subtle
- primary roads: clearly visible
- route: thick and high contrast
- traveled route: visually subdued
- current-position marker: obvious
- road labels: only useful major labels
- buildings: disabled or extremely subtle
- POIs: mostly disabled
- administrative clutter: disabled
- terrain: disabled initially

The TFT has little screen space. Every visible element should justify its presence.

---

# 7. Routing

Preferred initial routing engine:

- Valhalla

Use OpenStreetMap-based routing.

During the MVP, Valhalla may run remotely on a self-hosted server.

Suggested development topology:

```text
Android app
    │
    ├── GPS
    ├── MapLibre rendering
    │
    └── HTTPS / WireGuard
            │
            ▼
       Valhalla server
            │
            ▼
      route + maneuvers
```

Later, investigate fully offline routing on Android.

Create a generic routing abstraction:

```kotlin
interface RoutingEngine {
    suspend fun calculateRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        options: RouteOptions
    ): RouteResult

    suspend fun recalculateRoute(
        origin: GeoPoint,
        previousRoute: RouteResult,
        options: RouteOptions
    ): RouteResult
}
```

Initial route profiles:

- fastest
- avoid motorways
- touring

Do not over-engineer motorcycle-specific routing in phase 1.

---

# 8. Geocoding / destination search

For development, support a geocoding provider abstraction.

Possible initial option:

- Nominatim / OpenStreetMap

The public Nominatim instance must NOT be abused.

Do not implement aggressive autocomplete against the public service.

For the MVP, use:

```text
user writes address
        ↓
presses Search
        ↓
single geocoding request
```

Later possibilities:

- self-hosted Nominatim
- Photon
- Pelias
- another geocoder

Create:

```kotlin
interface Geocoder {
    suspend fun search(query: String): List<SearchResult>
}
```

---

# 9. NaviLite / Yamaha TFT transport

This is the most important integration layer.

Study the existing reverse-engineering work from the Pillion project and related public documentation.

Before copying code:

1. inspect the project's license;
2. identify reusable protocol code;
3. document what is reused;
4. preserve all required attribution;
5. avoid copying incompatible code.

Create a standalone transport module:

```text
navilite/
    BluetoothConnection
    NaviLiteSession
    NaviLiteHandshake
    NaviLiteFrameEncoder
    NaviLitePacketWriter
    NaviLiteProtocolConstants
```

High-level API:

```kotlin
interface TftTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendFrame(frame: TftFrame)
    val connectionState: Flow<ConnectionState>
}
```

The rest of the app must not depend directly on low-level Bluetooth implementation details.

---

# 10. Rendering pipeline

Target:

```text
NavigationState
      ↓
TftRenderer
      ↓
480×240 framebuffer
      ↓
image encoder
      ↓
NaviLite
```

Suggested model:

```kotlin
data class NavigationState(
    val position: GeoPoint,
    val bearing: Float,
    val speed: Float,
    val route: RouteResult?,
    val nextManeuver: Maneuver?,
    val distanceToNextManeuverMeters: Int?,
    val remainingDistanceMeters: Int?,
    val etaEpochMillis: Long?,
    val navigationStatus: NavigationStatus
)
```

Renderer:

```kotlin
interface TftRenderer {
    suspend fun render(state: NavigationState): TftFrame
}
```

Frame:

```kotlin
data class TftFrame(
    val width: Int = 480,
    val height: Int = 240,
    val encodedBytes: ByteArray,
    val timestampMs: Long
)
```

---

# 11. Adaptive frame rate

Do NOT blindly transmit 15 FPS continuously.

The application should update frames based on movement and navigation changes.

Example policy:

### Motorcycle stopped

```text
0–1 FPS
```

Only redraw if:

- maneuver changes
- UI state changes
- route changes
- connection changes

### Straight road

```text
2–5 FPS
```

### Turns / rapid bearing change

```text
8–15 FPS
```

Possible triggers:

```text
position delta > N meters
bearing delta > N degrees
next maneuver changed
distance label changed enough to matter
route recalculated
camera zoom changed
```

Implement the update policy as an independent component:

```kotlin
class RenderScheduler
```

It should be configurable.

---

# 12. Camera behavior

The initial renderer should behave like a motorcycle navigation display.

Suggested modes:

### During active navigation

- camera follows rider
- bearing-up orientation
- rider marker approximately in lower third
- upcoming road visible ahead
- dynamic zoom based on speed and maneuver distance

Example idea:

```text
< 30 km/h   → closer zoom
30–70 km/h  → medium zoom
> 70 km/h   → wider zoom
```

Approaching a maneuver should automatically zoom closer when useful.

Avoid excessive zoom animation.

---

# 13. TFT user interface

Suggested initial layout:

```text
┌───────────────────────────────────────────────┐
│         MAP                                   │
│                                               │
│                             ┌───────────────┐ │
│                             │ ↱  350 m      │ │
│                             │ AS-246        │ │
│                             └───────────────┘ │
│                                               │
│                   ▲                           │
│                  rider                        │
│                                               │
├───────────────────────────────────────────────┤
│ 23 km remaining       18 min       ETA 17:42 │
└───────────────────────────────────────────────┘
```

Exact layout can change after real TFT testing.

Priorities:

1. next maneuver
2. distance to maneuver
3. route geometry
4. rider position
5. remaining distance / ETA

Avoid tiny text.

---

# 14. Navigation state machine

Implement explicit navigation states:

```text
IDLE
SEARCHING
ROUTE_PREVIEW
NAVIGATING
OFF_ROUTE
REROUTING
ARRIVED
GPS_LOST
TFT_DISCONNECTED
ERROR
```

Do not bury these states inside UI logic.

---

# 15. Rerouting

Basic logic:

1. project current GPS point onto current route;
2. measure distance from route;
3. if distance exceeds threshold for a configurable duration, mark OFF_ROUTE;
4. recalculate;
5. atomically replace active route;
6. update renderer.

Avoid rerouting due to normal GPS jitter.

Example initial threshold:

```text
distance from route > 30–50 m
for several consecutive GPS updates
```

Make this configurable.

---

# 16. Phone UI

The phone application should be minimal.

Initial screens:

```text
Home
 ├── Yamaha connection status
 ├── Search destination
 ├── Recent destinations
 └── Settings

Route preview
 ├── destination
 ├── distance
 ├── ETA
 ├── Start navigation
 └── Cancel

Navigation
 ├── connection status
 ├── basic map preview
 ├── Stop
 └── Recalculate

Settings
 ├── map source
 ├── routing profile
 ├── offline maps
 ├── NaviLite debug
 └── frame-rate/debug settings
```

The TFT remains the primary navigation display while riding.

---

# 17. Background operation

Navigation must continue when:

- Android screen is off
- application UI is not foregrounded

Use an Android foreground service where required.

The service owns:

- GPS subscription
- active route
- navigation state
- render scheduler
- TFT connection
- frame transmission

UI connects to this service but does not own navigation state.

---

# 18. Power efficiency

Power efficiency is a primary goal.

Avoid:

- MediaProjection
- screen recording
- full-resolution rendering
- unnecessary 15 FPS transmission
- constant route recalculation
- unnecessary GPS accuracy when stationary
- decoding raster tiles that are never displayed
- excessive UI animations

Measure:

- CPU usage
- GPU usage
- network usage
- Bluetooth throughput
- battery drain
- average FPS
- frame encoding time
- frame send latency

Add a developer/debug overlay or logging facility for these measurements.

---

# 19. Logging

Use structured logging.

Log important events such as:

```text
GPS_UPDATE
ROUTE_REQUEST
ROUTE_RECEIVED
ROUTE_FAILED
OFF_ROUTE
REROUTE
TFT_CONNECT
TFT_DISCONNECT
NAVILITE_HANDSHAKE
FRAME_RENDER
FRAME_ENCODE
FRAME_SENT
FRAME_DROPPED
```

Do not log exact user location persistently by default.

Allow verbose protocol logging only in developer mode.

---

# 20. Security / privacy

Principles:

- no account required for MVP
- no analytics by default
- no upload of location history
- no persistent route history unless explicitly enabled
- API keys must not be hardcoded into public repositories
- use Android secure configuration mechanisms where needed
- self-hosted services should use HTTPS or WireGuard

---

# 21. Suggested repository structure

```text
yamaha-tft-nav/
│
├── app/
│
├── core/
│   ├── model/
│   ├── location/
│   ├── navigation/
│   ├── routing/
│   ├── geocoding/
│   └── logging/
│
├── map/
│   ├── maplibre/
│   ├── styles/
│   └── offline/
│
├── tft/
│   ├── renderer/
│   ├── encoder/
│   └── navilite/
│
├── service/
│   └── NavigationService
│
├── ui/
│
├── docs/
│   ├── ARCHITECTURE.md
│   ├── NAVILITE.md
│   ├── ROUTING.md
│   ├── TFT_RENDERING.md
│   └── TESTING.md
│
└── README.md
```

Use Gradle modules if it improves separation without making the initial prototype cumbersome.

---

# 22. Development phases

## Phase 0 — Research

Before building the full app:

- inspect current Pillion repository
- inspect its NaviLite protocol implementation
- identify required Bluetooth profile
- identify handshake sequence
- identify frame format
- confirm TFT resolution
- confirm JPEG/image requirements
- confirm packet framing
- confirm MT-07 2026 compatibility assumptions
- document findings in `docs/NAVILITE.md`

Deliverable:

```text
Bluetooth connection + protocol research document
```

---

## Phase 1 — NaviLite smoke test

Build the smallest Android app capable of:

1. pairing/connecting to motorcycle;
2. performing NaviLite handshake;
3. sending a hard-coded 480×240 test image.

Test image:

```text
BLACK BACKGROUND

YAMAHA TFT TEST

480 × 240
```

Success criterion:

> The generated test image appears on the MT-07 TFT.

Do not proceed to full navigation until this works.

---

## Phase 2 — Synthetic renderer

Build the internal rendering pipeline without maps.

Render:

- solid background
- fake road
- fake route
- current-position arrow
- maneuver icon
- ETA

Send it to TFT.

Success criterion:

> Dynamic synthetic navigation frames appear reliably on the TFT.

---

## Phase 3 — GPS

Add Android GPS.

Display:

- current coordinates internally/debug only
- real bearing
- real speed
- moving current-position marker

Success criterion:

> TFT reacts to actual motorcycle/phone movement.

---

## Phase 4 — MapLibre

Integrate MapLibre.

Render a real OSM-based map to 480×240.

No routing yet.

Success criterion:

> TFT shows the rider's actual location on a moving map.

---

## Phase 5 — Routing

Integrate Valhalla.

Allow destination coordinates first.

Example developer interface:

```text
destination latitude
destination longitude
START
```

Draw returned route.

Success criterion:

> Actual calculated route is displayed on TFT.

---

## Phase 6 — Turn-by-turn navigation

Add:

- maneuver parsing
- next-turn icon
- distance countdown
- remaining distance
- ETA
- off-route detection
- rerouting

Success criterion:

> Complete usable A→B motorcycle navigation.

---

## Phase 7 — Search

Add destination search using geocoder abstraction.

Start with Nominatim or another suitable provider.

---

## Phase 8 — Offline maps

Add regional offline map download and loading.

Start with Asturias.

Later add offline routing if practical.

---

# 23. Testing strategy

## Unit tests

Test:

- route parsing
- maneuver conversion
- distance calculations
- off-route logic
- adaptive frame-rate policy
- navigation state machine
- packet framing
- encoding

## Emulator tests

Mock:

- GPS
- route responses
- Bluetooth transport
- TFT output

Provide a `FakeTftTransport` that displays generated 480×240 frames inside the Android app or saves them to disk.

This is essential so most development can happen without sitting next to the motorcycle.

Example:

```kotlin
class FakeTftTransport : TftTransport
```

It should display exactly what the TFT would receive.

## Real motorcycle tests

Real-bike testing should initially be limited to:

- connection
- handshake
- display correctness
- latency
- connection recovery

Perform development testing while stationary whenever practical.

---

# 24. Simulator / desktop preview

Strongly preferred:

Create a simple TFT preview window or Android debug screen with an exact:

```text
480×240
```

viewport.

Every frame generated for the bike should optionally be mirrored there.

This allows rapid UI development without the motorcycle.

---

# 25. Image encoding

Investigate exactly what NaviLite expects.

If JPEG is required:

- encode directly from the 480×240 render target;
- benchmark quality levels;
- find lowest acceptable size without visible artifacts.

Test quality levels approximately:

```text
40
50
60
70
80
```

Record:

```text
average frame size
encode time
Bluetooth send time
visual quality
```

Choose based on actual measurements rather than assumptions.

---

# 26. Performance targets

Initial goals:

```text
Resolution:          480×240
Normal FPS:          2–5
Turning FPS:         up to 10–15
Stationary FPS:      ~0–1
Render latency:      < 50 ms desired
Encode latency:      < 20 ms desired
Navigation latency:  low enough to make turns safely readable
```

These are development targets, not hard protocol assumptions.

Measure actual Yamaha CCU behavior.

---

# 27. Failure handling

The app must recover gracefully from:

- Bluetooth disconnect
- CCU unavailable
- GPS loss
- Internet loss
- routing server unavailable
- malformed route
- frame send failure
- app backgrounding
- Android process recreation

On TFT disconnect:

- navigation must continue internally;
- attempt reconnect using conservative retry behavior;
- do not destroy active route.

---

# 28. Future features

Do not implement these until MVP works:

- traffic
- speed cameras where legally and technically appropriate
- live road closures
- weather
- voice navigation
- route recording
- GPX import/export
- custom motorcycle route generation
- curvy-road routing
- scenic routing
- fuel stations
- charging stations
- media controls
- phone notifications
- automatic day/night mode
- TFT themes
- alternative routes
- multi-stop routes

---

# 29. Possible future motorcycle-specific routing

A later version may provide profiles such as:

```text
FAST
TOURING
CURVY
AVOID MOTORWAYS
AVOID CITIES
```

Potential route scoring could incorporate:

- road class
- curvature
- elevation changes
- speed limits
- traffic if a source becomes available
- road surface where OSM data is sufficiently reliable

Do not assume OSM surface data is always correct.

---

# 30. Important legal/licensing tasks

Codex must verify current licenses and terms before implementation.

Specifically verify:

- OpenStreetMap attribution requirements
- MapLibre license
- Valhalla license
- Pillion license
- NaviLite reverse-engineering code reuse implications
- selected tile provider terms
- selected geocoding provider terms

Show OSM attribution where required.

Do not assume that free public infrastructure is an unrestricted production API.

---

# 31. Codex working rules

Codex should:

1. Work incrementally.
2. Keep the project buildable after each phase.
3. Prefer complete working implementations over placeholder architecture.
4. Add tests for protocol and navigation logic.
5. Document protocol discoveries.
6. Avoid unnecessary dependencies.
7. Avoid premature abstraction except at external boundaries:
   - routing
   - geocoding
   - maps
   - TFT transport
8. Never introduce screen capture unless explicitly requested.
9. Optimize only after measuring, except for the fundamental 480×240 direct-render architecture.
10. Keep motorcycle-specific protocol code isolated.

---

# 32. First tasks for Codex

Start here.

## Task 1

Research the current public Pillion implementation and document:

```text
- license
- repository structure
- Android implementation
- Bluetooth transport
- NaviLite handshake
- packet/frame structure
- image encoding
- known Yamaha compatibility
- reusable pieces
```

Write findings to:

```text
docs/NAVILITE_RESEARCH.md
```

Do not copy code until licensing has been checked.

## Task 2

Create the initial Android/Kotlin project.

Minimum:

```text
minSdk appropriate for modern Android
Kotlin
Coroutines
basic Compose or Views UI
foreground-service-ready structure
```

## Task 3

Create interfaces:

```text
TftTransport
TftRenderer
RoutingEngine
Geocoder
MapDataSource
LocationProvider
```

## Task 4

Implement:

```text
FakeTftTransport
```

and a 480×240 preview.

## Task 5

Render a synthetic test screen:

```text
YAMAHA TFT NAV
480 × 240

NEXT TURN → 350 m
23 km
ETA 17:42
```

## Task 6

Only after research is complete, begin the real NaviLite Bluetooth implementation.

---

# 33. Definition of first major milestone

Milestone `M1-TFT` is complete when:

1. the app installs on Android;
2. the phone pairs/connects with the Yamaha;
3. NaviLite session initializes successfully;
4. the app generates its own 480×240 image;
5. that image appears on the Yamaha TFT;
6. no MediaProjection or screen recording is used.

Everything else comes after this milestone.

---

# 34. Ultimate goal

The finished product should behave approximately like:

```text
                    ┌────────────────┐
                    │ Yamaha MT-07   │
                    │      TFT       │
                    └───────▲────────┘
                            │
                         NaviLite
                            │
                    ┌───────┴────────┐
                    │ Android app    │
                    │                │
                    │ MapLibre       │
                    │ Navigation     │
                    │ Valhalla       │
                    │ GPS            │
                    └───────┬────────┘
                            │
                   OSM / optional APIs
```

with no dependency on displaying or capturing Google Maps/Waze.

The project should prioritize:

```text
simplicity
reliability
low battery use
clear TFT readability
offline capability
open-source components
modularity
```

over feature count.
