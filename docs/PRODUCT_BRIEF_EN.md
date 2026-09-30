# Yamaha TFT Native Navigator: English brief summary

This is an English summary of the original project brief, [`yamaha_tft_native_navigator_codex.md`](../yamaha_tft_native_navigator_codex.md). That source document is preserved in its original Spanish-language form for provenance; this summary is not a line-by-line translation and may not include every original requirement.

## Product goal

Build an Android navigation app for compatible Yamaha motorcycle TFT displays. The initial target is a Yamaha MT-07 2026, 480×240 TFT, connected through the Yamaha/Garmin CCU using a NaviLite-compatible Bluetooth transport. NavFrame renders its own navigation image at the TFT's target dimensions. It does not capture or crop another app's screen with MediaProjection.

The intended data flow is Android GPS plus map data and route results into shared navigation state, then a native 480×240 renderer, a TFT-specific overlay, JPEG/frame encoding, and Bluetooth transmission. The design does not aim to reproduce all of Google Maps or Waze.

## Planned user capabilities

The original brief describes GPS position, destination search, route calculation, map/position/route display, next maneuver, remaining distance and estimated arrival, turn-based rerouting, direct TFT rendering, and screen-off operation. It proposes an Android Kotlin app, MapLibre for maps, a routing backend, configurable geocoding, local regional maps in a later phase, and a phone UI for setup/control.

The implementation is experimental and incomplete. Current verified status is documented in the main [README](../README.md), [Architecture](ARCHITECTURE.md), and [Testing/physical validation record](TESTING.md). In particular, physical confirmation of basic images on one Yamaha/phone combination does not establish a working map renderer, safe route guidance, or long-duration screen-off operation.

## Engineering principles retained

- Keep demo data visibly distinct from GPS/route data.
- Make route start, online search, and other network requests explicit user actions.
- Preserve attribution and licenses for OSM data, map styles, fonts, and software.
- Keep location out of logs and avoid sending sensitive coordinates/search terms to public services.
- Separate rendering cadence from Bluetooth keepalive/ACK flow; do not queue stale frames.
- Treat GPS loss, service errors, stale map data, and failed route requests as explicit states.
- Verify simulation, JVM/Android tests, and physical-device behavior as separate evidence.

## Provenance and limitations

The source brief is a planning document, not a current API contract or report of completed features. Later technical references document what was implemented and tested. Consult [NaviLite protocol notes](NAVILITE.md), [TFT rendering](TFT_RENDERING.md), [MapLibre research](MAPLIBRE_RESEARCH.md), and [offline regional packages](GLOBAL_OFFLINE.md) for current details.
