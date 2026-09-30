import importlib.util
import json
import hashlib
from unittest.mock import patch
from urllib.error import HTTPError
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


class DatedExtractTest(unittest.TestCase):
    latest = 'https://download.geofabrik.de/europe/andorra-latest.osm.pbf'
    dated = 'https://download.geofabrik.de/europe/andorra-260929.osm.pbf'
    html = '<a href="andorra-260929.osm.pbf">PBF</a><a href="andorra-260929.osm.pbf.md5">MD5</a>'

    def test_selects_newest_valid_matching_pair(self):
        html = self.html + '<a href="andorra-260928.osm.pbf">old</a><a href="andorra-260928.osm.pbf.md5">old md5</a>'
        html += '<a href="andorra-261332.osm.pbf">invalid</a><a href="andorra-261332.osm.pbf.md5">md5</a>'
        html += '<a href="andorra-261001.osm.pbf">no checksum</a>'
        html += '<a href="https://evil.example/andorra-261002.osm.pbf">evil</a><a href="https://evil.example/andorra-261002.osm.pbf.md5">evil md5</a>'
        html += '<a href="france-261002.osm.pbf">other region</a><a href="france-261002.osm.pbf.md5">other md5</a>'
        self.assertEqual(global_regions.dated_extract(self.latest, html), self.dated)
        with self.assertRaises(ValueError):
            global_regions.dated_extract(self.latest, '<a href="andorra-261001.osm.pbf">no checksum</a>')
        with self.assertRaises(ValueError):
            global_regions.dated_extract(self.latest, 'x' * (2 * 1024 * 1024 + 1))

    def test_redirect_loop_fallback_downloads_exact_pair(self):
        requested = []
        content = b'OSM test bytes'
        def fake_download(url, path, maximum):
            requested.append(url)
            if url == self.latest:
                raise HTTPError(url, 301, 'redirect loop', {}, None)
            if url.endswith('.html'):
                path.write_text(self.html)
            elif url == self.dated:
                path.write_bytes(content)
            elif url == self.dated + '.md5':
                path.write_text(hashlib.md5(content).hexdigest() + '  andorra-260929.osm.pbf')
            else:
                self.fail('Unexpected download URL: ' + url)
        with tempfile.TemporaryDirectory() as temporary, patch.object(global_regions, 'download', side_effect=fake_download):
            root = Path(temporary)
            actual = global_regions.download_extract(self.latest, root / 'region.osm.pbf', root / 'checksum.md5', 4096)
        self.assertEqual(actual, self.dated)
        self.assertEqual(requested[-2:], [self.dated, self.dated + '.md5'])

    def test_size_budget_error_does_not_fallback(self):
        with patch.object(global_regions, 'download', side_effect=ValueError('input budget')) as downloader:
            with self.assertRaises(ValueError):
                global_regions.download_extract(self.latest, Path('unused'), Path('unused.md5'), 1)
            self.assertEqual(downloader.call_count, 1)

    def test_wrong_dated_checksum_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pbf, checksum = root / 'region.osm.pbf', root / 'checksum.md5'
            pbf.write_bytes(b'test')
            checksum.write_text('0' * 32)
            with self.assertRaises(global_regions.ExtractChecksumError):
                global_regions.verify_extract(pbf, checksum)
