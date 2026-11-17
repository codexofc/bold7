"""BlackBerry OS 7.1 boot screen of the Bold 9900 as an Android bootanimation.zip (docs/BBOS7-VISUEL.md, section 11).

    bootanim.py build <logo.png> <out.zip>
    bootanim.py check <zip>

The 9900 draws its boot screen natively, from a run-length coded image of its OS (core/firmware BootScreen reads it on the phone); here the source is the
official simulator's own screen (sim-01-demarrage.png, fen-demarrage-*.png, native snapshots,
640x480). On black: the BlackBerry logo (332x59, grey shading kept as captured), and under it a
532x6 progress bar, track #313031, fill a vertical gradient from white to #A5A2A5 that grows from
left to right. `tools/import-assets.sh demarrage` cuts the logo out of the capture (the file given
here); the measured bar colours are named below. Placed for 720x720: logo and bar centred on x 360,
logo centre at 46 % of the height, bar 45 px above the bottom (docs/BBOS7-VISUEL.md, "On the Q25").
The bar fills over EXPECTED seconds, then stays full until Android asks bootanim to exit.
Deterministic: same inputs, same zip, byte for byte (fixed dates, fixed order, zlib level 9), at
equal `magick`. `check` verifies what bootanimation of Android 14 needs (FORMAT.md of AOSP): stored
entries only, a valid desc.txt, every part present with PNG frames of the announced size, at most
TEXTURES_MAX of textures and C_MAX seconds of `c` parts (what delays the lock screen), no `c` part
that loops forever or follows an `f` part with a fade (it would never end). The build
fails on any error, a malformed zip never reaches the module.
"""
import re
import struct
import subprocess
import sys
import zipfile
import zlib

SIZE = 720  # Classic and Q25: 720x720 (docs/DEVICE.md)
# Frames hold the logo and the bar (a 532x379 box, 0.8 MB as a texture) and bootanimation keeps every
# frame as a texture until it exits: 12 fps keeps part0 at about 130 MiB.
FPS = 12
# Length of the fill, in seconds. Measured on the Q25 (an earlier build, same phone, same boot):
# first frame to boot_progress_enable_screen, 14.67 s, of which 13.7 s of frames at the pace
# bootanimation really ran. To measure again after an OTA. A slower boot holds the full bar.
EXPECTED = 13.7

# Measured on sim-01 (docs/BBOS7-VISUEL.md, section 11).
LOGO_W, LOGO_H = 332, 59
BAR_W, BAR_H = 532, 6
TRACK = (0x31, 0x30, 0x31)
FILL = ((0xFF, 0xFF, 0xFF), (0xF7, 0xF3, 0xF7), (0xDE, 0xDF, 0xDE),  # top to bottom, one per row
        (0xCE, 0xCB, 0xCE), (0xB5, 0xB7, 0xB5), (0xA5, 0xA2, 0xA5))
# Placed for 720x720 (D): logo centre at 46 % of the height, bar at y 675 to 680.
LOGO_Y = round(SIZE * 0.46) - LOGO_H // 2
BAR_Y = SIZE - 45
# bootanimation keeps every frame as a texture until it exits (BootAnimation::playAnimation).
TEXTURES_MAX = 256 << 20
# `c` parts play to the end once Android asks bootanim to exit, and the screen, boot_completed and
# boot-completed.sh wait for them (WindowManagerService, up to 30 s): kept short. None here: the
# 9900 does not fade out, the last frame stays until Android's first one replaces it.
C_MAX = 3.0
# Parts: type, count, folder. p: cut at the first frame after the end of the boot is asked for.
# part0 plays the fill once; part1 is the full bar, held (count 0) until the exit request. No `f`
# part: in Android 14, once an f part has faded, fadedFramesCount stays at INT_MAX and a later c
# part never ends.
LAYOUT = (('p', 1, 'part0'),
          ('p', 0, 'part1'))


def fill_width(i, frames):
    """Pixels of the bar filled at frame i of frames (the last one is full)."""
    return round(BAR_W * min(1.0, i / max(1, frames - 1)))


