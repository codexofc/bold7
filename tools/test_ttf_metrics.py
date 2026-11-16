"""Tests for ttf_metrics.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import os
import struct
import tempfile
import unittest

import ttf_metrics


def table_sum(data):
    data += b'\0' * (-len(data) % 4)
    return sum(struct.unpack(f'>{len(data) // 4}I', data)) & 0xFFFFFFFF


def fabricate(ascender=1556, descender=-483, line_gap=410, win_ascent=1901, win_descent=483):
    """A TrueType file with the three tables the fix reads, laid out and checksummed as a real one."""
    head = bytearray(54)
    struct.pack_into('>I', head, 12, 0x5F0F3CF5)  # magic
    hhea = bytearray(36)
    struct.pack_into('>hhh', hhea, 4, ascender, descender, line_gap)
    os2 = bytearray(78)
    struct.pack_into('>HH', os2, 74, win_ascent, win_descent)
    tables = sorted({'head': bytes(head), 'hhea': bytes(hhea), 'OS/2': bytes(os2)}.items())
    offset = 12 + 16 * len(tables)
    directory, body = b'', b''
    for tag, data in tables:
        directory += struct.pack('>4sIII', tag.encode(), table_sum(data), offset + len(body), len(data))
        body += data + b'\0' * (-len(data) % 4)
    font = bytearray(struct.pack('>IHHHH', 0x00010000, len(tables), 32, 1, 16) + directory + body)
    head_at = font.index(b'head')
    head_offset = struct.unpack_from('>I', font, head_at + 8)[0]
    struct.pack_into('>I', font, head_offset + 8, (0xB1B0AFBA - table_sum(bytes(font))) & 0xFFFFFFFF)
    return bytes(font)


class FixTest(unittest.TestCase):
    def test_hhea_takes_the_win_metrics_and_no_line_gap(self):
        fixed = ttf_metrics.fix(fabricate())
        self.assertEqual(ttf_metrics.metrics(fixed), ((1901, -483, 0), (1901, 483)))
        self.assertEqual(ttf_metrics.mismatches(fixed), [])

    def test_a_font_already_right_is_left_byte_for_byte(self):
        right = fabricate(1901, -483, 0)
        self.assertEqual(ttf_metrics.fix(right), right)

    def test_checksums_stay_valid(self):
        fixed = ttf_metrics.fix(fabricate())
        self.assertEqual(table_sum(fixed), 0xB1B0AFBA)
        tag_at = fixed.index(b'hhea', 12)
        checksum, offset, length = struct.unpack_from('>III', fixed, tag_at + 4)
        self.assertEqual(checksum, table_sum(fixed[offset:offset + length]))

    def test_mismatches_names_what_differs(self):
        self.assertEqual(len(ttf_metrics.mismatches(fabricate())), 1)
        self.assertEqual(ttf_metrics.mismatches(fabricate(1901, -483, 0)), [])

    def test_main_copies_every_font_fixed(self):
        with tempfile.TemporaryDirectory() as src, tempfile.TemporaryDirectory() as out:
            for name, font in (('Off.ttf', fabricate()), ('Right.ttf', fabricate(1901, -483, 0))):
                with open(os.path.join(src, name), 'wb') as f:
                    f.write(font)
            ttf_metrics.main(src, out)
            self.assertEqual(sorted(os.listdir(out)), ['Off.ttf', 'Right.ttf'])
            for name in os.listdir(out):
                with open(os.path.join(out, name), 'rb') as f:
                    self.assertEqual(ttf_metrics.mismatches(f.read()), [])

    def test_refuses_a_file_that_is_not_truetype(self):
        with self.assertRaises(ValueError):
            ttf_metrics.fix(b'wOFF' + bytes(60))


if __name__ == '__main__':
    unittest.main()
