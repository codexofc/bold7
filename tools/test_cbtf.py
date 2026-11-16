"""Tests for cbtf.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import struct
import unittest

import cbtf


def fabricate(name='Clock', ranges=((0x30, 0x3b, '1' * 11), ), glyphs=None):
    """A cbtf laid out as the 9900's theme ones: sections 0 to 6, glyphs after the char map."""
    count = sum(m.count('1') for _, _, m in ranges)
    glyphs = glyphs or [bytes([0, 0xdc - i, 29, 26, 42, 2]) + bytes([i] * (i + 1)) for i in range(count)]
    s0 = struct.pack('>HIH', 1, len(name), 8) + name.encode('utf-16-be')
    s1 = struct.pack('>BHH', 0, 0x0800, count)
    s2 = bytes(14)
    s4 = struct.pack('>BH', 0, len(ranges))
    for first, end, mask in ranges:
        bits = mask.ljust((end - first + 7) // 8 * 8, '0')
        s4 += struct.pack('>HHH', first, end, mask.count('1'))
        s4 += bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits), 8))
    s5 = b''.join(glyphs)
    offsets, pos = [], 0
    for g in glyphs:
        offsets.append(pos)
        pos += len(g)
    s3 = struct.pack(f'>{count}H', *offsets)
    s6 = bytes([0x20, 1]) + b'\xff' * 4
    order = [(0, s0), (1, s1), (2, s2), (5, s5), (3, s3), (4, s4), (6, s6)]
    head = 12 + 6 * len(order)
    table, body = b'', b''
    for sid, sec in order:
        table += struct.pack('>HI', sid, head + len(body))
        body += sec
    return b'cbtf' + struct.pack('>HHHH', 10, 2, 1, len(order)) + table + body


class ParseTest(unittest.TestCase):
    def test_name_and_digits_with_their_metrics_and_pixels(self):
        name, glyphs = cbtf.parse(fabricate())
        self.assertEqual(name, 'Clock')
        self.assertEqual(list(glyphs), list('0123456789:'))
        self.assertEqual(glyphs['0'], cbtf.Glyph(-36, 29, 26, 42, 2, b'\x00'))
        self.assertEqual(glyphs[':'].top, -46)
        self.assertEqual(glyphs[':'].pixels, b'\x0a' * 11)

    def test_the_mask_skips_absent_characters_across_ranges(self):
        data = fabricate(ranges=((0x2e, 0x3b, '1000011'), (0x41, 0x4e, '1000000000001')))
        self.assertEqual(list(cbtf.parse(data)[1]), ['.', '3', '4', 'A', 'M'])

    def test_refuses_another_format(self):
        with self.assertRaisesRegex(ValueError, 'not a cbtf'):
            cbtf.parse(b'PK\x03\x04' + bytes(60))

    def test_refuses_a_mask_that_disagrees_with_its_count(self):
        data = bytearray(fabricate())
        at = data.index(struct.pack('>HHH', 0x30, 0x3b, 11))
        data[at + 4:at + 6] = struct.pack('>H', 10)
        with self.assertRaisesRegex(ValueError, 'announced'):
            cbtf.parse(bytes(data))


if __name__ == '__main__':
    unittest.main()
