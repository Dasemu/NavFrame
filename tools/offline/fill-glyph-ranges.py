#!/usr/bin/env python3
"""Write an empty glyph PBF for every Unicode BMP range missing from a font stack directory.

MapLibre's MapSnapshotter aborts a whole still image when any glyph range fails to load,
whereas an empty range only omits the uncovered characters. Real ranges are never replaced.
"""
import pathlib
import sys


def varint(value: int) -> bytes:
    out = bytearray()
    while value >= 0x80:
        out.append(value & 0x7F | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def field(number: int, payload: bytes) -> bytes:
    return varint(number << 3 | 2) + varint(len(payload)) + payload


def empty_range(font_stack: str, glyph_range: str) -> bytes:
    return field(1, field(1, font_stack.encode()) + field(2, glyph_range.encode()))


def main(directory: str) -> None:
    folder = pathlib.Path(directory)
    if not folder.is_dir():
        sys.exit(f"Not a font stack directory: {folder}")
    for start in range(0, 65536, 256):
        name = f"{start}-{start + 255}"
        target = folder / f"{name}.pbf"
        if not target.exists():
            target.write_bytes(empty_range(folder.name, name))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: fill-glyph-ranges.py FONT_STACK_DIRECTORY")
    main(sys.argv[1])
