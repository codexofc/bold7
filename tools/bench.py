#!/usr/bin/env python3
"""Public benchmarks of Bold7: bench.json from the raw runs of tools/perf.sh, and the charts of the
README from bench.json.

  /usr/bin/python3 -I tools/bench.py json --date <YYYY-MM-DD> --bold7 <run dir>... \\
      --launcher3 <run dir>... [--launcher3-date <YYYY-MM-DD>] [--apps <dir>] > bench.json
  /usr/bin/python3 -I tools/bench.py charts <bench.json> <output dir>

json reads, from each run directory of perf.sh (build/perf/<label>/), summary.txt and the
gfxinfo dumps. The runs of one launcher are pooled: late frames are the janky frames of all runs
over all their frames, percentiles are read from the summed histograms the way Android reads its
own (ProfileData::findPercentile), taps are the median of every tap, memory the median of the runs.
--apps holds one directory per app and side, <app>-bold7 and <app>-google, each with the
summary.txt of `APP=<package>/<activity> tools/perf.sh` (its "run N: X ms" lines) and
pss-<n>.txt, the dumpsys meminfo of the app at rest after a cold start.

charts writes frames, apps and memory as PNG when magick is found (its own SVG renderer, with the
Barlow fonts of media/demo/public/fonts given through a type.xml of its own), as SVG otherwise.
No dependency beyond the standard library.
"""
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
from xml.sax.saxutils import escape

# perf.sh scenarios: (gfxinfo file stem, label).
SCENARIOS = [
    ('swipes', 'View change by finger'),
    ('tray-raise', 'Drawer raised and lowered'),
    ('tray-scroll', 'Drawer scrolled'),
]

# App pairs measured against Google's own, in the order they are shown.
APPS = [
    ('phone', 'Phone'),
    ('contacts', 'Contacts'),
    ('calculator', 'Calculator'),
    ('clock', 'Clock'),
    ('calendar', 'Calendar'),
    ('messages', 'Messages'),
]


def histogram(text):
    """{ms: frames} of the first HISTOGRAM line of a gfxinfo dump (the process-wide one)."""
    line = next(l for l in text.splitlines() if l.startswith('HISTOGRAM:'))
    return {int(ms): int(n) for ms, n in re.findall(r'(\d+)ms=(\d+)', line)}


def percentile(hist, p):
    """Android's ProfileData::findPercentile over a {ms: frames} histogram."""
    total = sum(hist.values())
    remaining = total - p * total // 100
    for ms in sorted(hist, reverse=True):
        remaining -= hist[ms]
        if remaining <= 0:
            return ms
    return 0


def field(text, name):
    """The first `name: N` of a gfxinfo dump, as an int."""
    return int(re.search(rf'^{re.escape(name)}: (\d+)', text, re.M).group(1))


def summed(hists):
    out = {}
    for h in hists:
        for ms, n in h.items():
            out[ms] = out.get(ms, 0) + n
    return out


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def scenario(dirs, stem):
    """Pooled frames of one scenario over the runs that have it, or None."""
    dumps = [read(p) for p in (os.path.join(d, f'{stem}-gfxinfo.txt') for d in dirs) if os.path.exists(p)]
    if not dumps:
        return None
    hist = summed(histogram(t) for t in dumps)
    frames = sum(field(t, 'Total frames rendered') for t in dumps)
    janky = sum(field(t, 'Janky frames') for t in dumps)
    return {
        'runs': len(dumps), 'frames': frames, 'late_pct': round(100 * janky / frames, 2),
        'p50': percentile(hist, 50), 'p95': percentile(hist, 95), 'p99': percentile(hist, 99),
        'histogram': {str(ms): n for ms, n in sorted(hist.items()) if n},
    }


def home(dirs):
    """Everything perf.sh measures on one launcher, pooled over its runs."""
    texts = [read(os.path.join(d, 'summary.txt')) for d in dirs]
    taps = [(int(a), int(b)) for t in texts for a, b in re.findall(r'tap->START (\d+) ms, tap->Displayed (\d+) ms', t)]
    cpu = [(int(a), int(b)) for t in texts for a, b in re.findall(r'cpu: (\d+) ms\s+context switches \(all threads\): (\d+)', t)]
    pss = [int(m) for t in texts for m in re.findall(r'TOTAL PSS:\s+(\d+)', t)]
    idle = [int(re.search(r'## idle \d+s\nframes: (\d+)', t).group(1)) for t in texts]
    target = re.search(r"## tap '[^']*' \([^)]*\) -> (\S+),", texts[0]).group(1)
    return {
        'launcher': re.search(r'^launcher: (.*)$', texts[0], re.M).group(1),
        'build': re.search(r'^build: (.*)$', texts[0], re.M).group(1),
        'runs': len(texts),
        'scenarios': {stem: s for stem, _ in SCENARIOS if (s := scenario(dirs, stem))},
        'idle_frames': idle,
        'tap': {'target': target, 'start_ms': [a for a, _ in taps], 'first_frame_ms': [b for _, b in taps]},
        'background': {'cpu_ms': [a for a, _ in cpu], 'context_switches': [b for _, b in cpu]},
        'pss_kb': pss,
    }