def png(width, height, rgb):
    """8-bit RGB PNG, no row filter (mostly black frames: zlib alone does well)."""
    stride = width * 3
    raw = b''.join(b'\0' + bytes(rgb[y * stride:(y + 1) * stride]) for y in range(height))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def magick_rgba(args):
    """Runs magick, returns (w, h, RGBA bytes) of its single output image."""
    out = subprocess.run(['magick'] + args + ['-depth', '8', 'PAM:-'], check=True, capture_output=True).stdout
    head, _, data = out.partition(b'ENDHDR\n')
    fields = dict(l.split(b' ', 1) for l in head.split(b'\n')[1:] if b' ' in l)
    w, h, depth = int(fields[b'WIDTH']), int(fields[b'HEIGHT']), int(fields[b'DEPTH'])
    if depth == 4:
        return w, h, data[:w * h * 4]
    if depth == 3:  # RGB: opaque
        return w, h, b''.join(data[i:i + 3] + b'\xff' for i in range(0, w * h * 3, 3))
    raise ValueError('magick: depth %d' % depth)



def frame(logo, width):
    """The box (x, y, w, h) and its RGB bytes: logo over the bar with width pixels filled."""
    lw, lh, rgba = logo
    x0, y0 = (SIZE - BAR_W) // 2, LOGO_Y
    h = BAR_Y + BAR_H - y0
    buf = bytearray(BAR_W * h * 3)
    lx = (SIZE - lw) // 2 - x0
    for y in range(lh):
        for x in range(lw):
            i = ((y + LOGO_Y - y0) * BAR_W + lx + x) * 3
            buf[i:i + 3] = rgba[(y * lw + x) * 4:(y * lw + x) * 4 + 3]
    for y in range(BAR_H):
        for x in range(BAR_W):
            i = ((BAR_Y + y - y0) * BAR_W + x) * 3
            buf[i:i + 3] = bytes(FILL[y] if x < width else TRACK)
    return (x0, y0, BAR_W, h), buf


def build(logo_path, out_path):
    logo = magick_rgba([logo_path])
    if logo[:2] != (LOGO_W, LOGO_H):
        raise ValueError('%s: %dx%d, expected %dx%d' % ((logo_path,) + logo[:2] + (LOGO_W, LOGO_H)))
    count = int(EXPECTED * FPS)
    box = frame(logo, 0)[0]
    parts = {'part0': [png(box[2], box[3], frame(logo, fill_width(i, count))[1]) for i in range(count)],
             'part1': [png(box[2], box[3], frame(logo, BAR_W)[1])]}
    text = desc(LAYOUT, {name: len(f) for name, f in parts.items()})
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_STORED) as z:
        def put(name, data):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
        put('desc.txt', text)
        for name, frames in parts.items():
            for i, data in enumerate(frames):
                put('%s/%04d.png' % (name, i), data)
            put(name + '/trim.txt', '%dx%d+%d+%d\n' % (box[2], box[3], box[0], box[1]) * len(frames))


def desc(layout, frames, size=SIZE, fps=FPS):
    """desc.txt for layout and the number of frames of each part. Pauses are always 0: bootanimation
    skips a pause once the end of the boot is asked for, which is when `c` parts play, so a part is
    held by repeating its frames (count), never by a pause."""
    lines = ['%d %d %d' % (size, size, fps)]
    for kind, count, name in layout:
        if kind not in ('p', 'c'):
            raise ValueError('%s: only p and c parts (an f part never lets a later c part end)' % name)
        if not frames.get(name):
            raise ValueError('%s: no frame' % name)
        if kind == 'c' and count == 0:
            raise ValueError('%s: a c part that loops forever never lets the boot end' % name)
        lines.append('%s %d 0 %s' % (kind, count, name))
    return '\n'.join(lines) + '\n'


