# Offline places and regional maps: status and next steps

Initial proposal dated 2026-09-29, updated 2026-09-30. NavFrame 0.10 introduced the Asturias POI catalog and local regional-package format/import, with Cantabria as a second region. A later increment added an on-demand worldwide source/build/publication workflow and app catalog downloads; its current behavior is described in [GLOBAL_OFFLINE.md](GLOBAL_OFFLINE.md). The catalog is not complete worldwide coverage. Offline routing is not implemented. This document preserves the original data/product rationale and distinguishes it from later implementation.

## Current data

Asturias has a 34,617,483-byte PMTiles map containing roads, places/towns, inland water, land cover, and buildings at zoom 6–14, plus an SQLite catalog of 11,457 POIs. Cantabria is a separate `.navframe` map/POI package. Both retain separate source extracts and hashes. Maps are selected as individual regional sources; place search combines installed catalogs using the chosen location's coverage. The APK includes Noto Sans Regular glyphs for Unicode 0–2047. Current app limits are 1 GiB total, 600 MiB per compressed regional package, and up to twelve installed versions. These are prototype limits, not demonstrated capacity for all regions.

## Offline places for Asturias

The appropriate source is **the same OSM extract**, not repeated geocoder queries. OSM documents [amenity](https://wiki.openstreetmap.org/wiki/Key:amenity), [shop](https://wiki.openstreetmap.org/wiki/Key:shop), and [tourism](https://wiki.openstreetmap.org/wiki/Key:tourism); objects can be nodes, areas, or relations. Names and metadata remain OSM data under ODbL, with no promise of completeness, current opening hours, or the commercial coverage of another provider.

Initial visible categories:

| Visible category | Input OSM tags |
|---|---|
| Fuel | `amenity=fuel` |
| Motorcycles and repair | `shop=motorcycle/motorcycle_repair`; `motorcycle:repair=yes` or `repair=yes` on a motorcycle shop identifies repair; `shop=car_repair` remains a separate general workshop category |
| Food | `amenity=restaurant/fast_food/cafe` |
| Lodging | `tourism=hotel/motel/hostel/guest_house/camp_site` |
| Shopping | Compatible `shop=*` objects in their own category; do not treat `abandoned`/`disused` as active businesses |

Motorcycle workshop semantics are documented at [shop=motorcycle](https://wiki.openstreetmap.org/wiki/Tag:shop%3Dmotorcycle). Do not claim every car repair shop works on motorcycles.

Generate two products from the same PBF date/schema:

1. **PMTiles** with an additional `poi` layer for icons/names at close zoom, simplified geometry for display, category-specific appearance zoom, and density handling. Use original drawings; TFT shows a small useful selection while the phone can explore/tap.
2. **`pois.sqlite`** for complete offline search, independent of zoom and currently loaded tiles. Columns include internal ID, OSM type/ID, category, name/brand/operator, lat/lon, existing address, and optionally opening hours. Retain OSM identity for cross-region de-duplication and references. For unnamed entries, use the category as a label; do not invent a business name.

For areas, use an interior point/known entrance rather than an arbitrary center that may fall outside the geometry. A map point does not certify access or motorcycle entrance; routing currently snaps the destination to a road. Preserve provenance/date and remove disused/abandoned objects during build. Do not blindly merge nearby branches just because names match.

The proposed local query `PoiCatalog.search(query, categories, bounds, limit)` returns results and position. SQLite supports [R*Tree](https://www.sqlite.org/rtree.html) for spatial lookup; verify Android SQLite support in implementation and retain category/lat/lon indexes as fallback. Use text search compatible with the minimum Android SQLite version (verify FTS4), normalize diacritics explicitly, and parameterize queries. “Near me” requires usable position; allow map-region exploration without inventing GPS. Sort by actual distance to the selected location and distinguish straight-line from road distance. Do not imply coverage outside installed regions.

Selecting a business sets the existing destination, prepares route preview, and allows an explicit start. It must not replace active guidance or launch remote searches for every map movement. Offline catalog tests should cover coordinates/areas, duplicates, categories, Unicode, empty searches, overlapping regions, and no-network selection.

The [official Nominatim policy](https://operations.osmfoundation.org/policies/nominatim/) prohibits systematic queries to download all POIs and autocomplete on the public instance. Nominatim remains an explicit remote destination search; it **does not build this catalog**. Extracting categories from downloadable PBF files does not query that API. Any future business-data provider needs its own license/contract and attribution.

## Regional packages and global rollout

[Geofabrik provides downloadable Europe, country, and subregion extracts](https://download.geofabrik.de/europe.html) under ODbL. Offer regional packages, not all of Europe in the APK. Users may select regions for a trip and may eventually install all compatible packages if storage permits. For Spain/France/Germany and other large countries, use subdivisions when a package exceeds build or app limits. See [GLOBAL_OFFLINE.md](GLOBAL_OFFLINE.md) for the implemented on-demand workflow, inputs, release/catalog contract, and current constraints.

The original proposed build pipeline, outside the phone, was:

`Geofabrik regional PBF → pinned Planetiler + NavFrame profile → map.pmtiles + pois.sqlite → validation → immutable manifest → import files or HTTP server`

Maintain a versioned shared schema, explicit migrations, and a compatible renderer. A POI map layer needs a map schema version the original 1.0.0 importer did not accept. Do not accept arbitrary PMTiles schemas. Phone and TFT use the same styles/glyphs/attribution.

Each manifest should contain stable region ID, label, coverage polygon/bounds, parent region, OSM date, schema/style version, min/max zoom, URLs, exact sizes and SHA-256 per artifact, license/attribution, and minimum compatible app version. A catalog signed by a pinned publication key would provide authenticity; a hash alone detects corruption but does not authenticate a manifest served by an attacker. Versioned packages retain immutable URLs and allow atomic updates.

Select by **actual coverage**, not only rectangles: countries/subregions have irregular boundaries, islands, and overlap. Before a trip, install regions intersecting the route with edge overlap. Do not present a bbox as continuous coverage. A multi-region renderer must mount the required sources and avoid duplicate features; SQLite search combines indexes and deduplicates by OSM identity. The current map selector still selects an individual regional map, so combining adjacent regions requires new work.

For European/global use, add shared, versioned glyph coverage by writing system. Latin/Greek/Cyrillic are broadly covered by current ranges, but Georgian and many other business-name characters are not. Do not promise full names with glyphs 0–2047. New packages should declare needed ranges; generate/distribute font derivatives under their OFL terms without hidden remote font fetches.

### Download and install design

Users select regions, see sizes/date/required space, then download or import via SAF. Keep file/USB import available for personal use without hosting. Neither approach requires downloading every country or harvesting public map tiles.

For first-party packages, use HTTPS, content length/ETag, HTTP Range + If-Range resume, persistent staging with progress/checksum, cancel/pause, free-space checks for both new and old versions, activation only after every component validates, and coordinated deletion with readers. If ETag changes or server rejects Range, restart safely; never combine different versions. Keep work off the UI thread. Replace a whole region; [PMTiles is not updated in place](https://docs.protomaps.com/pmtiles/).

A server needs no dynamic GIS; static HTTPS files/manifests with practical transfer limits and Range support are enough. [PMTiles cloud-storage guidance](https://docs.protomaps.com/pmtiles/cloud-storage) describes object storage and partial requests. For personal use, generating on a PC and importing may be enough; CDN/managed storage is a future distribution choice with storage, transfer, and request costs. No price estimate or hosting commitment is made here.

### Size and capacity

The Geofabrik page consulted advertised **32.7 GB for the raw Europe PBF** and showed a specific `europe-260928.osm.pbf` version at **35,069,743,863 bytes** (page and bytes use different rounding units). This is **not** the final NavFrame package size or phone download. Output depends on zoom, layers/attributes, simplification, density, and POI/index choices. Do not extrapolate Asturias's ratio to the continent.

Useful size estimates require first building Asturias with POIs and a second border region, measuring final bytes/build time/RAM/temporary disk, and summing exact sizes from published manifests. Separate PC/server build cost, bytes downloaded, and storage needed for installation/update. Do not promise “Europe fits in X GB” without those measurements.

## Offline cartography/POIs versus offline routing

Maps and local places can work offline. **Calculating or recalculating routes** still depends on configured Valhalla; following an already calculated geometry is different. PMTiles and SQLite POIs do not contain the routing graph/access/restriction indexes needed to calculate routes.

Offline routing later requires evaluating a routing engine and building compatible regional data. [Valhalla uses dedicated graph tiles](https://valhalla.github.io/valhalla/concepts/why-tiles/); Android/NDK compilation, footprint, timings, and cross-region routing need evaluation. MapLibre SDK does not provide this. OsmAnd has a different engine/OBF format, but embedding it requires the GPL/PolyForm/license assessment in `OSMAND_RESEARCH.md`; do not treat its packages or engine as interchangeable with PMTiles.

## Original recommended increments

After closing 0.8, first deliver **Asturias offline POIs** for fuel/motorcycles/food/lodging, local search, and selecting a destination from real data. Regenerate map schema, add the SQLite catalog, migration, and package importer together; verify phone/TFT map and airplane-mode search. Then build a second region with the same pipeline and validate continuity/de-duplication. Only then expand to a downloadable Europe catalog and implement update/resume. This describes the original plan, not a claim that those steps are all complete.

## Local Cantabria package (2026-09-30)

The first local package was generated to validate the format and file import. It is [`dist/es-cantabria-20260928.1.navframe`](../dist/es-cantabria-20260928.1.navframe); the verifiable local catalog is [`dist/regional-catalog.json`](../dist/regional-catalog.json). Those local artifacts had no associated endpoint, server, or HTTP download. Copy the `.navframe` file to the device and import it with the file picker. Current network catalog downloads and worldwide package publication are separate later features; see [GLOBAL_OFFLINE.md](GLOBAL_OFFLINE.md).

The package is **27,782,594 bytes** (SHA-256 `b4f8e8075301a645e33511642119997dc27b1fb8e52ae0caa14519ebd3b79ce9`). It contains only `manifest.json`, `map.pmtiles`, and `pois.sqlite`. The source PBF was timestamped `2026-09-28T20:23:05Z`, size **35,537,700 bytes**, SHA-256 `0036ed2762d2ef7833362f15b5e09904dacbea47d0a2381bbc1fab80a92d0731`. The manifest records source/component hashes, PBF URL, official Cantabria `.poly` URL and SHA-256, OSM/ODbL attribution, and Planetiler/NavFrame build versions.

The map uses NavFrame Offline 1.0.0 style, zoom 6–14, and the actual Cantabria polygon; manifest rectangle validates extent, while the polygon determines whether a coordinate is covered. Local catalog has **8,448 POIs**: 171 fuel, 205 workshops, 2,690 food, 1,000 lodging, 4,382 shopping. Extraction reported zero geometry failures. The package has no offline routing graph. Asturias remains another bundled data pack; this initial Cantabria package did not publish or create a Europe catalog.

To reproduce the package on a build machine, install dependencies from `tools/offline/poi-requirements.txt`, place matching PBF and `.poly` inputs locally, and provide Planetiler 0.10.2:

```sh
python3 tools/offline/build-region.py \
  --id es-cantabria --name Cantabria --version 20260928.1 \
  --pbf build/offline/cantabria/source.osm.pbf \
  --coverage-poly build/offline/cantabria/coverage.poly \
  --coverage-url https://download.geofabrik.de/europe/spain/cantabria.poly \
  --planetiler-jar build/offline/planetiler-0.10.2.jar \
  --source-url https://download.geofabrik.de/europe/spain/cantabria-latest.osm.pbf \
  --python build/offline/poi-venv/bin/python \
  --output dist/es-cantabria-20260928.1.navframe
```

To package already generated components, replace `--planetiler-jar` with `--map-pmtiles ... --pois-sqlite ...`; the packager rereads the PBF timestamp and recalculates hashes. Geofabrik's `latest` endpoint can change; retain the local PBF identified by SHA-256 to reproduce this exact version. The 27.8 MB figure applies to this package and must not be extrapolated to other regions.