def app_side(d):
    """Cold starts (ms) and PSS (KB) of one app, from its directory under --apps."""
    starts = [int(n) for n in re.findall(r'^run \d+: (\d+) ms$', read(os.path.join(d, 'summary.txt')), re.M)]
    pss = [int(re.search(r'TOTAL PSS:\s+(\d+)', read(os.path.join(d, f))).group(1))
           for f in sorted(os.listdir(d)) if re.fullmatch(r'pss-\d+\.txt', f)]
    pkg = re.search(r'^app: (\S+?)/', read(os.path.join(d, 'summary.txt')), re.M).group(1)
    return {'package': pkg, 'start_ms': starts, 'pss_kb': pss}


def median(xs):
    m = statistics.median(xs)
    return int(m) if float(m).is_integer() else m


def stats(xs):
    return {'median': median(xs), 'min': min(xs), 'max': max(xs), 'n': len(xs)}


def mb(kb):
    """dumpsys KB to MB, by thousands as docs/BASELINE.md counts them."""
    return round(kb / 1000, 1)


def bench(date, bold7_dirs, l3_dirs, l3_date, apps_dir):
    b, l = home(bold7_dirs), home(l3_dirs)

    def both(fn):
        return {'bold7': fn(b), 'launcher3': fn(l)}

    metrics = [
        {'id': 'swipe_late', 'label': 'Late frames while swiping the home screen', 'unit': '%', 'better': 'lower', 'budget': 1,
         'values': both(lambda h: h['scenarios']['swipes']['late_pct'])},
        {'id': 'swipe_p95', 'label': 'Frame time while swiping, 95th percentile', 'unit': 'ms', 'better': 'lower', 'budget': 16.6,
         'values': both(lambda h: h['scenarios']['swipes']['p95'])},
        {'id': 'tap_start', 'label': 'Tap on an icon until the app starts', 'unit': 'ms', 'better': 'lower', 'budget': 120,
         'values': both(lambda h: median(h['tap']['start_ms']))},
        {'id': 'background_cpu', 'label': 'Home screen CPU while you use another app, per 30 s', 'unit': 'ms', 'better': 'lower', 'budget': 20,
         'values': both(lambda h: round(statistics.mean(h['background']['cpu_ms']), 1))},
        {'id': 'home_pss', 'label': 'Home screen memory', 'unit': 'MB', 'better': 'lower', 'budget': 120,
         'values': both(lambda h: mb(statistics.median(h['pss_kb'])))},
    ]
    out = {
        'device': 'Zinwa Q25, BenOS (Android 14), 60 Hz screen',
        'method': f'tools/perf.sh on the phone, never an emulator: {b["runs"]} runs of Bold7 on {date}, '
                  f'{l["runs"]} of stock Launcher3 on {l3_date} before Bold7 was installed, each side pooled',
        'date': date,
        'series': [
            {'id': 'bold7', 'label': 'Bold7', 'placeholder': False, 'date': date, 'runs': b['runs']},
            {'id': 'launcher3', 'label': 'Stock Launcher3', 'placeholder': False, 'date': l3_date, 'runs': l['runs']},
        ],
        'metrics': metrics,
        'home': {'bold7': b, 'launcher3': l},
    }
    if apps_dir:
        items = []
        for app, label in APPS:
            sides = {s: app_side(os.path.join(apps_dir, f'{app}-{s}')) for s in ('bold7', 'google')
                     if os.path.isdir(os.path.join(apps_dir, f'{app}-{s}'))}
            if len(sides) < 2:
                continue
            items.append({
                'id': app, 'label': label,
                'packages': {s: v['package'] for s, v in sides.items()},
                'cold_start_ms': {s: stats(v['start_ms']) for s, v in sides.items()},
                'pss_mb': {s: mb(statistics.median(v['pss_kb'])) for s, v in sides.items() if v['pss_kb']},
                'raw': {s: {'start_ms': v['start_ms'], 'pss_kb': v['pss_kb']} for s, v in sides.items()},
            })
        out['apps'] = {
            'method': 'am start -W as root, TotalTime (launch to first frame), after am force-stop, ten cold starts each; '
                      'PSS from dumpsys meminfo 5 s after a cold start, median of three. Bold7 apps in their showcase mode.',
            'series': [{'id': 'bold7', 'label': 'Bold7'}, {'id': 'google', 'label': "Google's app"}],
            'items': items,
        }
    return out


