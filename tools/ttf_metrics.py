#!/usr/bin/env python3
"""Copies the module's TrueType fonts with their line metrics made consistent: the hhea table's
ascender, descender and line gap become OS/2's winAscent, -winDescent and 0.

  python3 -I tools/ttf_metrics.py <dir of TTFs> <output dir>

Android lays text out with hhea. BBAlphaSans.ttf (Regular) of the 9900's firmware alone has an
hhea of 1556 / -483 / 410 where its eleven siblings have 1901 / -483 / 0, their winAscent and
winDescent: its line is 0.996 em against 1.164 in bold, and accented capitals (up to 1901) rise
4 to 6 px above its ascent, clipped without font padding. The sources are never changed: the
module's build writes the fixed copies (system/module/build.gradle.kts). Table checksums and
head.checkSumAdjustment are recomputed. Every output is checked before the script ends: a font
whose hhea still differs from its win metrics fails the build.
"""
import os
import shutil
import struct
import sys


def _tables(data):
    if data[:4] not in (b'\x00\x01\x00\x00', b'true'):
        raise ValueError('not a TrueType font')
    count = struct.unpack_from('>H', data, 4)[0]
    out = {}
    for i in range(count):
        tag, checksum, offset, length = struct.unpack_from('>4sIII', data, 12 + 16 * i)
        out[tag.decode('latin-1')] = (12 + 16 * i, offset, length)
    for tag in ('head', 'hhea', 'OS/2'):
        if tag not in out:
            raise ValueError(f'no {tag} table')
    return out


def _sum(data):
    data = bytes(data) + b'\0' * (-len(data) % 4)
    return sum(struct.unpack(f'>{len(data) // 4}I', data)) & 0xFFFFFFFF


def metrics(data):
    """((hhea ascender, descender, line gap), (OS/2 winAscent, winDescent))."""
    t = _tables(data)
    hhea, os2 = t['hhea'][1], t['OS/2'][1]
    return struct.unpack_from('>hhh', data, hhea + 4), struct.unpack_from('>HH', data, os2 + 74)


def mismatches(data):
    (asc, desc, gap), (win_asc, win_desc) = metrics(data)
    if (asc, desc, gap) == (win_asc, -win_desc, 0):
        return []
    return [f'hhea {asc} / {desc} / {gap}, win {win_asc} / {win_desc}']


def fix(data):
    if not mismatches(data):
        return data
    t = _tables(data)
    font = bytearray(data)
    entry, hhea, length = t['hhea']
    win_asc, win_desc = metrics(data)[1]
    struct.pack_into('>hhh', font, hhea + 4, win_asc, -win_desc, 0)
    struct.pack_into('>I', font, entry + 4, _sum(font[hhea:hhea + length]))
    head = t['head'][1]
    struct.pack_into('>I', font, head + 8, 0)
    struct.pack_into('>I', font, head + 8, (0xB1B0AFBA - _sum(font)) & 0xFFFFFFFF)
    return bytes(font)


def main(src, out):
    os.makedirs(out, exist_ok=True)
    failed = []
    for name in sorted(os.listdir(src)):
        path = os.path.join(src, name)
        if not name.lower().endswith('.ttf'):
            shutil.copy2(path, os.path.join(out, name))
            continue
        with open(path, 'rb') as f:
            data = f.read()
        fixed = fix(data)
        if fixed is not data:
            print(f'{name}: {mismatches(data)[0]}, set to the win metrics')
        failed += [f'{name}: {m}' for m in mismatches(fixed)]
        with open(os.path.join(out, name), 'wb') as f:
            f.write(fixed)
    if failed:
        sys.exit('\n'.join(failed))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
