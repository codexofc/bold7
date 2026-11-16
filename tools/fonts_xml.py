#!/usr/bin/env python3
"""Derives the module's /system/etc/fonts.xml from the phone's own: BBAlpha, the font of
BlackBerry OS 7.1 (firmware of the 9900, net_rim_font_latin_truetype), becomes the system
sans-serif, sans-serif-condensed, serif and monospace; everything else stays.

  python3 -I tools/fonts_xml.py <phone fonts.xml> <dir holding the BBAlpha TTFs> <output> [<dir holding the fallback TTFs>]

Text substitution, not an XML rewrite: the rest of the phone's file (fallbacks of the other
scripts, emoji, comments) is kept byte for byte. Each replaced family stays right after its
BBAlpha replacement, without its name: it becomes the first fallback, so a character BBAlpha
lacks is drawn as before. Aliases (sans-serif-light, sans-serif-medium...) point to the family
name and follow it.

The 9900's own fonts for the scripts BBAlpha lacks (BBGlobal, BBSemitic, BBThai, Univers for the
Indic scripts, DroidSansJapanese, MYuen) come as unnamed families right after BBAlpha Sans, ahead of
Android's: a character the firmware draws in one of them is drawn there, and any other still falls
through to Android's fallbacks (no tofu). The colour emoji stays Android's.

A broken fonts.xml can stop system_server from starting: the output is parsed and checked
before it is written, and every BBAlpha font it names must exist in the given directory. The
phone's own fonts it keeps are not checked here: they are those of the build whose fingerprint
tools/install.sh checks before installing.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

# Weight and style as read in each file's OS/2 table (usWeightClass, fsSelection), not from
# its name. The firmware has 400, 400 italic and 700 of each: no light, medium or bold italic,
# which Android draws from the nearest weight (and slants for bold italic).
BBALPHA = {
    'sans-serif': [
        (400, 'normal', 'BBAlphaSans.ttf'), (400, 'italic', 'BBAlphaSansItalic.ttf'),
        (700, 'normal', 'BBAlphaSansBold.ttf'),
    ],
    'sans-serif-condensed': [
        (400, 'normal', 'BBAlphaSansCondensed.ttf'), (400, 'italic', 'BBAlphaSansCondensedItalic.ttf'),
        (700, 'normal', 'BBAlphaSansCondensedBold.ttf'),
    ],
    'serif': [
        (400, 'normal', 'BBAlphaSerif.ttf'), (400, 'italic', 'BBAlphaSerifItalic.ttf'),
        (700, 'normal', 'BBAlphaSerifBold.ttf'),
    ],
    'monospace': [
        (400, 'normal', 'BBAlphaSansMono.ttf'), (400, 'italic', 'BBAlphaSansMonoItalic.ttf'),
        (700, 'normal', 'BBAlphaSansMonoBold.ttf'),
    ],
}

# The 9900's fallbacks, in the order of its chain. No lang means any text;
# a lang only makes the family preferred for that script or locale.
FALLBACKS = [
    (None, [(400, 'normal', 'BBGlobalSans.ttf'), (700, 'normal', 'BBGlobalSans-Bold.ttf')]),
    ('und-Hebr,und-Arab', [(400, 'normal', 'BBSemitic.ttf')]),
    ('und-Thai', [(400, 'normal', 'BBThai.ttf')]),
    ('und-Deva', [(400, 'normal', 'UniversOTSDevanagari.ttf')]),
    ('und-Beng', [(400, 'normal', 'UniversOTSBengali.ttf')]),
    ('und-Gujr', [(400, 'normal', 'UniversOTSGujarati.ttf')]),
    ('und-Taml', [(400, 'normal', 'UniversOTSTamil.ttf')]),
    ('ja', [(400, 'normal', 'DroidSansJapanese.ttf')]),
    ('zh-Hant', [(400, 'normal', 'MYuenC-SemiBold-Big5HKSCS.ttf')]),
    (None, [(400, 'normal', 'BBGlobalSansFallback.ttf')]),  # CJK, Hangul, arrows, maths, shapes
]
FALLBACK_FILES = [f for _, fonts in FALLBACKS for _, _, f in fonts]


def fallback_block(ind):
    out = ''
    for lang, fonts in FALLBACKS:
        attr = ' lang="%s"' % lang if lang else ''
        out += f'{ind}<family{attr}>\n'
        out += ''.join(f'{ind}    <font weight="{w}" style="{s}">{f}</font>\n' for w, s, f in fonts)
        out += f'{ind}</family>\n'
    return out


def derive(xml):
    # SlatePro: the fonts.xml of the BB10 module (Cascade), mounted the same way.
    if 'BBAlpha' in xml or 'SlatePro' in xml:
        raise ValueError('already derived: this is the module fonts.xml, not the stock one')
    for name, fonts in BBALPHA.items():
        found = list(re.finditer(r'(?m)^(?P<indent>[ \t]*)<family\s+name="%s"(?P<rest>[^>]*)>' % re.escape(name), xml))
        if len(found) != 1:
            raise ValueError(f'expected one <family name="{name}">, found {len(found)}')
        m = found[0]
        ind = m['indent']
        ours = (f'{ind}<family name="{name}">\n'
                 + ''.join(f'{ind}    <font weight="{w}" style="{s}">{f}</font>\n' for w, s, f in fonts)
                 + f'{ind}</family>\n'
                 + (fallback_block(ind) if name == 'sans-serif' else '')
                 + f'{ind}<family{m["rest"]}>')
        xml = xml[:m.start()] + ours + xml[m.end():]
    first = ET.fromstring(xml.encode()).find('family')
    if first.get('name') != 'sans-serif' or 'BBAlpha' not in first.find('font').text:
        raise ValueError('the first family is not BBAlpha sans-serif')
    return xml


def main(src, fonts_dir, out, fallback_dir=None):
    with open(src, encoding='utf-8') as f:
        derived = derive(f.read())
    fallback_dir = fallback_dir or fonts_dir
    missing = [f for fonts in BBALPHA.values() for _, _, f in fonts if not os.path.isfile(os.path.join(fonts_dir, f))]
    missing += [f for f in FALLBACK_FILES if not os.path.isfile(os.path.join(fallback_dir, f))]
    if missing:
        sys.exit(f'missing in {fonts_dir}: {", ".join(missing)} (run tools/import-assets.sh)')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as f:
        f.write(derived)


if __name__ == '__main__':
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    main(*sys.argv[1:])
