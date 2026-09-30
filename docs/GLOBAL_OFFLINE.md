# Worldwide offline package pipeline

The app installs individual `.navframe` packages containing a PMTiles map, a SQLite POI/camera database and a manifest with source timestamp, coverage, attribution and SHA-256 hashes. Worldwide means regions discoverable in the official Geofabrik index, with publication on demand. It does not mean every region has already been generated or that OSM has complete camera data.

## Generate and publish

In `Dasemu/NavFrame`, open **Actions → Build worldwide offline regions → Run workflow**. Enter Geofabrik IDs such as `andorra,act,cantabria,us/california`. First run with **publish = false** to inspect artifacts; run with **publish = true** to publish packages. Each run builds at most 32 independent regions, with two concurrent builders and a 330-minute timeout per builder. No planet download or full-world local build is performed.

The frozen `tools/offline/campaign-global-2026-09.json` contains the global leaf-region campaign, split into 17 tag-triggered batches. It excludes parent extracts to avoid overlapping coverage and excludes packages already present when the manifest was created. Push the batch tags to queue all remaining eligible regions:

```sh
for n in $(seq -w 1 17); do git push origin "offline-global-2026-09-batch-0$n"; done
```

Each batch publishes successful packages independently; oversized or otherwise unsupported extracts fail as individual matrix jobs. GitHub's catalog concurrency allows one running and one pending refresh, so after all batches complete, run **Actions → Refresh offline package catalog → Run workflow** once to ensure the index includes every successful package.

One-time bootstrap with SSH push access (no local GitHub CLI/token required): after pushing the project to the default branch, create and push the exact tag below. Only this tag and `offline-bootstrap-andorra-*` retry tags trigger an automatic **Andorra** build with publication enabled; other tag or branch pushes do not trigger the offline builder. The catalog becomes available after that workflow succeeds. For a retry after fixing a failed run, push a new tag such as `offline-bootstrap-andorra-v2`; preserve existing tags.

```sh
git tag offline-bootstrap-andorra
git push origin offline-bootstrap-andorra
```

Discover valid IDs without downloading extracts:

```sh
python3 tools/offline/global-regions.py discover --output build/offline/worldwide-sources.json
```

The discovery output describes source availability. The app catalog contains only successfully built packages. Standard hosted runners accept extracts up to 1 GiB and packages below 500 MiB, consistent with the app's archive budget. Choose smaller Geofabrik subregions when an extract exceeds the budget; a future dedicated larger runner can increase the input budget, but the package must remain within the Android installer limit. Input budgets do not guarantee that a runner has enough memory or disk for every extract.

Local build (Python dependencies and Java 21 required):

```sh
pip install -r tools/offline/poi-requirements.txt
curl --fail --location --output planetiler.jar https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar
python3 tools/offline/global-regions.py build --region andorra --version 20260930.1 --planetiler-jar planetiler.jar --output build/offline/andorra
```

`build-region.py` also accepts existing PBF/.poly/PMTiles inputs for network-free builds. Planetiler and POI dependencies are pinned by version. Build provenance records the recipe, upstream PBF SHA-256, coverage SHA-256, Planetiler binary SHA-256 and PBF replication timestamp. Geofabrik's `latest` source URL rotates daily; the downloaded PBF is checked against its official MD5 sidecar, and its actual bytes are identified by SHA-256. If the latest link fails (including redirect loops) or its checksum mismatches during daily rotation, the builder reads the bounded official region HTML and selects its newest dated PBF with a matching-basename checksum link. It downloads and verifies that exact pair and records the actual dated extract URL in the manifest. An invalid checksum on the fallback fails the build. OSM's replication timestamp identifies data freshness, while the package version identifies a publication.

## Catalog and publication contract

Every package publishes a release `offline-gf-REGION-RUNID.ATTEMPT` with the package and `regional-catalog.json`. The workflow creates a draft, uploads assets, then publishes it; it never replaces package assets. Published region releases should be treated as immutable. Package manifests retain schema version 1 and stable IDs `gf-REGION` (slashes in upstream IDs become hyphens, e.g. `us/california` → `gf-us-california`).

The catalog job reads **all** published region releases, merges their fragments and selects the latest publication per region. Concurrent campaign runs may coalesce pending catalog refreshes; use the manual refresh workflow after a large campaign completes. The merged index uses compact JSON and is checked against a 32 MiB / 4,096-entry Android discovery budget before publication.

The mutable discovery index is:

`https://github.com/Dasemu/NavFrame/releases/download/offline-catalog/regional-catalog.json`

This index release must remain mutable so its asset can be updated; do not enable repository-wide release immutability without moving the index to another host. The individual package URLs remain versioned. Historical package releases remain available. Catalog rebuilding is serialized. A failed region does not prevent catalog rebuilding for successful releases.

Catalog format:

```json
{
  "schemaVersion": 1,
  "regions": [{
    "manifest": {"id": "gf-andorra", "name": "Andorra", "version": "12345.1"},
    "archive": {
      "path": "gf-andorra-12345.1.navframe",
      "url": "https://github.com/Dasemu/NavFrame/releases/download/offline-gf-andorra-12345.1/gf-andorra-12345.1.navframe",
      "bytes": 123456,
      "sha256": "64 lowercase hexadecimal characters"
    },
    "countryCode": "AD",
    "country": "Andorra",
    "region": "andorra",
    "cameraCount": 4
  }]
}
```

The example abbreviates the manifest: real entries include the full map/POI hashes, geographic bounds and Polygon/MultiPolygon coverage. Metadata is optional; multi-country extracts may have no country code. Downloaders use `archive.url` when present and verify archive length/hash before installation. `path` remains a flat filename for older local catalogs. `global-regions.py merge` rejects conflicting entries for the same immutable ID/version.

## Coverage and cameras

Geofabrik `.poly` parsing preserves multiple islands and holes. Camera extraction accepts globally used `highway=speed_camera` nodes and explicitly speed-enforcing traffic-surveillance camera nodes. Generic CCTV, speed displays and abandoned objects are excluded. Raw direction evidence is retained without inventing enforcement direction. Average-speed relations, mobile enforcement and untagged cameras are not inferred. Missing camera entries cannot be interpreted as an absence of enforcement.

Maps use the existing NavFrame schema and bundled fonts. Worldwide coordinates and extraction support do not guarantee complete typography for every writing system; the existing limited offline glyph bundle remains a rendering limitation.

All package manifests retain **© OpenStreetMap contributors** and **ODbL-1.0**, with Geofabrik provenance. Keep attribution visible in the app and when redistributing packages. Generated large data assets belong in Releases/artifacts, not Git history.

## Verified upstream constraints

Geofabrik documents its [machine-readable worldwide index](https://download.geofabrik.de/technical.html). GitHub allows [up to 1,000 release assets, each under 2 GiB](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases). Actions has [matrix/runtime limits](https://docs.github.com/en/actions/reference/limits); this workflow deliberately uses smaller batches and packages. Artifact storage is temporary and may consume account quotas; only preview runs upload package artifacts, while publication runs distribute packages directly through Releases.