# Charts. Colours of the site (graphite, OS 7 blue); text in Barlow Semi Condensed.
INK, MUTED, LINE, PAPER, BLUE, GREY = '#101317', '#47505a', '#c3cad1', '#f3f5f7', '#0a5aa8', '#8a939d'
W = 960


class Svg:
    def __init__(self, h):
        self.h, self.parts = h, [f'<rect width="{W}" height="{h}" fill="{PAPER}"/>']

    def text(self, x, y, s, size=18, color=INK, bold=False, anchor='start'):
        weight = ' font-weight="bold"' if bold else ''
        self.parts.append(f'<text x="{x:.1f}" y="{y:.1f}" font-family="Barlow"{weight} font-size="{size}" fill="{color}" text-anchor="{anchor}">{escape(s)}</text>')

    def rect(self, x, y, w, h, color, rx=0):
        self.parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{max(w, 0):.1f}" height="{h:.1f}" rx="{rx}" fill="{color}"/>')

    def dashed(self, x, y0, y1, color=INK):
        # Simplification: dashes as rects, magick's own SVG renderer ignores stroke-dasharray.
        y = y0
        while y < y1:
            self.rect(x - 1, y, 2, min(6, y1 - y), color)
            y += 10

    def done(self):
        return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{self.h}" viewBox="0 0 {W} {self.h}">' + ''.join(self.parts) + '</svg>\n'


def legend(svg, x, y, items):
    for color, label in items:
        svg.rect(x, y - 12, 22, 12, color, 2)
        svg.text(x + 30, y, label, 17, MUTED)
        x += 40 + 9 * len(label)


def fmt(v):
    return f'{v:.0f}' if float(v).is_integer() else f'{v:.1f}'


def frames_chart(b):
    """Share of frames per frame time while swiping the home screen, Bold7 against Launcher3."""
    sides = [('bold7', 'Bold7', BLUE), ('launcher3', 'Stock Launcher3', GREY)]
    top_ms = 30
    shares = {}
    for key, _, _ in sides:
        h = {int(k): v for k, v in b['home'][key]['scenarios']['swipes']['histogram'].items()}
        total = sum(h.values())
        row = {ms: 0.0 for ms in range(5, top_ms + 1)}
        for ms, n in h.items():
            row[min(max(ms, 5), top_ms)] += 100 * n / total
        shares[key] = row
    svg = Svg(590)
    svg.text(40, 52, 'Frame times while swiping the home screen', 30, bold=True)
    svg.text(40, 80, f'{b["device"]}. Share of frames by frame time (dumpsys gfxinfo); one frame lasts 16.6 ms at 60 Hz.', 17, MUTED)
    x0, y0, cw, ch = 70, 120, W - 110, 300
    peak = max(max(r.values()) for r in shares.values())
    ymax = math.ceil(peak / 5) * 5
    for t in range(0, ymax + 1, 5):
        y = y0 + ch - ch * t / ymax
        svg.rect(x0, y, cw, 1, LINE)
        svg.text(x0 - 10, y + 5, f'{t}%', 15, MUTED, anchor='end')
    n = top_ms - 5 + 1
    step = cw / n
    bw = step * 0.38
    for i, ms in enumerate(range(5, top_ms + 1)):
        for j, (key, _, color) in enumerate(sides):
            v = shares[key][ms]
            hgt = ch * v / ymax
            svg.rect(x0 + i * step + step * 0.1 + j * bw, y0 + ch - hgt, bw, hgt, color, 1)
        if ms % 5 == 0 or ms == top_ms:
            svg.text(x0 + i * step + step / 2, y0 + ch + 24, f'{ms}+' if ms == top_ms else str(ms), 15, MUTED, anchor='middle')
    svg.text(x0 + cw / 2, y0 + ch + 50, 'frame time, ms', 15, MUTED, anchor='middle')
    bx = x0 + (16.6 - 5 + 0.5) * step
    svg.dashed(bx, y0 - 10, y0 + ch)
    svg.text(bx + 8, y0 + 4, '16.6 ms: one frame', 15, INK)
    for k, (key, label, color) in enumerate(sides):
        s = b['home'][key]['scenarios']['swipes']
        runs = next(x for x in b['series'] if x['id'] == key)
        legend(svg, x0, 500 + 26 * k, [(color, f'{label}: 95th percentile {s["p95"]} ms, {s["late_pct"]:.2f}% late frames '
                                              f'({s["frames"]} frames, {runs["runs"]} runs, {runs["date"]})')])
    svg.text(x0, 572, 'Bold7 changes view across the drawer; stock Launcher3 has one home page on the Q25, so its swipes mostly bounce at the edge.', 15, MUTED)
    return svg.done()


