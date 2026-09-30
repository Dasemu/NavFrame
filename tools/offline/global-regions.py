#!/usr/bin/env python3
"""Discover/build bounded Geofabrik regions and merge immutable catalog fragments."""
import argparse
import hashlib
from datetime import date
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
from urllib.request import urlopen
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin

INDEX = 'https://download.geofabrik.de/index-v1-nogeom.json'
ROOT = Path(__file__).resolve().parents[2]


def official_url(url):
    if not re.fullmatch(r'https://download\.geofabrik\.de/[a-z0-9._/-]+', url) or '..' in url or '//' in url[8:]:
        raise ValueError('Expected official Geofabrik HTTPS URL')
    return url


def download(url, path, maximum):
    official_url(url)
    path.parent.mkdir(parents=True, exist_ok=True)
    staging = path.with_suffix(path.suffix + '.part')
    try:
        with urlopen(url, timeout=120) as source, staging.open('wb') as target:
            official_url(source.geturl())
            if int(source.headers.get('Content-Length', 0)) > maximum:
                raise ValueError('Extract exceeds input budget; select a smaller region or larger runner')
            total = 0
            while block := source.read(1024 * 1024):
                total += len(block)
                if total > maximum:
                    raise ValueError('Download exceeds configured size budget')
                target.write(block)
        staging.replace(path)
    finally:
        staging.unlink(missing_ok=True)


class ExtractChecksumError(ValueError):
    """Upstream bytes and their paired checksum do not agree."""


def verify_extract(pbf, checksum):
    fields = checksum.read_text().split()
    expected = fields[0] if fields else ''
    digest = hashlib.md5()
    with pbf.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    if not re.fullmatch(r'[a-fA-F0-9]{32}', expected) or digest.hexdigest() != expected.lower():
        raise ExtractChecksumError('Geofabrik PBF checksum mismatch')


def dated_extract(latest, html):
    """Choose newest valid dated PBF/checksum pair linked on this region's page."""
    official_url(latest)
    if not latest.endswith('-latest.osm.pbf'):
        raise ValueError('Expected latest extract URL')
    if len(html.encode('utf-8')) > 2 * 1024 * 1024:
        raise ValueError('Region HTML exceeds discovery budget')
    stem = latest.removesuffix('-latest.osm.pbf')
    page = stem + '.html'
    links = set()

    class Links(HTMLParser):
        def handle_starttag(self, tag, attributes):
            if tag.lower() != 'a':
                return
            for key, value in attributes:
                if key.lower() == 'href' and value and '..' not in value:
                    links.add(urljoin(page, value))

    parser = Links()
    parser.feed(html)
    candidates = []
    pattern = re.compile(re.escape(stem) + r'-(\d{6})\.osm\.pbf')
    for link in links:
        match = pattern.fullmatch(link)
        if match is None or link + '.md5' not in links:
            continue
        stamp = match[1]
        try:
            when = date(2000 + int(stamp[:2]), int(stamp[2:4]), int(stamp[4:6]))
        except ValueError:
            continue
        candidates.append((when, official_url(link)))
    if not candidates:
        raise ValueError('Official region page has no dated PBF/checksum pair')
    return max(candidates)[1]


def download_extract(latest, pbf, checksum, maximum):
    try:
        download(latest, pbf, maximum)
        download(latest + '.md5', checksum, 4096)
        verify_extract(pbf, checksum)
        return latest
    except (URLError, TimeoutError, ExtractChecksumError) as error:
        print(f'Latest extract unavailable ({error}); resolving official dated extract', file=sys.stderr)
        if isinstance(error, HTTPError):
            error.close()
    page = pbf.parent / 'region-source.html'
    download(latest.removesuffix('-latest.osm.pbf') + '.html', page, 2 * 1024 * 1024)
    source = dated_extract(latest, page.read_text(encoding='utf-8'))
    download(source, pbf, maximum)
    download(source + '.md5', checksum, 4096)
    verify_extract(pbf, checksum)
    return source


def regions(index):
    properties = {f['properties']['id']: f['properties'] for f in index['features']}
    result = []
    for identifier, props in sorted(properties.items()):
        source = props.get('urls', {}).get('pbf')
        if not source:
            continue
        official_url(source)
        if not source.endswith('-latest.osm.pbf'):
            raise ValueError('Unsupported Geofabrik PBF URL')
        ancestor, seen = props, set()
        while not ancestor.get('iso3166-1:alpha2') and ancestor.get('parent') in properties:
            if ancestor['id'] in seen:
                raise ValueError('Cycle in region ancestry')
            seen.add(ancestor['id'])
            ancestor = properties[ancestor['parent']]
        codes = ancestor.get('iso3166-1:alpha2', [])
        result.append({'id': identifier, 'name': props['name'], 'parent': props.get('parent'),
                       'source': source, 'coverage': source.removesuffix('-latest.osm.pbf') + '.poly',
                       'countryCode': codes[0] if len(codes) == 1 else None,
                       'country': ancestor['name'] if codes else None})
    return result


