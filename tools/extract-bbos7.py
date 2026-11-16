#!/usr/bin/env python3
"""Extract the BlackBerry OS 7.1 assets of the Bold 9900 from its official OS Loader, or from
its official simulator, without running anything.

    tools/extract-bbos7.py <9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe>
        -> assets-private/bbos7-9900/, work in firmware/bbos7-9900/work/ (the reference)
    tools/extract-bbos7.py <BlackBerry Simulator_7.1.0.318_9900.exe>
        -> assets-private/bbos7-9900-sim/, work in firmware/bbos7-simulateur/work/

Only those two files are known (recognised by sha256, any other is refused). The simulator is
an NSIS installer whose Java/ directory holds the .cod files: 7-Zip (`7zz`, Homebrew sevenzip)
unpacks it, the rest is the same. The loader's nesting, read in Python only:
  .exe (PE)            overlay: InstallShield SFX container, 30 files, each one obfuscated by a
                       key derived from its name, in blocks of 1024 bytes, then zlib-compressed
  BlackBerry 9900.msi  OLE compound file; the !File, !Component and !Directory tables give the
                       real names of the cabinet's entries
  Data1.cab            MS cabinet (MSZIP: raw deflate blocks sharing a 32 KiB window)
  loader files         OS/rim0x07001204.sfi (native OS image), ALX/*.alx, CodFiles/*.cod
  .cod                 a COD module, or a zip of sibling COD modules (<name>-<n>.cod)
  COD resource         a byte array of the data section, registered by the static initialiser
                       of the module (`2d 02 <len> <ptr>` pushes the array, `28 <ptr>` its name);
                       a resource larger than a module is split into `__<name>@<offset>` chunks
                       spread over the siblings

Writes (<dest> being bbos7-9900 or bbos7-9900-sim), replaced entirely on each run:
  assets-private/<dest>/theme/<cod>/    every resource of the default theme
                                        (net_rim_theme_BlackBerry7_640x480_b and _bs1)
  assets-private/<dest>/images/<cod>/   png, gif, jpg, bmp of the other modules
  assets-private/<dest>/sounds/<cod>/   m4a, mp3, mid, wav, amr, aac
  assets-private/<dest>/fonts/<cod>/    TrueType fonts (split parts joined) and RIM sff4
  assets-private/<dest>/boot/<cod>/     power on and off animations (mp4)
  assets-private/<dest>/<dest>.sha256
and in the work directory: files.txt (the loader's files) and resources.tsv (every resource of
every module: module, name, size). The unpacked tree (work/tree, 900 MB for the loader) is
deleted at the end, KEEP_TREE=1 keeps it (the .cod files are in work/tree/loader/CodFiles, or
work/tree/loader/Java for the simulator). Localised modules (`__<locale>.cod`) other than
English are left out of images/: they repeat the English images. Resources are named after
their path in the module.

Formats read after public descriptions: InstallShield SFX after ISx (lifenjoiner), compound
file, MSI tables and cabinets after the Microsoft specifications, COD after its observed layout.
Python 3 stdlib only, plus 7zz for the simulator. ASSETS overrides the assets-private directory (default: the one of the
main checkout, also from a worktree).
"""
import collections
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
import zlib

# sha256 -> (destination under assets-private, work directory under firmware/, kind)
KNOWN = {
    '4f68dd201def6f3f5c648ce166e7e0e5ff713c79f55f97cb777b8aafa8b85adb':  # OS Loader 7.1.0.2807 (OS 7.1.0.1033)
        ('bbos7-9900', 'bbos7-9900', 'loader'),
    '79208dc1036487b05a1be007063bc377a649f4d9a973e58bade2ac06878474f1':  # Simulator 7.1.0.318 9900
        ('bbos7-9900-sim', 'bbos7-simulateur', 'simulator'),
}
THEME = ('net_rim_theme_BlackBerry7_640x480_b.cod', 'net_rim_theme_BlackBerry7_640x480_bs1.cod')
KINDS = {'images': ('png', 'gif', 'jpg', 'jpeg', 'bmp'),
         'sounds': ('m4a', 'mp3', 'mid', 'midi', 'wav', 'amr', 'aac', 'ogg'),
         'boot': ('mp4',)}