def pair_chart(b, title, subtitle, rows, unit, note=''):
    """Paired horizontal bars: rows of (label, bold7 value, other value, other label)."""
    row_h = 74
    svg = Svg(150 + row_h * len(rows) + (70 if note else 40))
    svg.text(40, 52, title, 30, bold=True)
    svg.text(40, 80, subtitle, 17, MUTED)
    x0, y, cw = 200, 130, W - 200 - 130
    top = max(max(r[1], r[2]) for r in rows)
    for label, v1, v2, _ in rows:
        svg.text(x0 - 16, y + 30, label, 20, INK, bold=True, anchor='end')
        for k, (v, color) in enumerate(((v1, BLUE), (v2, GREY))):
            yy = y + k * 26
            svg.rect(x0, yy, cw * v / top, 20, color, 2)
            svg.text(x0 + cw * v / top + 8, yy + 16, f'{v:.0f} {unit}' if unit == 'ms' else f'{fmt(v)} {unit}', 16, INK)
        y += row_h
    legend(svg, x0, y + 10, [(BLUE, 'Bold7'), (GREY, rows[0][3])])
    if note:
        svg.text(40, y + 46, note, 15, MUTED)
    return svg.done()


def apps_chart(b):
    rows = [(i['label'], i['cold_start_ms']['bold7']['median'], i['cold_start_ms']['google']['median'], "Google's app")
            for i in b['apps']['items']]
    return pair_chart(b, 'Cold start of the apps', f'{b["device"]}. Launch to first frame, median of ten cold starts each. Lower is better.', rows, 'ms',
                      "Bold7's apps show their made-up showcase data; Google's apps open the phone's own data and are compiled ahead of time.")


def memory_chart(b):
    home_row = ('Home screen', b['metrics'][4]['values']['bold7'], b['metrics'][4]['values']['launcher3'], 'Stock Launcher3 or Google\'s app')
    rows = [home_row] + [(i['label'], i['pss_mb']['bold7'], i['pss_mb']['google'], '') for i in b['apps']['items'] if len(i['pss_mb']) == 2]
    return pair_chart(b, 'Memory at rest', f'{b["device"]}. PSS of the process, from dumpsys meminfo. Lower is better.', rows, 'MB')


TYPE_XML = '''<?xml version="1.0"?>
<typemap>
<type name="Barlow-Regular" fullname="Barlow Regular" family="Barlow" style="Normal" stretch="Normal" weight="400" glyphs="{d}/BarlowSemiCondensed-Regular.ttf"/>
<type name="Barlow-Bold" fullname="Barlow Bold" family="Barlow" style="Normal" stretch="Normal" weight="700" glyphs="{d}/BarlowSemiCondensed-Bold.ttf"/>
</typemap>
'''


def to_png(svg_path, png_path):
    """PNG at twice the size with magick's own renderer, the Barlow fonts named by a type.xml."""
    fonts = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'media', 'demo', 'public', 'fonts')
    with tempfile.TemporaryDirectory() as conf:
        with open(os.path.join(conf, 'type.xml'), 'w', encoding='utf-8') as f:
            f.write(TYPE_XML.format(d=escape(os.path.abspath(fonts))))
        env = dict(os.environ, MAGICK_CONFIGURE_PATH=conf)
        subprocess.run(['magick', '-density', '144', f'MSVG:{svg_path}', '-strip', png_path], check=True, env=env)


def charts(bench_path, out_dir):
    with open(bench_path, encoding='utf-8') as f:
        b = json.load(f)
    os.makedirs(out_dir, exist_ok=True)
    made = {'frames': frames_chart(b)}
    if b.get('apps', {}).get('items'):
        made['apps'] = apps_chart(b)
        made['memory'] = memory_chart(b)
    with tempfile.TemporaryDirectory() as tmp:
        for name, svg in made.items():
            # The PNG when magick is there (what the README shows), else the SVG itself.
            png = shutil.which('magick')
            path = os.path.join(tmp if png else out_dir, f'{name}.svg')
            with open(path, 'w', encoding='utf-8') as f:
                f.write(svg)
            if png:
                path = os.path.join(out_dir, f'{name}.png')
                to_png(os.path.join(tmp, f'{name}.svg'), path)
            print(path)


def main(argv):
    if len(argv) >= 3 and argv[0] == 'charts':
        return charts(argv[1], argv[2])
    if not argv or argv[0] != 'json':
        sys.exit(__doc__)
    args, key = {'--bold7': [], '--launcher3': []}, None
    for a in argv[1:]:
        if a.startswith('--'):
            key = a
            args.setdefault(key, [])
        else:
            args[key].append(a)
    one = lambda k: (args.get(k) or [None])[0]
    if not one('--date') or not args['--bold7'] or not args['--launcher3']:
        sys.exit(__doc__)
    json.dump(bench(one('--date'), args['--bold7'], args['--launcher3'], one('--launcher3-date'), one('--apps')), sys.stdout, indent=2)
    print()


if __name__ == '__main__':
    main(sys.argv[1:])
