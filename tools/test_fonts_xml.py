"""Tests for fonts_xml.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import difflib
import os
import tempfile
import unittest
import xml.etree.ElementTree as ET

import fonts_xml

# Shaped like /system/etc/fonts.xml of Android 14 (AOSP android-14.0.0_r28), shortened.
DEVICE = '''<?xml version="1.0" encoding="utf-8"?>
<!--
    In this file, all fonts without names are added to the default list.
-->
<familyset version="23">
    <!-- first font is default -->
    <family name="sans-serif">
        <font weight="400" style="normal">Roboto-Regular.ttf
          <axis tag="ital" stylevalue="0" />
          <axis tag="wdth" stylevalue="100" />
          <axis tag="wght" stylevalue="400" />
        </font>
        <font weight="700" style="italic">Roboto-Regular.ttf
          <axis tag="ital" stylevalue="1" />
          <axis tag="wdth" stylevalue="100" />
          <axis tag="wght" stylevalue="700" />
        </font>
   </family>


    <!-- Note that aliases must come after the fonts they reference. -->
    <alias name="sans-serif-thin" to="sans-serif" weight="100" />
    <alias name="sans-serif-light" to="sans-serif" weight="300" />
    <alias name="arial" to="sans-serif" />

    <family name="sans-serif-condensed">
      <font weight="400" style="normal">Roboto-Regular.ttf
        <axis tag="ital" stylevalue="0" />
        <axis tag="wdth" stylevalue="75" />
        <axis tag="wght" stylevalue="400" />
      </font>
    </family>
    <alias name="sans-serif-condensed-light" to="sans-serif-condensed" weight="300" />

    <family name="serif">
        <font weight="400" style="normal" postScriptName="NotoSerif">NotoSerif-Regular.ttf</font>
    </family>
    <alias name="georgia" to="serif" />

    <family name="monospace">
        <font weight="400" style="normal">DroidSansMono.ttf</font>
    </family>
    <family lang="und-Arab" variant="elegant">
        <font weight="400" style="normal" postScriptName="NotoNaskhArabic">NotoNaskhArabic-Regular.ttf</font>
    </family>
    <family lang="und-Zsye">
        <font weight="400" style="normal">NotoColorEmoji.ttf</font>
    </family>
</familyset>
'''


def families(xml):
    return ET.fromstring(xml.encode()).findall('family')


def fonts(family):
    return [(int(f.get('weight')), f.get('style'), f.text.strip()) for f in family.findall('font')]


FALLBACK_LINES = [l.strip() for l in fonts_xml.fallback_block('').splitlines()]


class DeriveTest(unittest.TestCase):
    def setUp(self):
        self.out = fonts_xml.derive(DEVICE)

    def test_bbalpha_sans_is_the_first_family_with_its_real_weights(self):
        first = families(self.out)[0]
        self.assertEqual(first.get('name'), 'sans-serif')
        self.assertEqual(fonts(first), [
            (400, 'normal', 'BBAlphaSans.ttf'), (400, 'italic', 'BBAlphaSansItalic.ttf'),
            (700, 'normal', 'BBAlphaSansBold.ttf'),
        ])

    def test_condensed_serif_and_monospace_are_bbalpha(self):
        named = {f.get('name'): f for f in families(self.out) if f.get('name')}
        self.assertEqual(fonts(named['sans-serif-condensed']), [
            (400, 'normal', 'BBAlphaSansCondensed.ttf'), (400, 'italic', 'BBAlphaSansCondensedItalic.ttf'),
            (700, 'normal', 'BBAlphaSansCondensedBold.ttf'),
        ])
        self.assertEqual(fonts(named['serif']), [
            (400, 'normal', 'BBAlphaSerif.ttf'), (400, 'italic', 'BBAlphaSerifItalic.ttf'),
            (700, 'normal', 'BBAlphaSerifBold.ttf'),
        ])
        self.assertEqual(fonts(named['monospace']), [
            (400, 'normal', 'BBAlphaSansMono.ttf'), (400, 'italic', 'BBAlphaSansMonoItalic.ttf'),
            (700, 'normal', 'BBAlphaSansMonoBold.ttf'),
        ])

    def test_each_replaced_family_stays_right_after_as_an_unnamed_fallback(self):
        fam = families(self.out)
        names = [f.get('name') for f in fam]
        for name, wdth in (('sans-serif', '100'), ('sans-serif-condensed', '75')):
            kept = fam[names.index(name) + 1 + (len(fonts_xml.FALLBACKS) if name == 'sans-serif' else 0)]
            self.assertIsNone(kept.get('name'))
            self.assertEqual(kept.find('font').text.strip(), 'Roboto-Regular.ttf')
            self.assertEqual(kept.find("font/axis[@tag='wdth']").get('stylevalue'), wdth)
        for name, font in (('serif', 'NotoSerif-Regular.ttf'), ('monospace', 'DroidSansMono.ttf')):
            kept = fam[names.index(name) + 1]
            self.assertIsNone(kept.get('name'))
            self.assertEqual(kept.find('font').text.strip(), font)

    def test_nothing_else_changes(self):
        diff = list(difflib.ndiff(DEVICE.splitlines(), self.out.splitlines()))
        removed = [l[2:].strip() for l in diff if l.startswith('- ')]
        added = [l[2:].strip() for l in diff if l.startswith('+ ')]
        # Only insertions: the BBAlpha family opens on the original line, the original
        # family goes on as an unnamed <family>.
        self.assertEqual(removed, [])
        self.assertEqual(sorted(l for l in added if 'BBAlpha' not in l), sorted(['</family>', '<family>'] * 4 + FALLBACK_LINES))

    def test_other_attributes_of_a_replaced_family_are_kept_on_the_fallback(self):
        out = fonts_xml.derive(DEVICE.replace('<family name="sans-serif">', '<family name="sans-serif" variant="compact">'))
        self.assertIsNone(families(out)[0].get('variant'))
        self.assertEqual(families(out)[1 + len(fonts_xml.FALLBACKS)].get('variant'), 'compact')

    def test_firmware_fallbacks_come_right_after_bbalpha_and_before_android_s(self):
        fam = families(self.out)
        after = fam[1:1 + len(fonts_xml.FALLBACKS)]
        self.assertEqual([(f.get('lang'), [x[2] for x in fonts(f)]) for f in after],
                         [(lang, [x[2] for x in fs]) for lang, fs in fonts_xml.FALLBACKS])
        self.assertTrue(all(f.get('name') is None for f in after))
        # Android's own Roboto, Arabic and emoji families follow, in their order
        rest = [(f.get('name'), f.get('lang'), fonts(f)[0][2]) for f in fam[1 + len(after):]]
        self.assertEqual(rest[0], (None, None, 'Roboto-Regular.ttf'))
        self.assertIn((None, 'und-Arab', 'NotoNaskhArabic-Regular.ttf'), rest)
        self.assertEqual(rest[-1], (None, 'und-Zsye', 'NotoColorEmoji.ttf'))

    def test_no_family_is_lost(self):
        # Every stock family is still there, once more than before only for the firmware ones.
        stock = [(f.get('lang'), fonts(f)[0][2]) for f in families(DEVICE)]
        out = [(f.get('lang'), fonts(f)[0][2]) for f in families(self.out)]
        for entry in stock:
            self.assertIn(entry, out)
        self.assertEqual(len(out), len(stock) + 4 + len(fonts_xml.FALLBACKS))

    def test_the_colour_emoji_is_not_replaced(self):
        self.assertNotIn('Emoji', ' '.join(f for _, fs in fonts_xml.FALLBACKS for _, _, f in fs))

    def test_every_named_font_must_exist(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as fb:
            src = os.path.join(d, 'fonts.xml')
            open(src, 'w').write(DEVICE)
            for _, fs in fonts_xml.BBALPHA.items():
                for _, _, f in fs:
                    open(os.path.join(d, f), 'wb').close()
            out = os.path.join(d, 'out', 'fonts.xml')
            with self.assertRaises(SystemExit) as e:
                fonts_xml.main(src, d, out, fb)
            self.assertIn('BBSemitic.ttf', str(e.exception))
            for f in fonts_xml.FALLBACK_FILES:
                open(os.path.join(fb, f), 'wb').close()
            fonts_xml.main(src, d, out, fb)
            named = {x.text.strip() for x in ET.parse(out).getroot().iter('font') if x.text.strip().startswith(('BB', 'Univers', 'Droid', 'MYuen'))}
            have = set(os.listdir(d)) | set(os.listdir(fb))
            self.assertLessEqual(named - {'DroidSansMono.ttf'}, have)

    def test_refuses_a_file_already_derived(self):
        # The phone's fonts.xml read while the module is mounted is ours, not the stock one.
        with self.assertRaisesRegex(ValueError, 'already'):
            fonts_xml.derive(self.out)

    def test_refuses_the_file_of_the_bb10_module(self):
        # Cascade's module mounted Slate Pro the same way: its fonts.xml is not the stock one either.
        slate = DEVICE.replace('Roboto-Regular.ttf', 'SlatePro.ttf', 1)
        with self.assertRaisesRegex(ValueError, 'already'):
            fonts_xml.derive(slate)

    def test_refuses_a_file_without_the_family(self):
        with self.assertRaisesRegex(ValueError, 'sans-serif-condensed'):
            fonts_xml.derive(DEVICE.replace('name="sans-serif-condensed"', 'name="other"'))

    def test_refuses_a_family_found_twice(self):
        twice = DEVICE.replace('<family name="serif">', '<family name="sans-serif">')
        with self.assertRaisesRegex(ValueError, 'sans-serif'):
            fonts_xml.derive(twice)


if __name__ == '__main__':
    unittest.main()