LOCALISED = re.compile(r'__[a-z]{2}(_[A-Za-z]{2,4})?\.cod$')

# ---------------------------------------------------------------- InstallShield

IS_SIG = b'InstallShield\0'
IS_MAGIC = bytes((0x13, 0x35, 0x86, 0x07))
_ROT4 = bytes(((b << 4) | (b >> 4)) & 0xFF for b in range(256))


def is_entries(f, base):
    """(name, offset, size) of each file of the InstallShield container at `base`.

    Header: signature (14 bytes), u16 file count, 30 bytes. Each file: name (260 bytes),
    u32 flags, u32, u32 size, 8 bytes, u16, 30 bytes, then the encoded data."""
    f.seek(base)
    head = f.read(0x2E)
    if head[:14] != IS_SIG:
        raise ValueError('not an InstallShield container')
    count, = struct.unpack_from('<H', head, 14)
    if count == 0:
        raise ValueError('empty InstallShield container')
    pos, out = base + 0x2E, []
    for _ in range(count):
        f.seek(pos)
        attr = f.read(312)
        name = attr[:260].split(b'\0')[0].decode('latin-1')
        flags, _, size = struct.unpack_from('<III', attr, 260)
        if flags & 6 != 6 or not name or '/' in name or '\\' in name or name in ('.', '..'):
            raise ValueError('unexpected InstallShield entry %r (flags %#x)' % (name, flags))
        out.append((name, pos + 312, size))
        pos += 312 + size
    return out


