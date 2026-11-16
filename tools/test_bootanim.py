"""Tests for bootanim.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import os
import tempfile
import unittest
import zipfile

import bootanim

FRAME = bootanim.png(bootanim.SIZE, bootanim.SIZE, bytearray(bootanim.SIZE * bootanim.SIZE * 3))


def write_zip(entries, compress=zipfile.ZIP_STORED):
    fd, path = tempfile.mkstemp(suffix='.zip')
    os.close(fd)
    with zipfile.ZipFile(path, 'w', compress) as z:
        for name, data in entries:
            z.writestr(name, data)
    return path


class CheckTest(unittest.TestCase):
    def check(self, entries, compress=zipfile.ZIP_STORED):
        path = write_zip(entries, compress)
        try:
            return bootanim.check(path)
        finally:
            os.remove(path)

    def test_valid(self):
        self.assertEqual(self.check([('desc.txt', '720 720 20\np 0 0 part0\n'), ('part0/0000.png', FRAME)]), [])

    def test_compressed_entries_are_refused(self):
        errors = self.check([('desc.txt', '720 720 20\np 0 0 part0\n'), ('part0/0000.png', FRAME)],
                            zipfile.ZIP_DEFLATED)
        self.assertTrue(any('compressed' in e for e in errors))

    def test_missing_desc(self):
        self.assertEqual(self.check([('part0/0000.png', FRAME)]), ['no desc.txt'])

    def test_bad_first_line(self):
        self.assertTrue(self.check([('desc.txt', '720x720\np 0 0 part0\n'), ('part0/0000.png', FRAME)]))

    def test_last_line_needs_newline(self):
        errors = self.check([('desc.txt', '720 720 20\np 0 0 part0'), ('part0/0000.png', FRAME)])
        self.assertTrue(any('newline' in e for e in errors))

    def test_part_without_frames(self):
        errors = self.check([('desc.txt', '720 720 20\np 0 0 part0\nc 1 0 part1\n'), ('part0/0000.png', FRAME)])
        self.assertEqual(errors, ['part1: no frame'])

    def test_wrong_frame_size(self):
        small = bootanim.png(10, 10, bytearray(300))
        errors = self.check([('desc.txt', '720 720 20\np 0 0 part0\n'), ('part0/0000.png', small)])
        self.assertTrue(any('10x10' in e for e in errors))

    def test_trimmed_frames(self):
        small = bootanim.png(10, 20, bytearray(600))
        desc = ('desc.txt', '720 720 20\np 0 0 part0\n')
        self.assertEqual(self.check([desc, ('part0/0000.png', small), ('part0/trim.txt', '10x20+5+7\n')]), [])
        errors = self.check([desc, ('part0/0000.png', small), ('part0/trim.txt', '20x10+5+7\n')])
        self.assertTrue(any('expected 20x10' in e for e in errors))
        errors = self.check([desc, ('part0/0000.png', small), ('part0/trim.txt', '10x20+715+7\n')])
        self.assertTrue(any('bad line' in e for e in errors))
        errors = self.check([desc, ('part0/0000.png', small), ('part0/0001.png', small),
                             ('part0/trim.txt', '10x20+5+7\n')])
        self.assertTrue(any('1 lines for 2 frames' in e for e in errors))

    def test_c_part_after_a_fading_f_part_is_refused(self):
        frames = [('part0/0000.png', FRAME), ('part1/0000.png', FRAME)]
        errors = self.check([('desc.txt', '720 720 20\nf 1 0 part0 20\nc 1 0 part1\n')] + frames)
        self.assertTrue(any('part1: c part after the f part part0' in e for e in errors), errors)
        # Without a fade, f behaves like p; and a c part before the f part is fine.
        self.assertEqual(self.check([('desc.txt', '720 720 20\nf 1 0 part0 0\nc 1 0 part1\n')] + frames), [])
        self.assertEqual(self.check([('desc.txt', '720 720 20\nc 1 0 part1\nf 0 0 part0 20\n')] + frames), [])

    def test_textures_over_256_mib_are_refused(self):
        # 65 full frames: 65 x 720 x 720 x 4 = 128.5 MiB; listed twice in two parts sharing them.
        frames = [('part0/%04d.png' % i, FRAME) for i in range(65)]
        desc = ('desc.txt', '720 720 20\np 1 0 part0\n')
        self.assertEqual(self.check([desc] + frames), [])
        errors = self.check([('desc.txt', '720 720 20\np 1 0 part0\np 1 0 part1\n')] + frames
                            + [('part1/%04d.png' % i, FRAME) for i in range(65)])
        self.assertTrue(any(e.startswith('textures: 257 MiB') for e in errors), errors)

    def test_c_parts_over_3_s_are_refused(self):
        small = bootanim.png(10, 10, bytearray(300))
        frames = [('part0/0000.png', small), ('part0/trim.txt', '10x10+0+0\n')]
        self.assertEqual(self.check([('desc.txt', '720 720 20\nc 60 0 part0\n')] + frames), [])
        errors = self.check([('desc.txt', '720 720 20\nc 1 60 part0\n')] + frames)
        self.assertEqual(errors, ['c parts: 3.05 s after the end of the boot, at most 3'])
        errors = self.check([('desc.txt', '720 720 20\nc 0 0 part0\n')] + frames)
        self.assertTrue(any('loops forever' in e for e in errors))

    def test_not_a_zip(self):
        fd, path = tempfile.mkstemp()
        os.write(fd, b'not a zip')
        os.close(fd)
        try:
            self.assertTrue(bootanim.check(path)[0].startswith('not a zip'))
        finally:
            os.remove(path)


class DescTest(unittest.TestCase):
    FRAMES = {'part0': 164, 'part1': 1}

    def test_layout(self):
        self.assertEqual(bootanim.desc(bootanim.LAYOUT, self.FRAMES), '720 720 12\np 1 0 part0\np 0 0 part1\n')

    def test_nothing_plays_after_the_exit_request(self):
        # The 9900 does not fade out: no c part, the full bar is held by a looping p part.
        self.assertFalse([k for k, _, _ in bootanim.LAYOUT if k in ('c', 'f')])

    def test_no_f_part(self):
        # An f part, once faded, never lets a later c part end (Android 14).
        with self.assertRaises(ValueError):
            bootanim.desc((('f', 1, 'part0'), ('c', 1, 'part1')), {'part0': 1, 'part1': 1})

    def test_every_part_needs_frames(self):
        with self.assertRaises(ValueError):
            bootanim.desc(bootanim.LAYOUT, dict(self.FRAMES, part1=0))

    def test_a_c_part_never_loops_forever(self):
        with self.assertRaises(ValueError):
            bootanim.desc((('c', 0, 'part0'),), {'part0': 1})


class BarTest(unittest.TestCase):
    def test_fill_grows_from_empty_to_full(self):
        n = int(bootanim.EXPECTED * bootanim.FPS)
        w = [bootanim.fill_width(i, n) for i in range(n)]
        self.assertEqual((w[0], w[-1]), (0, bootanim.BAR_W))
        self.assertTrue(all(a <= b for a, b in zip(w, w[1:])))

    def test_placement_on_720(self):
        self.assertEqual((bootanim.SIZE - bootanim.BAR_W) // 2, 94)
        self.assertEqual((bootanim.BAR_Y, bootanim.BAR_Y + bootanim.BAR_H), (675, 681))
        self.assertEqual(bootanim.LOGO_Y + bootanim.LOGO_H // 2, 331)

    def test_frame_has_logo_track_and_fill(self):
        logo = (bootanim.LOGO_W, bootanim.LOGO_H, b'\x80\x80\x80\xff' * (bootanim.LOGO_W * bootanim.LOGO_H))
        (x, y, w, h), buf = bootanim.frame(logo, 10)
        self.assertEqual(x, 94)

        def px(X, Y):
            i = ((Y - y) * w + X - x) * 3
            return bytes(buf[i:i + 3])
        self.assertEqual(px(360, 331), b'\x80\x80\x80')
        self.assertEqual(px(94, 675), bytes(bootanim.FILL[0]))
        self.assertEqual(px(104, 680), bytes(bootanim.TRACK))
        self.assertEqual(px(104, 600), b'\0\0\0')

    def test_build_passes_check(self):
        out = tempfile.mkdtemp()
        logo = os.path.join(out, 'logo.png')
        with open(logo, 'wb') as f:
            f.write(bootanim.png(bootanim.LOGO_W, bootanim.LOGO_H, bytearray(bootanim.LOGO_W * bootanim.LOGO_H * 3)))
        zp = os.path.join(out, 'b.zip')
        bootanim.build(logo, zp)
        self.assertEqual(bootanim.check(zp), [])


if __name__ == '__main__':
    unittest.main()
