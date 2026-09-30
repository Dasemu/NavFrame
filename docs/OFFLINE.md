# Offline maps and regional packages

NavFrame offline packages provide map display and local place/camera lookup. They do not include an offline routing graph or offline address geocoder.

## Included and regional data

The APK includes an Asturias PMTiles map and SQLite place database derived from OpenStreetMap data obtained from Geofabrik. The data timestamp is `2026-09-28T20:23:05Z`; the map covers zoom levels 6–14. The app also supports regional `.navframe` packages containing `manifest.json`, `map.pmtiles`, and `pois.sqlite`. Cantabria packages are available under `dist/` for manual import and as part of the on-demand worldwide region publishing workflow.

Regional downloads use the HTTPS catalog configured in **Settings → Offline maps**. Each catalog entry describes a region, package URL, exact byte count, SHA-256, geographic coverage, source date, and attribution. The downloader checks the package length and SHA-256, then the importer validates the package manifest, PMTiles metadata, and SQLite schema before committing it to private app storage. A malformed or incomplete package is rejected. Manual file import remains available.

The default catalog is hosted as a GitHub Release asset. Catalog URLs can be changed in settings; only use a catalog from a source you trust. SHA-256 protects against a package differing from the catalog, but the catalog is not currently signed or authenticated with a pinned key.

The app supports individual regional maps. It does not merge adjacent map sources into a continuous multi-region map view. Local place/camera searches can use installed regional catalogs, filtered by each region's coverage. The practical package limits are 600 MiB per archive, 1 GiB total offline storage, and up to twelve installed versions. Actual device storage and memory may impose lower practical limits.

## Worldwide region generation

The [worldwide package workflow](GLOBAL_OFFLINE.md) discovers source regions from Geofabrik's machine-readable index, builds selected regions on demand, and publishes packages and a catalog through GitHub Releases. “Worldwide” describes where a region may be sourced from; it does not mean every region has been generated, published, or tested. Choose smaller subdivisions for extracts or generated packages that exceed the current build or app limits. Do not infer global package size or performance from the Asturias or Cantabria examples.

Each generated package preserves OSM attribution and ODbL provenance. Geofabrik `latest` URLs change over time; retain the actual source checksums and timestamps to reproduce a package. See [Licensing](LICENSING.md) for data and redistribution terms.

## Offline use and limitations

Maps and installed places can be viewed without a network connection. Search for local places does not query Nominatim. Address search and route calculation need the configured online services. Following a previously calculated route can continue from its saved geometry, but route calculation and recalculation still need a network connection.

Map detail is bounded by package coverage and maximum zoom. POI data can be absent or outdated. Glyph coverage is limited to the bundled font ranges, so some writing systems or characters may not render. OSM data does not certify business status, road access, motorcycle suitability, speed limits, or camera completeness.

The owner confirmed that the bundled Asturias map works offline on a Xiaomi 11 Lite 5G NE running Android 14. This confirms phone use only. The last reported physical map-render attempt on the Yamaha TFT displayed `MAPA NO DISPONIBLE`; a successful TFT map render has not been confirmed.

## Package format and validation

The current package manifest uses schema version 1 and identifies `NavFrame Offline/1.0.0` map data and POI schema 1. Geographic coverage supports GeoJSON Polygon and MultiPolygon geometry, including multiple islands and holes. Package IDs and immutable versions are retained when publishing. Do not substitute a generic PMTiles archive: compatible data layers, style, glyph source, coverage, and POI schema are required.

OpenStreetMap-derived map and database products are distributed under ODbL 1.0. Preserve the manifest and attribution with packages. The app includes the full ODbL notice in its license information.
