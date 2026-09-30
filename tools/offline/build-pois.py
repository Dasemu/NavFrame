#!/usr/bin/env python3
"""Original OSM regional POI extraction. Build tools only: osmium4.3.1/shapely2.1.2.
No Nominatim/Overpass requests; supplied PBF is the only input.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import unicodedata
from urllib.parse import urlsplit

BOUNDS = (42.9, -7.2, 43.75, -4.45)  # south west north east, regional coverage is Geofabrik's polygon
LABELS = {"FUEL": "Gasolinera", "WORKSHOP": "Taller", "FOOD": "Comida", "LODGING": "Alojamiento", "SHOPPING": "Comercio"}


def clean(value, limit=200):
    if not value:
        return None
    # Normalize controls/format characters to whitespace, never executable markup.
    value = "".join(" " if unicodedata.category(c) in {"Cc", "Cf", "Zl", "Zp"} else c for c in str(value))
    return " ".join(value.split())[:limit].strip() or None


def normalize(value):
    decomposed = unicodedata.normalize("NFD", value)
    letters = "".join(c for c in decomposed if not unicodedata.category(c).startswith("M"))
    return clean(letters.lower(), 1000) or ""


def category(tags):
    # Reject lifecycle-prefixed objects and explicitly inactive businesses.
    if any(tags.get(key) in {"yes", "true", "1"} for key in ["disused", "abandoned", "demolished", "razed", "construction"]):
        return None
    if any(tags.get(key, "") for key in ["disused:shop", "abandoned:shop", "disused:amenity", "abandoned:amenity", "disused:tourism", "abandoned:tourism"]):
        return None
    amenity, shop, tourism = tags.get("amenity"), tags.get("shop"), tags.get("tourism")
    if amenity == "fuel": return "FUEL"
    if shop in {"motorcycle_repair", "car_repair", "tyres"}: return "WORKSHOP"
    if tags.get("motorcycle:repair") in {"yes", "only"} or (shop == "motorcycle" and tags.get("repair") in {"yes", "only"}): return "WORKSHOP"
    if amenity in {"restaurant", "fast_food", "cafe", "food_court", "pub", "bar"}: return "FOOD"
    if tourism in {"hotel", "motel", "hostel", "guest_house", "camp_site", "caravan_site", "chalet", "apartment"}: return "LODGING"
    if shop and shop not in {"no", "vacant", "disused", "abandoned", "closed", "yes"}: return "SHOPPING"
    return None


def safe_website(value):
    value = clean(value, 500)
    if not value: return None
    if value.startswith("www."): value = "https://" + value
    try:
        uri = urlsplit(value)
        if uri.scheme.lower() not in {"https", "http"} or not uri.hostname or uri.username or uri.password or " " in value:
            return None
        if any(c in value for c in ["<", ">", '"', "\\"]): return None
        _ = uri.port
        return value
    except ValueError:
        return None


def safe_phone(value):
    value = clean(value, 100)
    return value if value and re.fullmatch(r"[+0-9 ()/;,.-]+", value) and any(c.isdigit() for c in value) else None


def address(tags):
    street = clean(tags.get("addr:street") or tags.get("addr:place"))
    number = clean(tags.get("addr:housenumber"), 30)
    postal = clean(tags.get("addr:postcode"), 20)
    city = clean(tags.get("addr:city") or tags.get("addr:town") or tags.get("addr:village"))
    return clean(", ".join(x for x in [" ".join(x for x in [street, number] if x), " ".join(x for x in [postal, city] if x)] if x), 300)


def row(osm_id, tags, lat, lon, bounds=BOUNDS, coverage=None):
    group = category(tags)
    if group is None or not math.isfinite(lat) or not math.isfinite(lon) or not (bounds[0] <= lat <= bounds[2] and bounds[1] <= lon <= bounds[3]):
        return None
    if coverage is not None and not coverage.covers(__import__("shapely.geometry", fromlist=["Point"]).Point(lon, lat)):
        return None
    name = clean(tags.get("name") or tags.get("name:es") or tags.get("brand") or tags.get("operator")) or LABELS[group] + " sin nombre"
    return (osm_id, name, normalize(name), group, lat, lon, address(tags), clean(tags.get("opening_hours"), 500),
            safe_website(tags.get("website") or tags.get("contact:website")), safe_phone(tags.get("phone") or tags.get("contact:phone")))


def camera_row(osm_id, tags, lat, lon, bounds=BOUNDS, coverage=None):
    fixed = tags.get("highway") == "speed_camera" or (
        tags.get("man_made") == "surveillance"
        and tags.get("surveillance:type") in {"camera", "ALPR"}
        and tags.get("surveillance:zone") == "traffic"
        and tags.get("enforcement") in {"maxspeed", "speed"})
    if not fixed or any(tags.get(k) in {"yes", "true", "1"} for k in ("disused", "abandoned", "demolished", "construction")):
        return None
    if not math.isfinite(lat) or not math.isfinite(lon) or not (bounds[0] <= lat <= bounds[2] and bounds[1] <= lon <= bounds[3]):
        return None
    if coverage is not None and not coverage.covers(__import__("shapely.geometry", fromlist=["Point"]).Point(lon, lat)):
        return None
    # OSM direction is ambiguously used for lens/traffic direction. Keep raw evidence;
    # do not invent an enforced travel bearing or interpret forward/backward without a way.
    return (osm_id, lat, lon, clean(tags.get("direction"), 40))


def read_poly(path):
    from coverage import read_geojson
    from shapely.geometry import shape
    geometry = shape(read_geojson(path))
    if geometry.is_empty or not geometry.is_valid:
        raise ValueError("Invalid Geofabrik polygon")
    return geometry


def build(source, target, date, region="asturias", bounds=BOUNDS, source_url="https://download.geofabrik.de/europe/spain/asturias-latest.osm.pbf", coverage_poly=None):
    import osmium
    from shapely import from_wkb
    from shapely.geometry import LineString
    coverage = read_poly(coverage_poly) if coverage_poly else None
    rows = {}
    cameras = {}
    failed_geometry = 0
    factory = osmium.geom.WKBFactory()

    class Handler(osmium.SimpleHandler):
        def node(self, node):
            tags = dict(node.tags)
            if node.location.valid():
                camera = camera_row(f"n/{node.id}", tags, node.location.lat, node.location.lon, bounds, coverage)
                if camera: cameras[camera[0]] = camera
            if category(tags) and node.location.valid():
                record = row(f"n/{node.id}", tags, node.location.lat, node.location.lon, bounds, coverage)
                if record: rows[record[0]] = record

        def way(self, way):
            nonlocal failed_geometry
            tags = dict(way.tags)
            if not category(tags) or way.is_closed(): return  # closed ways are handled as assembled areas
            try:
                coordinates = [(node.lon, node.lat) for node in way.nodes if node.location.valid()]
                if len(coordinates) < 2: raise ValueError("Incomplete geometry")
                point = LineString(coordinates).interpolate(0.5, normalized=True)
                record = row(f"w/{way.id}", tags, point.y, point.x, bounds, coverage)
                if record: rows[record[0]] = record
            except (ValueError, RuntimeError):
                failed_geometry += 1

        def area(self, area):
            nonlocal failed_geometry
            tags = dict(area.tags)
            if not category(tags): return
            try:
                shape = from_wkb(bytes.fromhex(factory.create_multipolygon(area)))
                if shape.is_empty or not shape.is_valid: raise ValueError("Invalid area")
                point = shape.representative_point()  # on-surface, not a centroid outside a concave business polygon
                record = row(f"{'w' if area.from_way() else 'r'}/{area.orig_id()}", tags, point.y, point.x, bounds, coverage)
                if record: rows[record[0]] = record
            except (ValueError, RuntimeError):
                failed_geometry += 1

    # Area callbacks assemble closed ways + multipolygon relations; nodes are cached for geometry.
    Handler().apply_file(str(source), locations=True, idx="flex_mem")
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = target.with_suffix(target.suffix + ".part")
    staging.unlink(missing_ok=True)
    db = sqlite3.connect(staging)
    try:
        db.executescript("""
            PRAGMA page_size=4096;
            PRAGMA user_version=1;
            CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
            CREATE TABLE places(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, search_name TEXT NOT NULL,
                category TEXT NOT NULL CHECK(category IN ('FUEL','WORKSHOP','FOOD','LODGING','SHOPPING')),
                latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),
                longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180),
                address TEXT, opening_hours TEXT, website TEXT, phone TEXT);
            CREATE TABLE speed_cameras(id TEXT PRIMARY KEY NOT NULL, latitude REAL NOT NULL CHECK(latitude BETWEEN -90 AND 90),
                longitude REAL NOT NULL CHECK(longitude BETWEEN -180 AND 180), direction TEXT);
            CREATE INDEX speed_cameras_position ON speed_cameras(latitude,longitude);
            CREATE INDEX places_category_position ON places(category,latitude,longitude);
            CREATE INDEX places_position ON places(latitude,longitude);
            CREATE INDEX places_search_name ON places(search_name);
        """)
        # Ten columns, all values parameterized; apostrophes and SQL wildcard characters remain data.
        db.executemany("INSERT INTO places VALUES(?,?,?,?,?,?,?,?,?,?)", sorted(rows.values(), key=lambda value: value[0]))
        db.executemany("INSERT INTO speed_cameras VALUES(?,?,?,?)", sorted(cameras.values()))
        metadata = {
            "camera_schema": "1", "camera_count": str(len(cameras)), "camera_recipe": "NavFrame fixed cameras 1.1.0",
            "schema_version": "1", "region": region, "source": "OpenStreetMap / Geofabrik",
            "source_url": source_url,
            "osm_timestamp": date, "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "bounds": json.dumps(bounds, separators=(",", ":")), "attribution": "© OpenStreetMap contributors",
            "license": "ODbL-1.0", "recipe": "NavFrame POI 1.1.0", "count": str(len(rows)),
            "failed_geometry": str(failed_geometry), "categories": json.dumps(LABELS, ensure_ascii=False, sort_keys=True),
        }
        db.executemany("INSERT INTO metadata VALUES(?,?)", sorted(metadata.items()))
        db.commit()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        db.execute("VACUUM")
        print(json.dumps({"count": len(rows), "categories": dict(db.execute("SELECT category,count(*) FROM places GROUP BY category")), "failed_geometry": failed_geometry}, ensure_ascii=False))
    finally:
        db.close()
    staging.replace(target)
    print(f"bytes={target.stat().st_size} sha256={hashlib.sha256(target.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--osm-date", required=True, help="UTC timestamp read from this source's PBF header")
    parser.add_argument("--region", default="asturias")
    parser.add_argument("--bounds", default="42.9,-7.2,43.75,-4.45", help="south,west,north,east")
    parser.add_argument("--source-url", default="https://download.geofabrik.de/europe/spain/asturias-latest.osm.pbf")
    parser.add_argument("--coverage-poly", type=Path, help="Geofabrik .poly region boundary; restricts catalog to actual coverage")
    args = parser.parse_args()
    bounds = tuple(float(value) for value in args.bounds.split(","))
    if len(bounds) != 4 or not all(math.isfinite(value) for value in bounds) or not (bounds[0] < bounds[2] and bounds[1] < bounds[3]):
        parser.error("Invalid bounds")
    build(args.pbf, args.output, args.osm_date, args.region, bounds, args.source_url, args.coverage_poly)
