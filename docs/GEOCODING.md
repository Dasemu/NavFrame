# Explicit destination search

Typing in NavFrame searches installed local place catalogs. Online address search requires a separate user action; NavFrame sends the submitted search text to the configured geocoder and does not send GPS as part of that query. The default public Nominatim service is subject to the [OSMF usage policy](https://operations.osmfoundation.org/policies/nominatim/): one request per second aggregated across the application, no client-side autocomplete or systematic bulk queries, and an identifying User-Agent/Referer with attribution. A per-device delay does not satisfy a multi-user aggregate limit. Do not submit sensitive search text to a public service.

## Implementation contract

Research dated 2026-09-29. NavFrame retains the `Geocoder.search(query)` abstraction, and the user selects a `SearchResult` before route calculation. Typing does not initiate network requests: only Search or an equivalent explicit action does. Searching does not start guidance, replace an active route, or assume the first result is correct.

## Public server policy

**Read the [official OSMF Nominatim policy](https://operations.osmfoundation.org/policies/nominatim/) before enabling the public service.** It permits explicit queries with moderate numbers of users; it requires an identifying User-Agent/Referer, attribution, and an absolute maximum of **one request per second aggregated across the entire application**, not per user or phone. Autocomplete, systematic queries, and personal/confidential data are prohibited. The application must be able to change backend without an APK update; a proxy/cache is recommended. Its LLM section requires a deliberate, informed developer decision and prohibits providing it as a generic geocoder for app-generation platforms.

The developer brief deliberately selects Nominatim as a possible provider, with explicit search and no abuse. The initial implementation is limited to the personal NavFrame prototype; it is not permission to distribute multiple installations with independent request budgets. Before expanding the user base, use a proxy that limits aggregate traffic or a self-hosted/commercial provider with suitable terms. The screen must show the policy and selected provider. Availability and results are not guaranteed; provider blocking must be respected.

## Endpoint and Android HTTP

Documented public endpoint: `https://nominatim.openstreetmap.org/search`. Another HTTPS URL **compatible with Nominatim jsonv2** can be selected in settings without recompilation. Removing the public provider must remain possible. The [official Search parameters](https://nominatim.org/release-docs/latest/api/Search/) allow free-form q, jsonv2, limit, and accept-language; the obsolete search.php endpoint is not used.

`nominatimSearchParameters(query)` returns `q` with normalized whitespace, `format=jsonv2`, `limit=5`, `accept-language=es`, and `addressdetails=0`. Android encodes each parameter through the HTTP encoder; do not concatenate unescaped queries. Queries have 2–200 characters, a local UI restriction rather than a service limit. Do not send GPS, viewbox, user identity, or email. User-Agent must identify NavFrame/version rather than the generic HTTP client; the geocoder requires neither X-Client-Id nor UUID.

Allow one request in flight, at least one second between HTTP starts, a timeout, and cancellation when leaving or replacing a search. Do not retry errors or HTTP429 automatically: show a pause and respect Retry-After before a manual action. A search generation must prevent late callbacks from replacing newer results.

Use a bounded in-memory cache (target: 100 entries/24 hours), keyed by endpoint, normalized query, and language, including empty results. Different queries must not mix; changing endpoint must partition or clear the cache. Destination/address history is not persisted by default. This cache and rate limiter reduce traffic for one installation; they do not solve the global limit when distributed to multiple phones.

## Parsing and selection

`parseNominatimResults(json)` uses strict Gson and returns `SearchResult(name, position, attribution)`; previous constructors remain available with default attribution. `display_name` describes the destination; lat/lon are finite numeric strings within geographic ranges. The result's `licence` is preserved as text; a standard OSM credit is shown when missing. See the [official output format](https://nominatim.org/release-docs/latest/api/Output/).

Responses are limited to 512 Ki characters, at most 40 remote objects, up to five UI results, and labels of 2,048 characters. Android must also limit bytes before loading a String. Exact name/coordinate duplicates and label control characters are removed without interpreting HTML. Invalid JSON/error/status/body produces local messages without the query or coordinates. An empty list means no matches, not failure.

Do not retain place_id as a permanent identity or infer motorcycle access from a city/POI centroid. Show the full name and selected coordinates before calculation; Valhalla may snap the destination to another road position. Search does not guarantee access, parking availability, or site restrictions.

## Privacy, attribution, and tests

The provider receives search text, IP, and HTTP metadata; do not enter private/confidential addresses into the public service. Show `© OpenStreetMap contributors` and a [copyright/ODbL](https://www.openstreetmap.org/copyright) link in results, preserving map credits and remote attribution where appropriate. No Nominatim code is embedded: the adapter/parser are original and consume HTTP. Gson/notices are already distributed in the APK.

`NominatimGeocodingTest` covers an original jsonv2 fixture, Unicode/antimeridian coordinates, attribution, empty results, duplicates/limits, invalid/non-strict JSON and backend errors, finite string coordinates/ranges, labels, Unicode/escaping parameters, and validation before HTTP. Tests do not run automated public queries. Adapter checks cover caching, rate limits, and cancellation; public availability and actual search quality require independent verification.

Phase 6 also includes a GPS recovery adjustment: two new stable fixes after loss/a gap before acquiring progress, without interpreting time without signal as demonstrated movement. It is validated with increment 0.6, without changing the previously delivered 0.5 APK.
