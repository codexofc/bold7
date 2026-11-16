"""Tests for bench.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import unittest

import bench

# The swipes of a run of stock Launcher3 on the Q25: gfxinfo said p50 10, p90 15, p95 18, p99 26 ms.
LAUNCHER3 = ('HISTOGRAM: 5ms=51 6ms=44 7ms=88 8ms=83 9ms=92 10ms=113 11ms=106 12ms=80 13ms=53 14ms=41 '
             '15ms=32 16ms=20 17ms=11 18ms=8 19ms=3 20ms=5 21ms=7 22ms=12 23ms=1 24ms=1 25ms=2 26ms=2 '
             '27ms=4 28ms=1 29ms=0 32ms=1 65ms=1 150ms=0\nHISTOGRAM: 5ms=999')


class PercentileTest(unittest.TestCase):
    def test_reads_like_android(self):
        h = bench.histogram(LAUNCHER3)
        self.assertEqual(sum(h.values()), 862)
        self.assertEqual([bench.percentile(h, p) for p in (50, 90, 95, 99)], [10, 15, 18, 26])

    def test_summed_runs(self):
        h = bench.summed([{9: 90, 20: 10}, {9: 100}])
        self.assertEqual(h, {9: 190, 20: 10})
        self.assertEqual(bench.percentile(h, 94), 9)
        self.assertEqual(bench.percentile(h, 95), 20)  # Android counts the top 5 % from the slowest bucket down


if __name__ == '__main__':
    unittest.main()
