"""Writes flat-colour 16x16 placeholder textures (standard library only). Final art comes later.

Usage: python tools/make_placeholder_textures.py
"""
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "assets" / "jasm" / "textures"

# Tier colours (Catppuccin Mocha): brown, green, blue, purple, gold, lowest tier first.
BROWN = (201, 163, 141)
GREEN = (166, 227, 161)
BLUE = (137, 180, 250)
PURPLE = (203, 166, 247)
GOLD = (249, 226, 175)

# name -> (folder, RGB)
TEXTURES = {
    "capacity_wafer_basic": ("item", BROWN),
    "capacity_wafer_1k": ("item", GREEN),
    "capacity_wafer_4k": ("item", BLUE),
    "capacity_wafer_16k": ("item", PURPLE),
    "capacity_wafer_64k": ("item", GOLD),
    "starter_deck": ("item", BROWN),
    "basic_deck": ("item", GREEN),
    "advanced_deck": ("item", BLUE),
    "elite_deck": ("item", PURPLE),
    "ultimate_deck": ("item", GOLD),
    "basic_archive": ("block", BLUE),
    "advanced_archive": ("block", PURPLE),
    "ultimate_archive": ("block", GOLD),
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