def matrix(value):
    ids = [item.strip() for item in value.split(',') if item.strip()]
    if not ids or len(ids) > 32 or len(ids) != len(set(ids)):
        raise ValueError('Select 1–32 distinct region IDs per batch')
    if any(not re.fullmatch(r'[a-z0-9]+(?:[-/][a-z0-9]+)*', item) for item in ids):
        raise ValueError('Invalid region ID')
    return {'region': ids}


def merge(paths):
    entries = {}
    for path in paths:
        catalog = json.loads(path.read_text())
        if catalog['schemaVersion'] != 1:
            raise ValueError('Unsupported catalog')
        for entry in catalog['regions']:
            key = (entry['manifest']['id'], entry['manifest']['version'])
            if key in entries and entries[key] != entry:
                raise ValueError('Conflicting immutable catalog entries')
            url = entry['archive'].get('url', '')
            if not re.fullmatch(r'https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/download/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.navframe', url):
                raise ValueError('Missing immutable GitHub package URL')
            entries[key] = entry
    return {'schemaVersion': 1, 'regions': [entries[key] for key in sorted(entries)]}


def build(args, selected):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.version):
        raise ValueError('Invalid immutable version')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        raise ValueError('Invalid repository')
    work = args.output / 'work'
    work.mkdir(parents=True, exist_ok=True)
    pbf, poly = work / 'region.osm.pbf', work / 'region.poly'
    checksum = work / 'region.osm.pbf.md5'
    source_url = download_extract(selected['source'], pbf, checksum, args.max_input_mib * 1024 * 1024)
    download(selected['coverage'], poly, 16 * 1024 * 1024)
    slug = selected['id'].replace('/', '-')
    archive = args.output / f"gf-{slug}-{args.version}.navframe"
    subprocess.run([sys.executable, str(ROOT / 'tools/offline/build-region.py'),
                    '--id', f"gf-{slug}", '--name', selected['name'], '--version', args.version,
                    '--pbf', str(pbf), '--coverage-poly', str(poly), '--coverage-url', selected['coverage'],
                    '--planetiler-jar', str(args.planetiler_jar), '--java-heap', args.java_heap,
                    '--python', sys.executable,
                    '--source-url', source_url, '--output', str(archive), '--work', str(work)], check=True)
    if archive.stat().st_size >= args.max_package_mib * 1024 * 1024:
        raise ValueError('Package exceeds publication budget; select a smaller region')
    catalog_path = args.output / 'regional-catalog.json'
    catalog = json.loads(catalog_path.read_text())
    entry = catalog['regions'][0]
    tag = f"offline-gf-{slug}-{args.version}"
    entry['archive']['url'] = f'https://github.com/{args.repository}/releases/download/{tag}/{archive.name}'
    entry.update({key: selected[key] for key in ('countryCode', 'country') if selected[key]})
    entry['region'] = selected['id']
    with sqlite3.connect(work / 'pois.sqlite') as db:
        entry['cameraCount'] = db.execute('SELECT count(*) FROM speed_cameras').fetchone()[0]
    catalog_path.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'archive': str(archive), 'catalog': str(catalog_path), 'tag': tag}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    discover = sub.add_parser('discover')
    discover.add_argument('--index', type=Path)
    discover.add_argument('--output', type=Path, required=True)
    plan = sub.add_parser('matrix')
    plan.add_argument('regions')
    combine = sub.add_parser('merge')
    combine.add_argument('catalogs', type=Path, nargs='+')
    combine.add_argument('--output', type=Path, required=True)
    builder = sub.add_parser('build')
    builder.add_argument('--region', required=True)
    builder.add_argument('--version', required=True)
    builder.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY', 'Dasemu/NavFrame'))
    builder.add_argument('--planetiler-jar', type=Path, required=True)
    builder.add_argument('--output', type=Path, required=True)
    builder.add_argument('--index', type=Path)
    builder.add_argument('--max-input-mib', type=int, default=1024)
    builder.add_argument('--max-package-mib', type=int, default=500)
    builder.add_argument('--java-heap', default='4g')
    args = parser.parse_args()
    if args.command == 'matrix':
        print(json.dumps(matrix(args.regions)))
        return
    if args.command == 'merge':
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(merge(args.catalogs), ensure_ascii=False, indent=2) + '\n')
        return
    if args.index:
        index = json.loads(args.index.read_text())
    else:
        with urlopen(INDEX, timeout=60) as response:
            index = json.load(response)
    available = regions(index)
    if args.command == 'discover':
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'schemaVersion': 1, 'sourceIndex': INDEX, 'regions': available}, ensure_ascii=False, indent=2) + '\n')
    else:
        selected = next((r for r in available if r['id'] == args.region), None)
        if selected is None:
            raise ValueError('Region not present in official Geofabrik index')
        build(args, selected)


if __name__ == '__main__':
    main()
