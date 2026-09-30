import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('campaign', Path(__file__).with_name('campaign.py'))
campaign = importlib.util.module_from_spec(spec)
spec.loader.exec_module(campaign)


class CampaignTest(unittest.TestCase):
    def test_reproducible_leaf_selection_and_published_exclusion(self):
        rows = [{'id': 'parent', 'parent': None}, {'id': 'child', 'parent': 'parent'},
                {'id': 'published', 'parent': None}] + [{'id': f'r{i:03}', 'parent': None} for i in range(33)]
        catalog = {'regions': [{'manifest': {'id': 'gf-published'}}]}
        result = campaign.create({'regions': rows}, catalog, b'sources', b'catalog')
        self.assertEqual(result, campaign.create({'regions': list(reversed(rows))}, catalog, b'sources', b'catalog'))
        self.assertEqual(result['excludedParents'], ['parent'])
        self.assertEqual(result['excludedPublished'], ['published'])
        self.assertEqual([len(batch['regions']) for batch in result['batches']], [32, 2])
        self.assertEqual(campaign.select(result, campaign.PREFIX + '002')['region'], ['r031', 'r032'])
        with self.assertRaises(ValueError):
            campaign.select(result, campaign.PREFIX + '003')
        result['batches'][1]['regions'].append('child')
        with self.assertRaises(ValueError):
            campaign.select(result, campaign.PREFIX + '001')

    def test_frozen_manifest_is_bounded_disjoint_and_matches_sources(self):
        root = Path(__file__).parent
        frozen = json.loads((root / 'campaign-global-2026-09.json').read_text())
        sources = json.loads((root / 'worldwide-sources.json').read_text())
        parents = {row['parent'] for row in sources['regions']}
        expected = {row['id'] for row in sources['regions']} - parents - set(frozen['excludedPublished'])
        actual = []
        for batch in frozen['batches']:
            actual.extend(campaign.select(frozen, batch['tag'])['region'])
        self.assertEqual(set(actual), expected)
        self.assertEqual(len(actual), len(expected))
        import hashlib
        self.assertEqual(frozen['sourceSha256'], hashlib.sha256((root / 'worldwide-sources.json').read_bytes()).hexdigest())
