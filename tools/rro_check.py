#!/usr/bin/env python3
"""Checks the module's RROs against the phone they are built for.

  python3 -I tools/rro_check.py <aapt2> <framework-res.apk> <overlay.apk>=<target.apk>...

framework-res.apk and the targets are the phone's own (stubs/device, pulled by tools/stubs.sh).
For each overlay:
- every framework resource it names (0x01…: a value, a style's parent or item, an XML attribute or
  reference) is one framework-res has. An RRO keeps each reference as an id, and the private ids of
  LineageOS are not the SDK's: an id framework-res lacks means the overlay was linked against
  something else (the system/overlays build links against this very file);
- every resource it overlays exists in its target, except its own, whose name holds "bold7": a
  value for a name the target dropped is silently never used (Lineage renames from one build to
  the next).
Exits non-zero, naming each mismatch, otherwise prints nothing.

Deliberately simple: names only. A resource the target keeps under a more specific configuration than
the overlay's (night, land, sw600dp...) is not checked: the overlay loses there without a sound.
"""
import re
import subprocess
import sys
import zipfile

RESOURCE = re.compile(r'^\s+resource (0x[0-9a-f]{8}) (\S+)', re.M)
# A reference (@, ?), a style's parent or item key, an attribute of a compiled XML: "(0x0101…)".
FRAMEWORK_REF = re.compile(r'(?:[@?(]|parent=|^\s+)(0x01[0-9a-f]{6})\b', re.M)


def resources(dump):
    """`aapt2 dump resources` -> {id: type/name}; private attributes under their plain name."""
    return {i: n.replace('^attr-private/', 'attr/') for i, n in RESOURCE.findall(dump)}


def framework_refs(text):
    """The framework ids an `aapt2 dump resources` or `dump xmltree` text names."""
    return set(FRAMEWORK_REF.findall(text))


def mismatches(name, overlay_dump, overlay_xml, target_names, framework_ids):
    """Messages for each framework id unknown to framework-res, and each overlaid name the target lacks."""
    errors = [f'{name}: {i} is no resource of the phone\'s framework-res (linked against another one?)'
              for i in sorted(framework_refs(overlay_dump + overlay_xml) - framework_ids)]
    errors += [f'{name}: {n} is not in its target any more, drop or replace it'
               for n in sorted(set(resources(overlay_dump).values()) - target_names) if 'bold7' not in n.lower()]
    return errors


def main(aapt2, framework_res, pairs):
    def dump(apk):
        return subprocess.run([aapt2, 'dump', 'resources', apk], check=True, capture_output=True, text=True).stdout
    fw = dump(framework_res)
    framework_ids, dumps = set(resources(fw)), {framework_res: fw}
    errors = []
    for pair in pairs:
        overlay, target = pair.split('=', 1)
        if target not in dumps:
            dumps[target] = dump(target)
        with zipfile.ZipFile(overlay) as z:
            xmls = [n for n in z.namelist() if n.endswith('.xml') and not n.startswith('META-INF/')]
        xml = ''.join(subprocess.run([aapt2, 'dump', 'xmltree', '--file', x, overlay], check=True,
                                     capture_output=True, text=True).stdout for x in xmls)
        errors += mismatches(overlay.rsplit('/', 1)[-1], dump(overlay), xml,
                             set(resources(dumps[target]).values()), framework_ids)
    print('\n'.join(errors), file=sys.stderr, end='\n' if errors else '')
    return 1 if errors else 0


if __name__ == '__main__':
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
