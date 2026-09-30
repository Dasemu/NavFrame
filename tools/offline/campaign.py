#!/usr/bin/env python3
"""Freeze global leaf batches and resolve campaign tags without network discovery."""
import argparse
import hashlib
import json
from pathlib import Path
import re

PREFIX = 'offline-global-2026-09-batch-'
DEFAULT = Path(__file__).with_name('campaign-global-2026-09.json')


def create(sources, catalog, source_bytes, catalog_bytes):
    rows = sources['regions']
    ids = [row['id'] for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate source IDs')
    parents = {row['parent'] for row in rows if row.get('parent')}
    published = {entry.get('region') for entry in catalog['regions'] if entry.get('region')}
    published_packages = {entry['manifest']['id'] for entry in catalog['regions']}
    excluded = sorted(identifier for identifier in ids if identifier in published or 'gf-' + identifier.replace('/', '-') in published_packages)
    selected = sorted(set(ids) - parents - set(excluded))
    slugs = [identifier.replace('/', '-') for identifier in selected]
    if len(slugs) != len(set(slugs)):
        raise ValueError('Package slug collision')
    return {'schemaVersion': 1, 'campaign': 'global-2026-09',
            'sourceSha256': hashlib.sha256(source_bytes).hexdigest(),
            'catalogSha256': hashlib.sha256(catalog_bytes).hexdigest(),
            'excludedParents': sorted(set(ids) & parents), 'excludedPublished': excluded,
            'batches': [{'tag': PREFIX + f'{i // 32 + 1:03d}', 'regions': selected[i:i + 32]}
                        for i in range(0, len(selected), 32)]}


def select(campaign, tag):
    if campaign.get('schemaVersion') != 1 or campaign.get('campaign') != 'global-2026-09':
        raise ValueError('Unsupported campaign')
    all_ids, all_tags = [], []
    selected = None
    for batch in campaign['batches']:
        ids = batch['regions']
        if not 1 <= len(ids) <= 32 or any(not re.fullmatch(r'[a-z0-9]+(?:[-/][a-z0-9]+)*', item) for item in ids):
            raise ValueError('Invalid campaign batch')
        if not re.fullmatch(re.escape(PREFIX) + r'\d{3}', batch['tag']):
            raise ValueError('Invalid campaign tag')
        all_ids.extend(ids)
        all_tags.append(batch['tag'])
        if batch['tag'] == tag:
            selected = ids
    if len(all_ids) != len(set(all_ids)) or len(all_tags) != len(set(all_tags)):
        raise ValueError('Duplicate campaign region or tag')
    if set(all_ids) & (set(campaign['excludedParents']) | set(campaign['excludedPublished'])):
        raise ValueError('Campaign includes excluded region')
    if selected is None:
        raise ValueError('Tag is not present in frozen campaign')
    return {'region': selected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    build = sub.add_parser('create')
    build.add_argument('--sources', type=Path, default=Path(__file__).with_name('worldwide-sources.json'))
    build.add_argument('--catalog', type=Path, required=True)
    build.add_argument('--output', type=Path, default=DEFAULT)
    matrix = sub.add_parser('matrix')
    matrix.add_argument('tag')
    matrix.add_argument('--campaign', type=Path, default=DEFAULT)
    args = parser.parse_args()
    if args.command == 'create':
        source_bytes, catalog_bytes = args.sources.read_bytes(), args.catalog.read_bytes()
        result = create(json.loads(source_bytes), json.loads(catalog_bytes), source_bytes, catalog_bytes)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    else:
        print(json.dumps(select(json.loads(args.campaign.read_text()), args.tag)))


if __name__ == '__main__':
    main()
