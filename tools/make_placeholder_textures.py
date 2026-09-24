"""Writes flat-colour 16x16 placeholder textures (standard library only). Final art comes later.

Usage: python tools/make_placeholder_textures.py
"""
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "jasm" / "textures"

# name -> (folder, RGB). Wafer tiers get a hue ramp; Decks and Archives get their own ramps.
TEXTURES = {
    "capacity_wafer_basic": ("item", (150, 150, 150)),
    "capacity_wafer_1k": ("item", (80, 170, 90)),
    "capacity_wafer_4k": ("item", (70, 130, 200)),
    "capacity_wafer_16k": ("item", (160, 90, 200)),
    "capacity_wafer_64k": ("item", (220, 170, 60)),
    "starter_deck": ("item", (120, 100, 80)),
    "basic_deck": ("item", (90, 150, 110)),
    "advanced_deck": ("item", (80, 110, 180)),
    "ultimate_deck": ("item", (190, 150, 60)),
    "basic_archive": ("block", (90, 120, 140)),
    "advanced_archive": ("block", (70, 90, 160)),
    "ultimate_archive": ("block", (150, 120, 60)),
    "creative_battery": ("block", (200, 60, 160)),
}


def png(rgb, size=16):
    border = tuple(max(0, c - 60) for c in rgb)
    rows = b""
    for y in range(size):
        row = b"\x00"
        for x in range(size):
            edge = x in (0, size - 1) or y in (0, size - 1)
            row += bytes(border if edge else rgb) + b"\xff"
        rows += row

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


for name, (folder, rgb) in TEXTURES.items():
    path = ROOT / folder / f"{name}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png(rgb))
    print(path.relative_to(ROOT.parent.parent.parent.parent.parent.parent))
