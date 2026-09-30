# Offline places / POIs for Asturias — 0.9

Catalog derived from the same OpenStreetMap/Geofabrik PBF as the Asturias map. Nominatim and Overpass are not queried to generate it; Google Places or a new backend are not integrated.

## Data and provenance

Source: [Geofabrik Asturias](https://download.geofabrik.de/europe/spain/asturias.html), `asturias-latest.osm.pbf` downloaded for this project, timestamp **2026-09-28T20:23:05Z**. PBF SHA-256: `1ab5967cc162027f0d05b2b603b65ad3f5d3b5466551613054cf5e6b28b9eb5d`.

Artifact: `app/src/main/assets/offline/asturias-pois.sqlite`, **2,347,008 bytes**, SHA-256 **c5d8413f260b0b3e5bdc3da4c9b5e33f8e66f5acd80cc284a7e8668f039798f9**, SQLite `user_version=1`, recipe **NavFrame POI 1.0.0**. Timestamp, schema, source hash, attribution, bounds, and counts are stored in `metadata`. The rectangle south/west/north/east is 42.9/−7.2/43.75/−4.45; objects are clipped to the regional extract polygon, not guaranteed to cover every neighboring region within the rectangle.

**11,457 places**, from OSM nodes, ways, and relation areas:

| Group | Count | Selection |
|---|---:|---|
| Fuel | 259 | `amenity=fuel` |
| Workshops | 196 | `shop=motorcycle_repair/car_repair/tyres`; explicit motorcycle repair `motorcycle:repair=yes/only`, or `repair=yes/only` on `shop=motorcycle` |
| Food | 4,393 | `amenity=restaurant/fast_food/cafe/food_court/pub/bar` |
| Lodging | 1,383 | `tourism=hotel/motel/hostel/guest_house/camp_site/caravan_site/chalet/apartment` |
| Shopping | 5,226 | Other valid `shop` values, including motorcycle/parts stores without explicit repair tagging |

Classification follows [amenity](https://wiki.openstreetmap.org/wiki/Key:amenity), [shop](https://wiki.openstreetmap.org/wiki/Key:shop), [tourism](https://wiki.openstreetmap.org/wiki/Key:tourism), and [motorcycle](https://wiki.openstreetmap.org/wiki/Tag:shop%3Dmotorcycle). A general workshop is not verified as offering motorcycle repair, and a fuel station does not guarantee stock. OSM may be incomplete or stale; names/hours/contact details are shown as source data, not as commercial verification.

Nodes use their own position. Assembled areas use an interior `representative_point` instead of a centroid that might fall outside a concave polygon; compatible open ways use their midpoint. These are object positions, not guaranteed entrances or road access. **Zero geometry failures** were reported in this extraction. IDs `n/<id>`, `w/<id>`, and `r/<id>` distinguish object types and avoid ID collisions; records are not merged just because names match. OSM can represent one business as different objects, so duplicate physical businesses are not perfectly removed.

Relevant inactive markers are excluded (`disused`/`abandoned` and prefixes for shop/amenity/tourism), as are `shop=no/vacant/disused/abandoned/closed`. The generator does not invent names or addresses: names use existing `name`/`name:es`/`brand`/`operator`, with a category “unnamed” label only as a last resort.

## Contract and SQLite

The original core `OfflinePlace` retains ID, name, `PlaceCategory`, position, optional address/openingHours/website/phone, and optional straight-line distance. `normalizePlaceSearch` uses NFD, removes combining marks, lowercases independently of locale, compacts whitespace/control/format characters, and preserves non-Latin alphabets. Python generates `search_name` with the same algorithm. Map glyphs remain limited to Unicode 0–2047; native list text does not depend on these PBF glyphs.

Schema: `places(id TEXT PRIMARY KEY, name TEXT, search_name TEXT, category TEXT, latitude REAL, longitude REAL, address TEXT, opening_hours TEXT, website TEXT, phone TEXT)`. Name/search/category/position are required and categories are checked. Indexes: `(category,latitude,longitude)`, `(latitude,longitude)`, `search_name`. `metadata(key TEXT PRIMARY KEY,value TEXT)` identifies version/coverage. Android queries are parameterized and escape `%`, `_`, and backslash via `escapePlaceLike` with `ESCAPE '\'`; an apostrophe remains data. Bounded substring search does not make the name index a full-text-search guarantee for millions of places.

The Android repository opens the catalog as read-only SQLite. Local text/category and region/selected-position search returns a bounded number of places; selecting one enters the existing destination/preview flow. Displayed distance is straight line, not road distance. Without a usable GPS fix, users can search from the map center or explore installed catalogs; NavFrame does not invent a phone location or automatically query a geocoder for POIs.

Address fields preserve existing `addr:*` tags. `opening_hours` remains raw text; the app does not evaluate “open now”. Website accepts only HTTP/HTTPS with a host and no credentials/whitespace/control or dangerous characters. Phone retains only simple telephone characters. Both are optional and are not opened automatically; external links require explicit user action. The phone map displays catalog markers; this increment does not add a POI layer to PMTiles.

## Compatibility and license

The catalog installs independently from the cartographic file: PMTiles/`NavFrame Offline 1.0.0` style are unchanged, as are user-imported regions. Old maps are not deleted or regenerated to enable businesses. Catalog schema 1 can be copied/updated as a whole after size/checksum/schema validation and before activation; incompatible versions must be rejected without destroying the previous database. Map manager 0.8 does not interpret this catalog as new PMTiles.

The complete derived SQLite database is distributed inside the APK ZIP at `assets/offline/asturias-pois.sqlite` under [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). `OFFLINE_DATA_LICENSE.md` adds source/hash/recipe to the full license; preserve `© OpenStreetMap contributors` in the UI. Build-time Pyosmium 4.3.1 ([BSD 2-Clause](https://github.com/osmcode/pyosmium/blob/v4.3.1/LICENSE.TXT)) and Shapely 2.1.2 ([BSD 3-Clause](https://github.com/shapely/shapely/blob/2.1.2/LICENSE.txt)) are not packaged in Android. The script/models are original, not OsmAnd code.

## Reproduction and tests

```sh
python3 -m venv build/offline/poi-venv
build/offline/poi-venv/bin/pip install -r tools/offline/poi-requirements.txt
build/offline/poi-venv/bin/python tools/offline/build-pois.py \
  --pbf build/offline/asturias.osm.pbf \
  --output app/src/main/assets/offline/asturias-pois.sqlite \
  --osm-date 2026-09-28T20:23:05Z
build/offline/poi-venv/bin/python -m unittest discover -s tools/offline -p 'test_build_pois.py' -v
```

The command takes an explicit date that must match the PBF; `latest` changes, and another source changes the counts/hash. It builds in staging and activates the file after `integrity_check`; the script does not download data onto the phone. Extraction does not calculate routes or use network access.

Four Python tests passed: an original OSM fixture with node/way/relation cases, categories/lifecycle, optional metadata and sanitization, plus integrity/coordinates/normalization/identity checks for the **complete real catalog**. Three core JVM tests check Unicode/diacritics, LIKE escaping, and optional fields; Android runs the final repository/UI build tests. These checks do not certify businesses remain open or have road access. Physical 0.9 POI search/selection still needs testing on the phone; the owner's earlier confirmation of Asturias offline map is separate evidence and does not validate this new catalog.

## Local Cantabria (0.10)

The search center must lie in Asturias or an installed region. The app then queries available catalogs, filters each result to its region's polygon and the search radius, and deduplicates by OSM identity. The Cantabria file contains **8,448 POIs** (fuel 171, workshops 205, food 2,690, lodging 1,000, shopping 4,382), SQLite `user_version=1`, derived from the Geofabrik PBF timestamped 2026-09-28T20:23:05Z. Source, hash, coverage, recipe, and component hashes are in the manifest within `dist/es-cantabria-20260928.1.navframe` and in [OFFLINE.md](OFFLINE.md). Importing it does not remove the Asturias map or catalog. Local search can work in airplane mode; route calculation and address search still require Internet. Cantabria is one additional region, not a Europe-wide catalog.
