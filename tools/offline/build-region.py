#!/usr/bin/env python3
"""Build one local NavFrame region archive from an existing Geofabrik extract.

Network is not used by this script. Inputs (PBF, matching .poly and Planetiler jar)
must be supplied by the operator; output is a deterministic ZIP-compatible .navframe.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path
import re
import subprocess
import zipfile
import shutil

ROOT = Path(__file__).resolve().parents[2]
POI_BUILDER = ROOT / "tools/offline/build-pois.py"
POI_VERSION = "NavFrame POI 1.1.0"
RECIPE = "NavFrame regional pack 1.1.0"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def pmtiles_bounds(path):
    with path.open("rb") as stream:
        header = stream.read(127)
    if len(header) != 127 or header[:7] != b"PMTiles" or header[7] != 3:
        raise ValueError("Map input is not a PMTiles v3 archive")
    west, south, east, north = struct.unpack_from("<iiii", header, 102)
    result = {"south": south / 1e7, "west": west / 1e7, "north": north / 1e7, "east": east / 1e7}
    if not (result["south"] < result["north"] and result["west"] < result["east"]):
        raise ValueError("PMTiles has invalid bounds")
    return result


def bounds_for_poly(path):
    points = []
    for line in path.read_text().splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) == 2:
            try:
                points.append((float(fields[0]), float(fields[1])))
            except ValueError:
                pass
    if not points:
        raise ValueError("Coverage polygon has no coordinate pairs")
    west, south = min(x for x, _ in points), min(y for _, y in points)
    east, north = max(x for x, _ in points), max(y for _, y in points)
    return south, west, north, east


def geojson_for_poly(path):
    from coverage import read_geojson
    return read_geojson(path)


def header_timestamp(pbf, python):
    code = "import osmium,sys; r=osmium.io.Reader(sys.argv[1]); h=r.header(); print(h.get('osmosis_replication_timestamp')); r.close()"
    value = subprocess.check_output([python, "-c", code, str(pbf)], text=True).strip()
    if not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", value):
        raise ValueError("PBF header is missing its UTC replication timestamp")
    return value


def write_archive(archive, manifest, map_file, poi_file):
    archive.parent.mkdir(parents=True, exist_ok=True)
    temp = archive.with_suffix(archive.suffix + ".part")
    with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as output:
        for name, source in (("manifest.json", None), ("map.pmtiles", map_file), ("pois.sqlite", poi_file)):
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED
            if source is None:
                output.writestr(info, manifest)
            else:
                with source.open("rb") as stream, output.open(info, "w") as target:
                    while block := stream.read(1024 * 1024):
                        target.write(block)
    temp.replace(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-heap", default="2g", help="Planetiler heap (e.g. 5g for a CI runner)")
    parser.add_argument("--id", required=True, help="Stable slug, for example es-cantabria")
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True, help="Immutable region version")
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--coverage-poly", type=Path, required=True)
    parser.add_argument("--coverage-url", required=True, help="Official Geofabrik .poly URL matching this extract")
    parser.add_argument("--planetiler-jar", type=Path, help="Planetiler 0.10.2 jar; required when --map-pmtiles is omitted")
    parser.add_argument("--map-pmtiles", type=Path, help="Reuse a previously built compatible NavFrame PMTiles archive")
    parser.add_argument("--pois-sqlite", type=Path, help="Reuse a previously built NavFrame POI SQLite catalog")
    parser.add_argument("--output", type=Path, required=True, help="Target .navframe archive")
    parser.add_argument("--source-url", required=True, help="Official immutable Geofabrik extract URL")
    parser.add_argument("--python", default="python3", help="Python with tools/offline/poi-requirements.txt installed")
    parser.add_argument("--work", type=Path, help="Build workspace (defaults beside output)")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", args.id) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", args.version):
        parser.error("Invalid id or version")
    for path in (args.pbf, args.coverage_poly):
        if not path.is_file():
            parser.error(f"Missing input: {path}")
    if args.map_pmtiles is None and (args.planetiler_jar is None or not args.planetiler_jar.is_file()):
        parser.error("Provide --planetiler-jar to generate the map, or --map-pmtiles to reuse one")
    if args.map_pmtiles and not args.map_pmtiles.is_file():
        parser.error(f"Missing map input: {args.map_pmtiles}")
    if args.pois_sqlite and not args.pois_sqlite.is_file():
        parser.error(f"Missing POI input: {args.pois_sqlite}")
    work = args.work or args.output.parent / f".{args.id}-build"
    work.mkdir(parents=True, exist_ok=True)
    map_file, poi_file = work / "map.pmtiles", work / "pois.sqlite"
    south, west, north, east = bounds_for_poly(args.coverage_poly)
    osm_timestamp = header_timestamp(args.pbf, args.python)
    source_url = args.source_url
    if not re.fullmatch(r"https://download\.geofabrik\.de/[a-z0-9._/-]+\.osm\.pbf", source_url) or ".." in source_url or "//" in source_url.removeprefix("https://"):
        parser.error("Source must be a regional Geofabrik PBF URL")
    coverage_url = args.coverage_url
    if not re.fullmatch(r"https://download\.geofabrik\.de/[a-z0-9._/-]+\.poly", coverage_url) or ".." in coverage_url or "//" in coverage_url.removeprefix("https://"):
        parser.error("Coverage must be a regional Geofabrik .poly URL")
    if args.map_pmtiles:
        shutil.copyfile(args.map_pmtiles, map_file)
    else:
        schema_file = work / "navframe-region.yaml"
        schema_file.write_text((ROOT / "tools/offline/navframe.yaml").read_text().replace("build/offline/asturias.osm.pbf", str(args.pbf.resolve())))
        subprocess.run(["java", f"-Xmx{args.java_heap}", "-jar", str(args.planetiler_jar), "generate-custom",
                        f"--schema={schema_file}", f"--output={map_file}",
                        f"--bounds={west},{south},{east},{north}", f"--tmpdir={work / 'tmp'}",
                        "--force", "--download-osm-tile-weights=false"], cwd=ROOT, check=True)
    if args.pois_sqlite:
        shutil.copyfile(args.pois_sqlite, poi_file)
    else:
        subprocess.run([args.python, str(POI_BUILDER), "--pbf", str(args.pbf), "--output", str(poi_file),
                        "--osm-date", osm_timestamp, "--region", args.id, "--bounds",
                        f"{south},{west},{north},{east}", "--source-url", source_url,
                        "--coverage-poly", str(args.coverage_poly)], cwd=ROOT, check=True)
    manifest = {
        "schemaVersion": 1, "id": args.id, "name": args.name, "version": args.version,
        "osmTimestamp": osm_timestamp,
        "bounds": pmtiles_bounds(map_file),
        "minZoom": 6, "maxZoom": 14, "attribution": "© OpenStreetMap contributors",
        "mapSchema": "NavFrame Offline/1.0.0", "poiSchema": 1,
        "glyphs": "noto-sans-regular-0-2047", "capabilities": ["map", "poi"],
        "source": {"provider": "Geofabrik", "extractUrl": source_url, "pbfSha256": sha(args.pbf),
                   "coverageUrl": coverage_url, "coverageSha256": sha(args.coverage_poly),
                   "planetilerJarSha256": sha(args.planetiler_jar) if args.planetiler_jar else None,
                   "license": "ODbL-1.0", "recipe": RECIPE, "planetiler": "0.10.2", "poiBuilder": POI_VERSION},
        "coverage": geojson_for_poly(args.coverage_poly),
        "map": {"path": "map.pmtiles", "bytes": map_file.stat().st_size, "sha256": sha(map_file)},
        "pois": {"path": "pois.sqlite", "bytes": poi_file.stat().st_size, "sha256": sha(poi_file)},
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()
    write_archive(args.output, manifest_bytes, map_file, poi_file)
    catalog = {"schemaVersion": 1, "regions": [{"manifest": manifest, "archive": {
        "path": args.output.name, "bytes": args.output.stat().st_size, "sha256": sha(args.output)}}]}
    catalog_path = args.output.parent / "regional-catalog.json"
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    print(json.dumps({"archive": str(args.output), "bytes": args.output.stat().st_size,
                      "sha256": sha(args.output), "catalog": str(catalog_path), "osmTimestamp": osm_timestamp,
                      "pbfSha256": sha(args.pbf), "mapSha256": manifest["map"]["sha256"],
                      "poiSha256": manifest["pois"]["sha256"]}, indent=2))


if __name__ == "__main__":
    main()
