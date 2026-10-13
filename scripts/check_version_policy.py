#!/usr/bin/env python3
"""Validate PR routing and committed Android versions without executing PR code."""
import argparse
import re
import subprocess


def version_at(ref):
    content = subprocess.check_output(
        ['git', 'show', f'{ref}:app/build.gradle.kts'], text=True
    )
    names = re.findall(r'^\s*versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"\s*$', content, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', content, re.M)
    if len(names) != 1 or len(codes) != 1:
        raise ValueError('Expected one literal x.y.z versionName and one versionCode')
    return tuple(map(int, names[0])), int(codes[0])


def validate(base_branch, head_branch, old, new, old_code, new_code, major_approved=False):
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
    if new != expected:
        raise ValueError(f'Expected version {".".join(map(str, expected))}, found {".".join(map(str, new))}')
    if new_code <= old_code or not 1 <= new_code <= 2_100_000_000:
        raise ValueError('Committed versionCode must increase and remain within Android limits')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('base-ref', 'head-ref', 'base-branch', 'head-branch'):
        parser.add_argument(f'--{name}', required=True)
    parser.add_argument('--major-approved', action='store_true')
    args = parser.parse_args()
    old, old_code = version_at(args.base_ref)
    new, new_code = version_at(args.head_ref)
    try:
        validate(args.base_branch, args.head_branch, old, new, old_code, new_code, args.major_approved)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    print(f'Version policy passed: {old} -> {new}, versionCode {old_code} -> {new_code}')


if __name__ == '__main__':
    main()
