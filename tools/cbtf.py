#!/usr/bin/env python3
"""Reads a .cbtf, the bitmap font format of BlackBerry OS 7 themes (BannerTime.cbtf draws the
time of the home banner), as far as it is understood: its glyphs' metrics, not their pixels.

  python3 -I tools/cbtf.py <file.cbtf> [characters]

prints, for each character (default: those of the font), its advance, its box and where the box
sits against the baseline, in pixels of the 9900.

The container, read on the 25 .cbtf of the 9900's default theme (docs/BBOS7-VISUEL.md, BannerTime):

  "cbtf", u16 10 (version), u16 2, u16 1, u16 n, then n entries (u16 id, u32 offset);
  section 0: u16 1, u32 length, then the font's name in UTF-16BE (u16 8 before it);
  section 1: u8 0, u16 0x0800, u16 number of glyphs;
  section 2: font metrics (line height, ascent...), 14 bytes, not needed here;
  section 3: u16 offset of each glyph in section 5;
  section 4: u8 0, u16 number of ranges, then per range u16 first, u16 end (excluded), u16
    count and a bitmask of (end - first) bits, most significant first: the characters present,
    whose glyphs follow in order;
  section 5: the glyphs, each 6 bytes (u8 0, i8 top, u8 advance, u8 width, u8 height, u8 left
    bearing) then its pixels;
  section 6: u8 0x20, u8 palette size, then that many ARGB entries (white at varying alpha).

`top` is signed, down positive, from a line that is not the baseline and whose rule is not
found in section 2: on BannerTime the box's top is `top - 5` from the baseline (every flat glyph,
1, 4, 7, A, :, then ends exactly on it, the round ones 1 px under it); on LockBannerTime, `top - 2`.

Where it stops: the pixels are a bit stream (most significant bit first) whose code is not
identified. Transparent runs show as a repeated 011, opaque areas as repeated 8-bit units
(00001101, 00000101 in every rotation); it is neither raw indices into the palette (5 bits, the
palette's 0x20) nor a plain run-length code of them. The firmware's decoder is native code of
the OS, not in the theme.
"""
import struct
import sys
from typing import NamedTuple

class Glyph(NamedTuple):
    top: int  # as stored: see the docstring
    advance: int
    width: int
    height: int
    left: int
    pixels: bytes  # still encoded


def parse(data):
    """Returns (name, {character: Glyph}). Raises ValueError on anything unexpected."""
    if data[:4] != b'cbtf':
        raise ValueError('not a cbtf file')
    count = struct.unpack_from('>H', data, 10)[0]
    sections = dict(struct.unpack_from('>HI', data, 12 + 6 * i) for i in range(count))
    if not {0, 1, 3, 4, 5}.issubset(sections):
        raise ValueError(f'missing sections: {sorted(sections)}')
    s0 = sections[0]
    length = struct.unpack_from('>I', data, s0 + 2)[0]
    name = data[s0 + 8:s0 + 8 + 2 * length].decode('utf-16-be')
    glyphs = struct.unpack_from('>H', data, sections[1] + 3)[0]
    offsets = struct.unpack_from(f'>{glyphs}H', data, sections[3])
    glyph_end = min(o for o in sections.values() if o > sections[5])
    chars = []
    pos = sections[4] + 1
    ranges = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    for _ in range(ranges):
        first, end, n = struct.unpack_from('>HHH', data, pos)
        pos += 6
        mask = data[pos:pos + (end - first + 7) // 8]
        pos += len(mask)
        present = [first + i for i in range(end - first) if mask[i // 8] >> (7 - i % 8) & 1]
        if len(present) != n:
            raise ValueError(f'range {first:#x}: {len(present)} characters in the mask, {n} announced')
        chars += present
    if len(chars) != glyphs:
        raise ValueError(f'{len(chars)} characters for {glyphs} glyphs')
    out = {}
    for i, c in enumerate(chars):
        start = sections[5] + offsets[i]
        end = sections[5] + offsets[i + 1] if i + 1 < glyphs else glyph_end
        _, top, advance, width, height, left = struct.unpack_from('>BbBBBB', data, start)
        out[chr(c)] = Glyph(top, advance, width, height, left, data[start + 6:end])
    return name, out


def main(path, wanted=None):
    with open(path, 'rb') as f:
        name, glyphs = parse(f.read())
    print(f'{name}: {len(glyphs)} glyphs')
    print('char\tadvance\twidth\theight\tleft\ttop')
    for c in wanted or glyphs:
        g = glyphs[c]
        print(f'{c}\t{g.advance}\t{g.width}\t{g.height}\t{g.left}\t{g.top}')


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    main(*sys.argv[1:])