def is_decode_block(block, name):
    """One block of at most 1024 bytes: b -> ~(key ^ rot4(b)), the key restarting each block."""
    key = bytes(c ^ IS_MAGIC[i % 4] for i, c in enumerate(name.encode('latin-1')))
    key = (key * (1024 // len(key) + 1))[:len(block)]
    rot = block.translate(_ROT4)
    n = len(block)
    return (int.from_bytes(rot, 'big') ^ int.from_bytes(key, 'big') ^ ((1 << 8 * n) - 1)).to_bytes(n, 'big')


def is_extract(f, name, offset, size, out):
    """Decode then inflate one entry into the file `out`."""
    z = zlib.decompressobj()
    f.seek(offset)
    with open(out, 'wb') as o:
        left = size
        while left:
            block = f.read(min(1024, left))
            left -= len(block)
            o.write(z.decompress(is_decode_block(block, name)))
        o.write(z.flush())
    if not z.eof:
        raise ValueError('%s: truncated zlib stream' % name)


def find_installshield(f):
    f.seek(0)
    head = f.read(4 << 20)
    i = head.find(IS_SIG)
    while i >= 0:
        try:
            is_entries(f, i)
            return i
        except ValueError:
            i = head.find(IS_SIG, i + 1)
    sys.exit('no InstallShield container in the first 4 MiB')

# ---------------------------------------------------------------- compound file, MSI


class Compound:
    """Read-only OLE compound file (MS-CFB): directory and streams."""

    def __init__(self, f):
        self.f = f
        h = f.read(512)
        assert h[:8] == bytes.fromhex('d0cf11e0a1b11ae1'), 'not a compound file'
        self.ss = 1 << struct.unpack_from('<H', h, 0x1E)[0]
        self.mss = 1 << struct.unpack_from('<H', h, 0x20)[0]
        nfat, dir0 = struct.unpack_from('<II', h, 0x2C)
        self.cutoff, minifat0, nminifat, difat0, ndifat = struct.unpack_from('<5I', h, 0x38)
        difat, sect = list(struct.unpack_from('<109I', h, 0x4C)), difat0
        for _ in range(ndifat):
            more = struct.unpack('<%dI' % (self.ss // 4), self.sector(sect))
            difat, sect = difat + list(more[:-1]), more[-1]
        self.fat = []
        for s in difat[:nfat]:
            self.fat += struct.unpack('<%dI' % (self.ss // 4), self.sector(s))
        raw = self.chain(dir0)
        self.entries = []
        for i in range(0, len(raw), 128):
            e = raw[i:i + 128]
            n, = struct.unpack_from('<H', e, 0x40)
            start, size = struct.unpack_from('<IQ', e, 0x74)
            if self.ss == 512:
                size &= 0xFFFFFFFF
            self.entries.append((e[:max(n - 2, 0)].decode('utf-16-le'), e[0x42], start, size))
        self.mini = self.chain(self.entries[0][2])
        mf = self.chain(minifat0) if nminifat else b''
        self.minifat = struct.unpack('<%dI' % (len(mf) // 4), mf)

    def sector(self, n):
        self.f.seek((n + 1) * self.ss)
        return self.f.read(self.ss)

    def chain(self, n, size=None):
        out = []
        while n < 0xFFFFFFFA and (size is None or len(out) * self.ss < size):
            out.append(self.sector(n))
            n = self.fat[n]
        return b''.join(out)[:size]

    def stream(self, entry):
        _, _, start, size = entry
        if size >= self.cutoff:
            return self.chain(start, size)
        out, n = [], start
        while n < 0xFFFFFFFA and len(out) * self.mss < size:
            out.append(self.mini[n * self.mss:(n + 1) * self.mss])
            n = self.minifat[n]
        return b''.join(out)[:size]

    def copy(self, entry, path):
        _, _, start, size = entry
        assert size >= self.cutoff
        with open(path, 'wb') as o:
            n, left = start, size
            while left:
                b = self.sector(n)[:left]
                o.write(b)
                left -= len(b)
                n = self.fat[n]


MSI_CHARS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._'


def msi_name(name):
    """Stream names of an MSI are packed two base-64 characters per UTF-16 unit."""
    out = []
    for ch in name:
        c = ord(ch)
        if 0x3800 <= c < 0x4800:
            c -= 0x3800
            out += [MSI_CHARS[c & 0x3F], MSI_CHARS[(c >> 6) & 0x3F]]
        elif 0x4800 <= c < 0x4840:
            out.append(MSI_CHARS[c - 0x4800])
        elif c == 0x4840:
            out.append('!')
        else:
            out.append(ch)
    return ''.join(out)


class Msi:
    def __init__(self, f):
        self.cf = Compound(f)
        self.streams = {msi_name(e[0]): e for e in self.cf.entries if e[1] == 2}
        pool = self.cf.stream(self.streams['!_StringPool'])
        data = self.cf.stream(self.streams['!_StringData'])
        codepage, = struct.unpack_from('<I', pool)
        self.sw = 3 if codepage & 0x80000000 else 2
        self.strings, pos, i = [''], 0, 4
        while i < len(pool):
            n, refs = struct.unpack_from('<HH', pool, i)
            i += 4
            if n == 0 and refs:  # long string: its length is the next entry, as 32 bits
                lo, hi = struct.unpack_from('<HH', pool, i)
                n, i = lo | hi << 16, i + 4
            self.strings.append(data[pos:pos + n].decode('cp1252', 'replace'))
            pos += n
        self.columns = collections.defaultdict(list)
        for table, num, col, typ in self.rows('_Columns', 'sisi'):
            self.columns[table].append((num, col, typ))

    def rows(self, table, kinds):
        """Rows of a table stored column by column; kinds: s (string), i (16 bits), l (32 bits)."""
        raw = self.cf.stream(self.streams['!' + table])
        widths = [self.sw if k == 's' else 2 if k == 'i' else 4 for k in kinds]
        n = len(raw) // sum(widths)
        cols, pos = [], 0
        for k, w in zip(kinds, widths):
            col = []
            for _ in range(n):
                v = int.from_bytes(raw[pos:pos + w], 'little')
                pos += w
                col.append(self.strings[v] if k == 's' else (v ^ (1 << (8 * w - 1))) if v else None)
            cols.append(col)
        return list(zip(*cols))

    def table(self, name):
        cols = sorted(self.columns[name])
        kinds = ''
        for _, _, typ in cols:
            typ ^= 0x8000
            kinds += 's' if typ & 0x0800 else 'i' if typ & 0xFF <= 2 else 'l'
        return [dict(zip([c for _, c, _ in cols], r)) for r in self.rows(name, kinds)]

# ---------------------------------------------------------------- cabinet


def cab_extract(f, wanted):
    """Write the cabinet entries named in `wanted` {entry: path}. MSZIP only."""
    head = f.read(36)
    assert head[:4] == b'MSCF', 'not a cabinet'
    files_off, = struct.unpack_from('<I', head, 16)
    nfolders, nfiles, flags = struct.unpack_from('<HHH', head, 26)
    assert flags == 0, 'cabinet flags %#x not handled' % flags
    folders = [struct.unpack('<IHH', f.read(8)) for _ in range(nfolders)]
    f.seek(files_off)
    by_folder = collections.defaultdict(list)
    for _ in range(nfiles):
        size, off, folder, _, _, attr = struct.unpack('<IIHHHH', f.read(16))
        name = b''
        while (c := f.read(1)) != b'\0':
            name += c
        name = name.decode('utf-8' if attr & 0x80 else 'latin-1')
        if name in wanted:
            by_folder[folder].append((off, size, wanted[name]))
    for folder, items in sorted(by_folder.items()):
        start, nblocks, typ = folders[folder]
        assert typ & 0xF == 1, 'cabinet compression %d not handled' % typ
        data, prev, pos = bytearray(), b'', start
        for _ in range(nblocks):
            f.seek(pos)
            _, csize, usize = struct.unpack('<IHH', f.read(8))
            block = f.read(csize)
            pos += 8 + csize
            assert block[:2] == b'CK', 'MSZIP block expected'
            z = zlib.decompressobj(-15, zdict=prev) if prev else zlib.decompressobj(-15)
            prev = z.decompress(block[2:])
            assert len(prev) == usize, 'MSZIP block size'
            data += prev
        for off, size, path in items:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'wb') as o:
                o.write(data[off:off + size])

# ---------------------------------------------------------------- COD


def cod_modules(data):
    """The COD modules of a .cod file: itself, or the siblings of its zip in numeric order."""
    if data[:2] != b'PK':
        return [data]
    import io
    z = zipfile.ZipFile(io.BytesIO(data))

    def order(n):
        m = re.search(r'-(\d+)\.cod$', n)
        return int(m.group(1)) if m else -1
    return [z.read(n) for n in sorted(z.namelist(), key=order)]


def _object(data, ptr, tag):
    """A data section object: u16 length, u16 tag (0xd004 bytes, 0xd824 string), content."""
    if ptr < 4 or ptr > len(data):
        return None
    n, t = struct.unpack_from('<HH', data, ptr - 4)
    return data[ptr:ptr + n] if t == tag and ptr + n <= len(data) else None


_REGISTER = re.compile(rb'(?s)\x2d\x02(..)(..)\x56\x3f\x28(..)\x40')
_ALSO = re.compile(rb'(?s)(?:\x01\xff\xff\x03|..)\xcd\x3f\x28(..)\x40')
_NAME = re.compile(rb'[\x21-\x7e][\x20-\x7e]*')


def module_resources(mod):
    """(name, bytes) of the resources a COD module registers.

    Header: u32 0xffffc0de, ..., at 38 u16 code size, at 40 u16 data size; code at 44, data
    right after. The static initialiser pushes each array (`2d 02 len ptr`), duplicates it, then
    registers it under one or more names (`28 ptr`, followed by an invoke)."""
    magic, = struct.unpack_from('<I', mod)
    if magic != 0xFFFFC0DE:
        return []
    code_size, data_size = struct.unpack_from('<HH', mod, 38)
    code, data = mod[44:44 + code_size], mod[44 + code_size:44 + code_size + data_size]
    out = []
    for m in _REGISTER.finditer(code):
        n, ptr, name = struct.unpack('<HHH', m.group(1) + m.group(2) + m.group(3))
        blob = _object(data, ptr, 0xD004)
        if blob is None or len(blob) != n:
            continue
        names, pos = [name], m.end()
        while (k := _ALSO.match(code, pos)):
            names.append(struct.unpack('<H', k.group(1))[0])
            pos = k.end()
        for p in names:
            s = _object(data, p, 0xD824)
            if s is not None and _NAME.fullmatch(s):
                out.append((s.decode('ascii'), blob))
    return out


def cod_resources(data):
    """{name: bytes} of a .cod file, chunks joined; names without a path dropped when the same
    bytes exist under a path ending with that name."""
    named, chunks = {}, collections.defaultdict(dict)
    for mod in cod_modules(data):
        for name, blob in module_resources(mod):
            m = re.fullmatch(r'__(.+)@(\d+)', name)
            if m:
                chunks[m.group(1)][int(m.group(2))] = blob
            else:
                named.setdefault(name, blob)
    for name, parts in chunks.items():
        joined = b''
        for off in sorted(parts):
            if off != len(joined):
                raise ValueError('%s: chunk at %d, expected %d' % (name, off, len(joined)))
            joined += parts[off]
        named[name] = joined
    with_path = {}
    for name, blob in named.items():
        if '/' in name:
            with_path.setdefault(name.rsplit('/', 1)[1], []).append(blob)
    return {n: b for n, b in named.items() if '/' in n or b not in with_path.get(n, [])}


def join_fonts(resources):
    """TrueType fonts split in parts `<font>.001`, `.002`... joined into `<font>.ttf`."""
    parts = collections.defaultdict(dict)
    for name, blob in resources.items():
        m = re.fullmatch(r'(.+)\.(\d{3})', name)
        if m:
            parts[m.group(1)][int(m.group(2))] = blob
    fonts = {}
    for base, p in parts.items():
        if p.get(1, b'')[:4] in (b'\0\1\0\0', b'OTTO', b'true') and sorted(p) == list(range(1, len(p) + 1)):
            fonts[base.rsplit('/', 1)[-1] + '.ttf'] = b''.join(p[k] for k in sorted(p))
    for name, blob in resources.items():
        if name.lower().endswith(('.ttf', '.sff4')):
            fonts[name.rsplit('/', 1)[-1]] = blob
    return fonts

# ---------------------------------------------------------------- main


def safe_join(root, *parts):
    rel = os.path.normpath(os.path.join(*parts))
    if rel.startswith('..') or os.path.isabs(rel):
        raise ValueError('unsafe path %r' % rel)
    return os.path.join(root, rel)


def put(path, blob):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as o:
        o.write(blob)


def default_assets():
    common = subprocess.run(['git', 'rev-parse', '--git-common-dir'], capture_output=True, text=True,
                            cwd=os.path.dirname(os.path.abspath(__file__)), check=True).stdout.strip()
    common = os.path.join(os.path.dirname(os.path.abspath(__file__)), common)
    return os.path.join(os.path.dirname(os.path.abspath(common)), 'assets-private')


def unpack_simulator(exe, tree):
    """NSIS installer of the simulator, unpacked by 7-Zip (which runs nothing): its Java/
    directory holds the same .cod files; returns the listing and that directory."""
    out = os.path.join(tree, 'loader')
    subprocess.run(['7zz', 'x', '-y', '-bso0', '-o' + out, exe, 'Java/*.cod', '*.alx'], check=True)
    rows = []
    for d, _, files in os.walk(out):
        for f in files:
            p = os.path.join(d, f)
            rows.append('%s\t%d' % (os.path.relpath(p, out), os.path.getsize(p)))
    return sorted(rows), os.path.join(out, 'Java')


def unpack(loader, tree):
    """InstallShield -> MSI -> cabinet -> tree/loader/<component>/<file>; returns the listing
    and the directory of the .cod files."""
    with open(loader, 'rb') as f:
        base = find_installshield(f)
        isdir = os.path.join(tree, 'installshield')
        os.makedirs(isdir)
        for name, off, size in is_entries(f, base):
            is_extract(f, name, off, size, os.path.join(isdir, name))
    with open(os.path.join(isdir, 'BlackBerry 9900.msi'), 'rb') as f:
        msi = Msi(f)
        device = {'OS', 'ALX', 'CodFiles', 'BinaryFiles', 'Readme'}
        wanted, listing = {}, []
        for r in msi.table('File'):
            if r['Component_'] in device:
                name = r['FileName'].split('|')[-1]
                wanted[r['File']] = safe_join(tree, 'loader', r['Component_'], name)
                listing.append('%s/%s\t%d' % (r['Component_'], name, r['FileSize']))
        cab = os.path.join(tree, 'Data1.cab')
        msi.cf.copy(msi.streams['Data1.cab'], cab)
    with open(cab, 'rb') as f:
        cab_extract(f, wanted)
    return sorted(listing), os.path.join(tree, 'loader', 'CodFiles')


def main(loader):
    h = hashlib.sha256()
    with open(loader, 'rb') as f:
        while (b := f.read(1 << 20)):
            h.update(b)
    if h.hexdigest() not in KNOWN:
        sys.exit('unknown loader (sha256 %s): only the Bold 9900 OS Loader 7.1.0.2807 and Simulator '
                 '7.1.0.318 are known' % h.hexdigest())
    name, workdir, kind = KNOWN[h.hexdigest()]
    assets = os.environ.get('ASSETS') or default_assets()
    work = os.path.join(assets, 'firmware', workdir, 'work')
    tree, out = os.path.join(work, 'tree'), os.path.join(assets, name)
    for d in (tree, out):
        shutil.rmtree(d, ignore_errors=True)
    os.makedirs(out)
    listing, cods = (unpack if kind == 'loader' else unpack_simulator)(loader, tree)

    rows, counts = [], collections.Counter()
    for cod in sorted(os.listdir(cods)):
        with open(os.path.join(cods, cod), 'rb') as f:
            res = cod_resources(f.read())
        stem = cod[:-4]
        rows += ['%s\t%s\t%d' % (cod, n, len(b)) for n, b in sorted(res.items())]
        if cod in THEME:
            for n, b in res.items():
                put(safe_join(out, 'theme', stem, n), b)
                counts['theme'] += 1
            continue
        for n, b in join_fonts(res).items():
            put(safe_join(out, 'fonts', stem, n), b)
            counts['fonts'] += 1
        for kind, exts in KINDS.items():
            if kind == 'images' and LOCALISED.search(cod) and not cod.endswith('__en.cod'):
                continue
            for n, b in res.items():
                if n.lower().rsplit('.', 1)[-1] in exts and (kind != 'boot' or 'video' in cod):
                    put(safe_join(out, kind, stem, n), b)
                    counts[kind] += 1

    sums = []
    for d, _, files in sorted(os.walk(out)):
        for x in sorted(files):
            p = os.path.join(d, x)
            with open(p, 'rb') as f:
                sums.append('%s  %s' % (hashlib.sha256(f.read()).hexdigest(), os.path.relpath(p, out)))
    with open(os.path.join(out, name + '.sha256'), 'w') as o:
        o.write(''.join(s + '\n' for s in sorted(sums, key=lambda s: s[66:])))
    for name, lines in (('files.txt', listing), ('resources.tsv', rows)):
        with open(os.path.join(work, name), 'w') as o:
            o.write(''.join(r + '\n' for r in lines))
    if os.environ.get('KEEP_TREE') != '1':
        shutil.rmtree(tree)
    for k in ('theme', 'images', 'sounds', 'fonts', 'boot'):
        print('%-7s %6d files' % (k, counts[k]))
    print('loader sha256 %s -> %s' % (h.hexdigest(), out))


if __name__ == '__main__':
    if len(sys.argv) != 2 or not os.path.isfile(sys.argv[1]):
        sys.exit(__doc__)
    main(sys.argv[1])
