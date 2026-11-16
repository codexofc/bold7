"""Tests of tools/extract-bbos7.py on fabricated data, without the firmware.

    python3 -m unittest discover -s tools -p 'test_extract_bbos7.py'
"""
import importlib.util
import io
import os
import struct
import tempfile
import unittest
import zipfile
import zlib

_spec = importlib.util.spec_from_file_location(
    'extract_bbos7', os.path.join(os.path.dirname(__file__), 'extract-bbos7.py'))
x = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(x)

PNG = b'\x89PNG\r\n\x1a\n' + b'\0\0\0\rIHDR' + struct.pack('>II', 2, 3) + b'rest-of-png'


def is_encode(data, name):
    """Inverse of the loader's obfuscation: b = rot4(~p ^ key), the key restarting every 1024 bytes."""
    out = bytearray()
    for i in range(0, len(data), 1024):
        block = data[i:i + 1024]
        key = x.is_decode_block(bytes(len(block)), name)  # decoding zeros gives ~key
        out += bytes(x._ROT4[p ^ k] for p, k in zip(block, key))
    return bytes(out)


def installshield(files, decoy=False):
    """A container: signature, file count, then per file 312 bytes of attributes and the data."""
    out = x.IS_SIG + b'\0\0' + bytes(30) if decoy else b''
    out += x.IS_SIG + struct.pack('<H', len(files)) + bytes(30)
    for name, payload in files:
        enc = is_encode(zlib.compress(payload), name)
        out += name.encode().ljust(260, b'\0') + struct.pack('<III', 6, 0, len(enc)) + bytes(8)
        out += struct.pack('<H', 1) + bytes(30) + enc
    return out


def cod_module(resources):
    """A COD module whose static initialiser registers each (names, bytes) resource."""
    data, ptrs = bytearray(b'\0' * 8), []

    def add(blob, tag):
        while len(data) % 4:
            data.append(0)
        data.extend(struct.pack('<HH', len(blob), tag))
        ptrs.append(len(data))
        data.extend(blob)
        return len(data) - len(blob)
    code = bytearray()
    for names, blob in resources:
        p = add(blob, 0xD004)
        code += b'\x2d\x02' + struct.pack('<HH', len(blob), p) + b'\x56\x3f'
        for k, name in enumerate(names):
            q = add(name.encode(), 0xD824)
            code += (b'\x3f' if k else b'') + b'\x28' + struct.pack('<H', q) + b'\x40\x01\xff\xff\x03\xcd'
    code += b'\x1f\x00'
    head = struct.pack('<IIIIIIHhii', 0xFFFFC0DE, 0, 0, 0, 0, 0, 5, -1, -1, -1)
    head += struct.pack('<HHHH', 79, len(code), len(data), 1)
    return head + bytes(code) + bytes(data)


class InstallShieldTest(unittest.TestCase):
    def test_known_vectors(self):
        # bytes of the 9900 loader: the start of SplashScreen.BMP (a zlib header once decoded),
        # and the start of the second 1024-byte block of 0x0405.ini, whose 10-byte key restarts
        self.assertEqual(x.is_decode_block(bytes.fromhex('7c628f44e96243c4'), 'SplashScreen.BMP'),
                         bytes.fromhex('789ceddd01841ed7'))
        self.assertEqual(x.is_decode_block(bytes.fromhex('3c58bad199bebaaebd5eac64'), '0x0405.ini'),
                         bytes.fromhex('1f37e2d14514fc7b594616f4'))

    def test_header_and_decoding(self):
        big = bytes(range(256)) * 20  # several 1024-byte blocks, key of 9 bytes
        blob = installshield([('Setup.ini', b'[Info]\r\n'), ('BlackBerry 9900.msi', big)], decoy=True)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'loader.exe')
            with open(path, 'wb') as f:
                f.write(b'MZ' + bytes(100) + blob)
            with open(path, 'rb') as f:
                base = x.find_installshield(f)
                self.assertEqual(base, 102 + 0x2E)  # the empty decoy is skipped
                entries = x.is_entries(f, base)
                self.assertEqual([n for n, _, _ in entries], ['Setup.ini', 'BlackBerry 9900.msi'])
                for (name, off, size), want in zip(entries, (b'[Info]\r\n', big)):
                    out = os.path.join(d, name)
                    x.is_extract(f, name, off, size, out)
                    with open(out, 'rb') as o:
                        self.assertEqual(o.read(), want)

    def test_refuses_path_in_name(self):
        blob = installshield([('../evil', b'x')])
        with self.assertRaises(ValueError):
            x.is_entries(io.BytesIO(blob), 0)


class CodTest(unittest.TestCase):
    def test_resources_of_a_module(self):
        mod = cod_module([(['img/icon.png', 'icon.png'], PNG), (['tune.mid'], b'MThd\0\0\0\6')])
        self.assertEqual(x.module_resources(mod),
                         [('img/icon.png', PNG), ('icon.png', PNG), ('tune.mid', b'MThd\0\0\0\6')])
        # the name without its path is dropped, the bytes being the same
        self.assertEqual(x.cod_resources(mod), {'img/icon.png': PNG, 'tune.mid': b'MThd\0\0\0\6'})

    def test_not_a_module(self):
        self.assertEqual(x.module_resources(b'\0' * 64), [])

    def test_chunks_over_siblings(self):
        big = PNG + bytes(range(256)) * 300
        a, b = big[:40000], big[40000:]
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w') as z:  # order in the zip differs from the numeric one
            z.writestr('t-2.cod', cod_module([(['__wall.png@40000'], b)]))
            z.writestr('t.cod', cod_module([(['other.gif'], b'GIF89a')]))
            z.writestr('t-1.cod', cod_module([(['__wall.png@0', 'wall.png'], a)]))
        res = x.cod_resources(buf.getvalue())
        self.assertEqual(res['wall.png'], big)
        self.assertEqual(res['other.gif'], b'GIF89a')

    def test_missing_chunk(self):
        mod = cod_module([(['__a.png@0'], b'x' * 10), (['__a.png@20'], b'y')])
        with self.assertRaises(ValueError):
            x.cod_resources(mod)

    def test_fonts_joined(self):
        res = {'ttf/raw_split/Alpha.001': b'\0\1\0\0head', 'ttf/raw_split/Alpha.002': b'tail',
               'data/map.001': b'notafont', 'sff/RIM_european.sff4': b'sff4'}
        self.assertEqual(x.join_fonts(res), {'Alpha.ttf': b'\0\1\0\0headtail', 'RIM_european.sff4': b'sff4'})


class MsiTest(unittest.TestCase):
    def test_stream_names(self):
        self.assertEqual(x.msi_name(chr(0x4840) + chr(0x3800 + 10 + (11 << 6))), '!AB')


class LoaderTest(unittest.TestCase):
    def test_unknown_loader_refused(self):
        with tempfile.NamedTemporaryFile() as f:
            f.write(installshield([('Setup.ini', b'x')]))
            f.flush()
            with self.assertRaises(SystemExit) as e:
                x.main(f.name)
            self.assertIn('unknown loader', str(e.exception))


if __name__ == '__main__':
    unittest.main()
