import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from coverage import read_geojson

spec = importlib.util.spec_from_file_location('global_regions', Path(__file__).with_name('global-regions.py'))
global_regions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(global_regions)


class GlobalPipelineTest(unittest.TestCase):
    def test_global_index_and_ancestry(self):
        index = {'features': [{'properties': {'id': 'australia', 'name': 'Australia', 'iso3166-1:alpha2': ['AU'], 'urls': {'pbf': 'https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf'}}},
                              {'properties': {'id': 'act', 'name': 'ACT', 'parent': 'australia', 'urls': {'pbf': 'https://download.geofabrik.de/australia-oceania/australia/act-latest.osm.pbf'}}}]}
        region = global_regions.regions(index)[0]
        self.assertEqual(region['countryCode'], 'AU')
        self.assertEqual(region['coverage'], 'https://download.geofabrik.de/australia-oceania/australia/act.poly')

    def test_matrix_limits_and_injection(self):
        self.assertEqual(global_regions.matrix('act,us/california'), {'region': ['act', 'us/california']})
        for value in ('', 'act,act', '$(id)', ','.join('r' + str(i) for i in range(33))):
            with self.assertRaises(ValueError):
                global_regions.matrix(value)

    def test_untrusted_sources(self):
        for url in ('http://download.geofabrik.de/a.poly', 'https://evil.example/a.poly', 'https://download.geofabrik.de/../a.poly', 'https://download.geofabrik.de//a.poly'):
            with self.assertRaises(ValueError):
                global_regions.official_url(url)

    def test_polygon_islands_holes_and_final_end(self):
        text = 'example\n1\n0 0\n4 0\n4 4\n0 0\nEND\n!1\n1 1\n2 1\n2 2\n1 1\nEND\n2\n10 10\n11 10\n11 11\n10 10\nEND\nEND\n'
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'region.poly'
            path.write_text(text)
            geometry = read_geojson(path)
            self.assertEqual(geometry['type'], 'MultiPolygon')
            self.assertEqual([len(p) for p in geometry['coordinates']], [2, 1])
            path.write_text('bad\n1\n0 0\n1 0\n1 1\nEND\nEND\n')
            with self.assertRaises(ValueError):
                read_geojson(path)

    def test_merge_conflicting_immutable_entries(self):
        entry = {'manifest': {'id': 'gf-act', 'version': '1.1'}, 'archive': {'url': 'https://github.com/Dasemu/NavFrame/releases/download/offline-gf-act-1.1/gf-act-1.1.navframe', 'sha256': 'a'}}
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / '1.json', Path(temporary) / '2.json'
            first.write_text(json.dumps({'schemaVersion': 1, 'regions': [entry]}))
            second.write_text(first.read_text())
            self.assertEqual(len(global_regions.merge([first, second])['regions']), 1)
            entry['archive']['sha256'] = 'b'
            second.write_text(json.dumps({'schemaVersion': 1, 'regions': [entry]}))
            with self.assertRaises(ValueError):
                global_regions.merge([first, second])