def check(path):
    """Errors that would make bootanimation of Android 14 show nothing or something else."""
    errors = []
    try:
        z = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        return ['not a zip: %s' % e]
    with z:
        infos = z.infolist()
        errors += ['%s: compressed, bootanimation needs stored entries (zip -0)' % i.filename
                   for i in infos if i.compress_type != zipfile.ZIP_STORED]
        names = [i.filename for i in infos]
        if 'desc.txt' not in names:
            return errors + ['no desc.txt']
        lines = [l for l in z.read('desc.txt').decode('ascii', 'replace').split('\n')]
        if lines[-1] != '':
            errors.append('desc.txt: last line without newline, bootanimation ignores it')
        lines = [l for l in lines if l.strip()]
        head = lines[0].split() if lines else []
        if len(head) not in (3, 4) or not all(v.isdigit() for v in head):
            return errors + ['desc.txt: first line is not "WIDTH HEIGHT FPS [PROGRESS]"']
        width, height, fps = int(head[0]), int(head[1]), int(head[2])
        if (width, height) != (SIZE, SIZE) or fps <= 0:
            errors.append('desc.txt: %dx%d at %d fps, expected %dx%d' % (width, height, fps, SIZE, SIZE))
        if len(lines) < 2:
            errors.append('desc.txt: no part')
        textures, c_seconds, faded = 0, 0.0, None
        for line in lines[1:]:
            f = line.split()
            if len(f) < 4 or f[0] not in ('p', 'c', 'f') or not f[1].isdigit() or not f[2].isdigit():
                errors.append('desc.txt: bad part line %r' % line)
                continue
            if f[0] == 'f' and len(f) > 4 and f[4].isdigit() and int(f[4]) > 0:
                faded = faded or f[3]
            elif f[0] == 'c' and faded:
                errors.append('%s: c part after the f part %s, which leaves fadedFramesCount at INT_MAX'
                              ' once faded: it never ends, the boot waits 30 s' % (f[3], faded))
            # Frames sorted by name (Animation::Part::frames is a SortedVector); the lines of trim.txt
            # give each, in that order, its size and place.
            frames = sorted(n for n in names if n.startswith(f[3] + '/') and not n.endswith('/')
                            and n.rsplit('/', 1)[1] not in ('trim.txt', 'audio.wav'))
            if not frames:
                errors.append('%s: no frame' % f[3])
            sizes = [(width, height)] * len(frames)
            if f[3] + '/trim.txt' in names:
                trims = z.read(f[3] + '/trim.txt').decode('ascii', 'replace').split('\n')[:-1]
                if len(trims) != len(frames):
                    errors.append('%s/trim.txt: %d lines for %d frames' % (f[3], len(trims), len(frames)))
                for k, t in enumerate(trims[:len(frames)]):
                    m = re.fullmatch(r'(\d+)x(\d+)\+(\d+)\+(\d+)', t)
                    if not m or int(m[1]) + int(m[3]) > width or int(m[2]) + int(m[4]) > height:
                        errors.append('%s/trim.txt: bad line %r' % (f[3], t))
                    else:
                        sizes[k] = (int(m[1]), int(m[2]))
            for n, size in zip(frames, sizes):
                data = z.read(n)
                if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
                    errors.append('%s: not a PNG' % n)
                elif struct.unpack('>II', data[16:24]) != size:
                    errors.append('%s: %dx%d, expected %dx%d' % ((n,) + struct.unpack('>II', data[16:24]) + size))
            # One RGBA texture per frame, kept to the end; a repeated part reuses its own.
            textures += sum(w * h * 4 for w, h in sizes)
            if f[0] == 'c':
                if int(f[1]) == 0:
                    errors.append('%s: a c part that loops forever never lets the boot end' % f[3])
                c_seconds += int(f[1]) * (len(frames) + int(f[2])) / max(fps, 1)
        if textures > TEXTURES_MAX:
            errors.append('textures: %d MiB, at most %d' % (textures >> 20, TEXTURES_MAX >> 20))
        if c_seconds > C_MAX:
            errors.append('c parts: %.2f s after the end of the boot, at most %g' % (c_seconds, C_MAX))
    return errors


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == 'build':
        build(*sys.argv[2:])
        problems = check(sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] == 'check':
        problems = check(sys.argv[2])
    else:
        sys.exit(__doc__)
    for p in problems:
        print('bootanimation: ' + p, file=sys.stderr)
    sys.exit(1 if problems else 0)
