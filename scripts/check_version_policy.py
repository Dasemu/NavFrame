#!/usr/bin/env python3
"""Validate PR routing and committed Android versions without executing PR code.

With --write, apply the required versionName, versionCode and README version
to the local working tree instead, so a contributor never computes them by hand.
"""
import argparse
import re
import subprocess
from pathlib import Path

GRADLE = 'app/build.gradle.kts'
README = 'README.md'
NAME_RE = re.compile(r'^(\s*versionName\s*=\s*)"(\d+)\.(\d+)\.(\d+)"(\s*)$', re.M)
CODE_RE = re.compile(r'^(\s*versionCode\s*=\s*)(\d+)(\s*)$', re.M)
README_RE = re.compile(r'(The current checkout is version )\d+\.\d+\.\d+\.')


def parse_version(content):
    names = NAME_RE.findall(content)
    codes = CODE_RE.findall(content)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError('Expected one literal x.y.z versionName and one versionCode')
    return tuple(map(int, names[0][1:4])), int(codes[0][1])


def version_at(ref):
    return parse_version(subprocess.check_output(['git', 'show', f'{ref}:{GRADLE}'], text=True))


def expected_version(base_branch, head_branch, old, major_approved=False):
    if base_branch == 'main' and head_branch == 'development':
        expected = (old[0], old[1] + 1, old[2])
    elif base_branch == 'main' and head_branch.startswith('hotfix/'):
        expected = (old[0], old[1], old[2] + 1)
    elif base_branch == 'development' and head_branch.startswith(('feat/', 'fix/')):
        expected = (old[0], old[1], old[2] + 1)
    else:
        raise ValueError('PRs must be feat/* or fix/* -> development, hotfix/* -> main, or development -> main')
    if major_approved:
        expected = (old[0] + 1, old[1], old[2])
    return expected


def fmt(version):
    return '.'.join(map(str, version))


def validate(base_branch, head_branch, old, new, old_code, new_code, major_approved=False):
    expected = expected_version(base_branch, head_branch, old, major_approved)
    if new != expected:
        raise ValueError(
            f'Expected version {fmt(expected)}, found {fmt(new)}. Fix it with: '
            f'python3 scripts/check_version_policy.py --write --base-ref origin/{base_branch} '
            f'--base-branch {base_branch} --head-branch {head_branch}'
        )
    if new_code <= old_code or not 1 <= new_code <= 2_100_000_000:
        raise ValueError(f'Committed versionCode must increase above {old_code} and remain within Android limits')


def apply_version(gradle, readme, version, code):
    """Return gradle and README text carrying the given versionName and versionCode."""
    gradle = NAME_RE.sub(lambda m: f'{m[1]}"{fmt(version)}"{m[5]}', gradle)
    gradle = CODE_RE.sub(lambda m: f'{m[1]}{code}{m[3]}', gradle)
    return gradle, README_RE.sub(lambda m: f'{m[1]}{fmt(version)}.', readme)


def write(args):
    old, old_code = version_at(args.base_ref)
    gradle_path, readme_path = Path(GRADLE), Path(README)
    gradle, readme = gradle_path.read_text(), readme_path.read_text()
    _, head_code = parse_version(gradle)
    version = expected_version(args.base_branch, args.head_branch, old, args.major_approved)
    code = head_code if head_code > old_code else old_code + 1
    gradle, readme = apply_version(gradle, readme, version, code)
    gradle_path.write_text(gradle)
    readme_path.write_text(readme)
    print(f'Wrote versionName {fmt(version)} and versionCode {code} ({args.base_branch} has {fmt(old)}, {old_code})')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ('base-ref', 'base-branch', 'head-branch'):
        parser.add_argument(f'--{name}', required=True)
    parser.add_argument('--head-ref', help='required unless --write')
    parser.add_argument('--major-approved', action='store_true')
    parser.add_argument('--write', action='store_true', help='update the working tree instead of validating')
    args = parser.parse_args()
    try:
        if args.write:
            write(args)
            return
        if not args.head_ref:
            parser.error('--head-ref is required unless --write is given')
        old, old_code = version_at(args.base_ref)
        new, new_code = version_at(args.head_ref)
        validate(args.base_branch, args.head_branch, old, new, old_code, new_code, args.major_approved)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f'Version policy passed: {fmt(old)} -> {fmt(new)}, versionCode {old_code} -> {new_code}')


if __name__ == '__main__':
    main()
