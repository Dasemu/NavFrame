import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('pois', Path(__file__).with_name('build-pois.py'))
pois = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pois)
class CameraExtractionTest(unittest.TestCase):
    def test_international_speed_surveillance(self):
        tags = {'man_made': 'surveillance', 'surveillance:type': 'camera', 'surveillance:zone': 'traffic', 'enforcement': 'maxspeed'}
        self.assertIsNotNone(pois.camera_row('n/2', tags, -35, 149, (-36,148,-34,150)))
        self.assertIsNone(pois.camera_row('n/2', {'man_made': 'surveillance', 'surveillance:type': 'camera'}, -35,149,(-36,148,-34,150)))

    def test_fixed_nodes_only(self):
        self.assertEqual(pois.camera_row('n/1', {'highway':'speed_camera','direction':'forward'},43,-5), ('n/1',43,-5,'forward'))
        self.assertIsNone(pois.camera_row('n/1', {'highway':'speed_camera','disused':'yes'},43,-5))
        self.assertIsNone(pois.camera_row('n/1', {'highway':'speed_display'},43,-5))
        self.assertIsNone(pois.camera_row('n/1', {'highway':'speed_camera'},float('nan'),-5))
        self.assertIsNone(pois.camera_row('n/1', {'highway':'speed_camera'},40,-5))
if __name__ == '__main__': unittest.main()
