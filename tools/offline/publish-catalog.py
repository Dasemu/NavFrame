#!/usr/bin/env python3
"""Rebuild the mutable discovery index from immutable regional release fragments."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    repository = os.environ['GITHUB_REPOSITORY']
    # gh --paginate --slurp preserves page boundaries; avoid the default 30-release limit.
    pages = json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp',
                        f'repos/{repository}/releases?per_page=100'], text=True))
    releases = [release for page in pages for release in page]
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        fragments = []
        for release in releases:
            tag = release['tag_name']
            if release['draft'] or not tag.startswith('offline-gf-'):
                continue
            destination = root / str(release['id'])
            subprocess.run(['gh', 'release', 'download', tag, '--repo', repository,
                            '--pattern', 'regional-catalog.json', '--dir', str(destination)], check=True)
            fragments.append(destination / 'regional-catalog.json')
        if not fragments:
            raise ValueError('No published regional packages')
        spec = importlib.util.spec_from_file_location('global_regions', Path(__file__).with_name('global-regions.py'))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        combined = module.merge(fragments)
        # Browser catalog shows the newest publication per region; old URLs remain usable.
        newest = {}
        for entry in combined['regions']:
            identifier = entry['manifest']['id']
            version = entry['manifest']['version']
            key = tuple(int(part) for part in version.split('.'))
            if identifier not in newest or key > newest[identifier][0]:
                newest[identifier] = (key, entry)
        combined['regions'] = [newest[key][1] for key in sorted(newest)]
        output = root / 'regional-catalog.json'
        payload = (json.dumps(combined, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
        if len(payload) > 32 * 1024 * 1024 or len(combined['regions']) > 4096:
            raise ValueError('Catalog exceeds Android discovery limits; split the catalog before publication')
        output.write_bytes(payload)
        existing = subprocess.run(['gh', 'release', 'view', 'offline-catalog', '--repo', repository],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if existing.returncode:
            subprocess.run(['gh', 'release', 'create', 'offline-catalog', str(output), '--repo', repository,
                            '--title', 'Offline regional catalog', '--notes',
                            'Mutable package discovery index. Regional package releases are immutable.', '--latest=false'], check=True)
        else:
            subprocess.run(['gh', 'release', 'upload', 'offline-catalog', str(output), '--repo', repository,
                            '--clobber'], check=True)


if __name__ == '__main__':
    main()
