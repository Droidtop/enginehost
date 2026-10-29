#!/usr/bin/env python3
"""Prints a screenshot the core harness wrote as a coarse grid of characters, dark to light.

For reading a run's screens from its log alone, where the artifact cannot be downloaded
(docs/core-harness.md). Reads the plain RGBA PNGs core_harness.write_png makes (no row filters).
    screen_text.py first-frame.png [columns]
"""
import struct
import sys
import zlib

SHADES = " .:-=+*#%@"


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    at, width, height, idat = 8, 0, 0, b""
    while at < len(data):
        length, kind = struct.unpack_from(">I4s", data, at)
        body = data[at + 8:at + 8 + length]
        if kind == b"IHDR":
            width, height = struct.unpack_from(">II", body, 0)
        elif kind == b"IDAT":
            idat += body
        at += 12 + length
    raw = zlib.decompress(idat)
    stride = width * 4 + 1
    return width, height, [raw[y * stride + 1:(y + 1) * stride] for y in range(height)]


def main(argv):
    width, height, rows = read_png(argv[1])
    columns = int(argv[2]) if len(argv) > 2 else 32
    # Characters are about twice as tall as wide.
    lines = max(1, columns * height // width // 2)
    for line in range(lines):
        y = line * height // lines
        out = []
        for column in range(columns):
            x = column * width // columns
            r, g, b = rows[y][x * 4], rows[y][x * 4 + 1], rows[y][x * 4 + 2]
            luma = (r * 299 + g * 587 + b * 114) // 1000
            out.append(SHADES[luma * (len(SHADES) - 1) // 255])
        print("|" + "".join(out) + "|")
    print("%dx%d" % (width, height))


if __name__ == "__main__":
    main(sys.argv)
