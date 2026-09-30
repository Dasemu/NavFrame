"""Original extraction tests, plus integrity checks on the generated real Asturias asset."""
import importlib.util
import sqlite3
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("build_pois", Path(__file__).with_name("build-pois.py"))
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)


class PoiPipelineTest(unittest.TestCase):
    def test_categories_do_not_claim_every_motorcycle_shop_repairs(self):
        self.assertEqual("FUEL", pipeline.category({"amenity": "fuel"}))
        self.assertEqual("SHOPPING", pipeline.category({"shop": "motorcycle", "motorcycle:repair": "no"}))
        self.assertEqual("WORKSHOP", pipeline.category({"shop": "motorcycle", "motorcycle:repair": "yes"}))
        self.assertEqual("WORKSHOP", pipeline.category({"shop": "motorcycle_repair"}))
        self.assertEqual("WORKSHOP", pipeline.category({"shop": "car_repair"}))
        self.assertEqual("LODGING", pipeline.category({"tourism": "camp_site"}))
        self.assertIsNone(pipeline.category({"shop": "bakery", "disused": "yes"}))
        self.assertIsNone(pipeline.category({"shop": "bakery", "abandoned:shop": "bakery"}))
        self.assertIsNone(pipeline.category({"shop": "vacant"}))

    def test_metadata_is_optional_and_links_are_sanitized(self):
        self.assertEqual("cafe alvaro", pipeline.normalize("  CAFÉ\u00a0ÁLVARO\n"))
        self.assertEqual("a b", pipeline.normalize("A\u200bB"))
        self.assertEqual("καφες", pipeline.normalize("Καφές"))
        self.assertEqual("https://www.example.org", pipeline.safe_website("www.example.org"))
        for url in ["javascript:alert(1)", "file:///tmp/x", "https://user:secret@example.org", "http://example.org:bad", "https://example.org/\npath"]:
            self.assertIsNone(pipeline.safe_website(url))
        self.assertIsNone(pipeline.safe_phone("javascript:123"))
        self.assertEqual("+34 985 123 456", pipeline.safe_phone("+34 985 123 456"))
        record = pipeline.row("n/1", {"amenity": "fuel"}, 43.36, -5.85)
        self.assertEqual("Gasolinera sin nombre", record[1])
        self.assertIsNone(record[6])
        self.assertIsNone(record[7])
        self.assertIsNone(pipeline.row("n/1", {"amenity": "fuel"}, 0.0, 0.0))

    def test_real_catalog_integrity_counts_identity_and_utf8(self):
        target = Path(__file__).parents[2] / "app/src/main/assets/offline/asturias-pois.sqlite"
        with sqlite3.connect(f"file:{target}?mode=ro", uri=True) as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual(1, db.execute("PRAGMA user_version").fetchone()[0])
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            self.assertEqual("ODbL-1.0", metadata["license"])
            self.assertEqual("2026-09-28T20:23:05Z", metadata["osm_timestamp"])
            self.assertEqual("1ab5967cc162027f0d05b2b603b65ad3f5d3b5466551613054cf5e6b28b9eb5d", metadata["source_sha256"])
            self.assertEqual(int(metadata["count"]), db.execute("SELECT COUNT(*) FROM places").fetchone()[0])
            self.assertEqual(set(pipeline.LABELS), {row[0] for row in db.execute("SELECT DISTINCT category FROM places")})
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM places WHERE latitude NOT BETWEEN 42.9 AND 43.75 OR longitude NOT BETWEEN -7.2 AND -4.45").fetchone()[0])
            seen = set()
            for osm_id, name, search_name, lat, lon in db.execute("SELECT id,name,search_name,latitude,longitude FROM places"):
                self.assertRegex(osm_id, r"^[nwr]/[1-9][0-9]*$")
                self.assertNotIn(osm_id, seen)
                seen.add(osm_id)
                self.assertEqual(pipeline.normalize(name), search_name)
            self.assertGreater(db.execute("SELECT COUNT(*) FROM places WHERE name LIKE '%é%' OR name LIKE '%ñ%'").fetchone()[0], 100)

    def test_build_extracts_original_fixture_nodes_closed_ways_and_relation_areas(self):
        # Synthetic local coordinates, original OSM fixture. No user/GPS data or claimed real businesses.
        xml = '''<?xml version="1.0"?><osm version="0.6" generator="NavFrame test">
          <node id="1" lat="43.36" lon="-5.85"><tag k="amenity" v="fuel"/><tag k="name" v="Fuel fixture"/></node>
          <node id="2" lat="43.3600" lon="-5.8500"/><node id="3" lat="43.3600" lon="-5.8499"/>
          <node id="4" lat="43.3601" lon="-5.8499"/><node id="5" lat="43.3601" lon="-5.8500"/>
          <way id="1"><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="5"/><nd ref="2"/><tag k="amenity" v="cafe"/><tag k="name" v="O'Connor %_ fixture"/></way>
          <way id="2"><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="5"/><nd ref="2"/></way>
          <relation id="1"><member type="way" ref="2" role="outer"/><tag k="type" v="multipolygon"/><tag k="tourism" v="hotel"/><tag k="name" v="Hotel fixture"/></relation>
        </osm>'''
        with tempfile.TemporaryDirectory() as folder:
            source, target = Path(folder) / "input.osm", Path(folder) / "places.sqlite"
            source.write_text(xml)
            pipeline.build(source, target, "2026-09-28T20:23:05Z")
            with sqlite3.connect(target) as db:
                self.assertEqual({"n/1", "w/1", "r/1"}, {row[0] for row in db.execute("SELECT id FROM places")})
                self.assertEqual("O'Connor %_ fixture", db.execute("SELECT name FROM places WHERE id='w/1'").fetchone()[0])
                self.assertEqual(0, db.execute("SELECT COUNT(*) FROM places WHERE latitude NOT BETWEEN 43.36 AND 43.3601 OR longitude NOT BETWEEN -5.85 AND -5.8499").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
