# Licenses, attribution, and service terms

This document summarizes the project's current third-party materials and service dependencies. It is not legal advice. Data licenses, software licenses, and public-service policies are separate obligations; review the full notices included with the relevant component before redistribution or commercial use.

## Project and third-party software

| Component | Terms or status | Practical note |
| --- | --- | --- |
| NavFrame application | No single permissive license is declared for the whole repository | Check the individual code and data notices. Do not assume every file is available under the same terms. |
| NaviLite / Pillion-derived code | PolyForm Noncommercial 1.0.0; see `third_party/pillion/LICENSE.md` and `NOTICE.md` | The license restricts commercial use. Preserve the required notice and license. Separate permission is needed for uses outside its terms. |
| MapLibre Native Android | BSD 2-Clause | Preserve required copyright, license, and disclaimer notices for distributed binaries and source. |
| Gson | Apache License 2.0 | The app bundles Gson; its license and notice are in `app/src/main/assets/GSON_LICENSE.md`. |
| Planetiler 0.10.2 | Apache License 2.0 | Build-time tool; it is not an Android runtime dependency. |
| PMTiles format | Specification is CC0 | This does not change the license of the map data stored in a PMTiles file. |
| Noto Sans glyph assets | SIL Open Font License 1.1 | Bundled glyph files and the full font notice are included in the APK assets. |

## OpenStreetMap-derived data

Offline maps, places, and camera catalogs are derived from OpenStreetMap data and are distributed under [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Attribute the data to **© OpenStreetMap contributors** and link to [openstreetmap.org/copyright](https://www.openstreetmap.org/copyright). Regional package manifests retain source URLs, timestamps, checksums, build recipe, and attribution. Keep these details with redistributed packages.

ODbL applies to the database/data and derivative database obligations; it does not automatically place all NavFrame application code under ODbL. Geofabrik provides regional extracts as a source; its `latest` links change, so package provenance records the exact source bytes used.

## Online services

- **Valhalla routing:** The default endpoint is the public FOSSGIS demo service at `valhalla1.openstreetmap.de`. The [Valhalla project documents fair-use/rate limiting](https://valhalla.github.io/valhalla/) and asks apps published to end users to notify it through GitHub Discussions and send an identifying `X-Client-Id`. This endpoint is for development and testing, not a production availability guarantee. For a public release, arrange an appropriate provider or operate a service under explicit terms.
- **Nominatim search:** The public OSMF service's [usage policy](https://operations.osmfoundation.org/policies/nominatim/) applies to aggregate traffic. It limits use to at most one request per second, requires an identifying User-Agent or Referer and attribution, and prohibits autocomplete and systematic bulk use. Do not assume a per-device limiter is sufficient for a multi-user release. Use a suitably provisioned provider or proxy before scaling.
- **Online map styles:** The default online style uses OpenFreeMap/OpenMapTiles/OSM data. Its public service is provided under its own [terms](https://openfreemap.org/tos/) and does not constitute an SLA. Style providers may receive map-resource requests for the visible area. Keep attribution supplied by the style visible. NavFrame does not use `tile.openstreetmap.org` for bulk tile downloads or offline map generation.

Online search sends the submitted text and network metadata to the configured geocoder. Routing sends origin and destination coordinates to the configured routing endpoint. A remotely hosted map style receives resource requests that reveal the viewed area and network metadata. Avoid sending personal or sensitive destinations to public services.

## Notices

The APK contains license/attribution notices under `app/src/main/assets/`; NaviLite retains the Pillion notice under `third_party/pillion/`. When redistributing a binary, preserve the notices applicable to the components and datasets it contains. Review all transitive dependency licenses for a changed dependency set.
