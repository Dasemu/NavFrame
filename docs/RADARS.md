# Fixed speed-camera advisories

Camera advisories are opt-in and default to off. They are available only during live GPS route guidance, never in demo playback. The alert text says “Possible fixed speed camera nearby.” It is informational; it is not a speed-limit warning, a guarantee of camera presence or absence, or a substitute for posted signs and safe riding.

## Data and matching

The offline POI database can include an indexed `speed_cameras` table generated from a regional OSM extract. The current generator accepts active `highway=speed_camera` nodes and selected traffic-surveillance nodes explicitly tagged for speed enforcement. Existing regional packages without this optional table remain usable. OSM coverage is community maintained and can be incomplete, stale, or incorrectly positioned. The database does not infer cameras from unrelated CCTV or speed display tags.

An alert is considered only when a route exists and a camera is close to its geometry. The runtime requires successive GPS fixes showing approach, an acceptable bearing, lateral distance, fix accuracy/age, and minimum speed. It announces at roughly 35–500 m and suppresses repeat alerts for the same OSM object for ten minutes. Distances are approximate, straight-line estimates rounded for display.

Direction tags are preserved as source evidence, but the app does not certify enforcement direction, lane, lens orientation, or relation topology. Parallel roads, route geometry, missing camera data, changes since the extract, mobile cameras, and average-speed enforcement are not reliably handled. Never treat silence as evidence that a road has no enforcement.

## Legal and regional availability

Rules vary by jurisdiction. Check local law before enabling or using camera-location alerts. For example, France's [Code de la route Article R413-15](https://www.legifrance.gouv.fr/loda/article_lc/LEGIARTI000025111528/2026-05-23) covers devices or products that warn or inform users about the location of enforcement equipment. This project does not provide a jurisdiction-by-jurisdiction legal determination. Global package availability does not mean camera alerts are lawful or enabled everywhere; distribution should apply an appropriate regional policy before broad release.

Camera locations are derived from OSM and distributed with ODbL attribution. Source extracts, timestamps, and checksums are recorded in package manifests. Do not combine another provider's camera data without checking its license, terms, completeness, and local restrictions.

Automated extraction and policy tests do not validate real-road timing, GPS quality, legal status, or audible behavior. Physical audio and riding-condition validation remain pending.
