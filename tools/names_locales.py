#!/usr/bin/env python3
"""The languages of the label of each app of system/overlays/names/names.tsv.

Usage: names_locales.py <dir> > system/overlays/names/names-locales.tsv
<dir>/<package>/*.apk: the APKs (base and splits) pulled from the phone by `adb pull`.
Reads `aapt2 dump resources` (read only). One line per (package, resource):
package, resource, the values-<q> folder qualifiers separated by commas.
fr and fr-rFR are always there: a language split missing from the phone would not hide them.
"""
import glob, re, subprocess, sys

ALWAYS = ["fr", "fr-rFR"]
CFG = re.compile(r"^\s*\(([A-Za-z0-9+-]*)\)\s")


def locales(dump, name):
    """The qualifiers (default excluded) where string/<name> has a value, in an `aapt2 dump resources`."""
    out, on = set(), False
    for line in dump.splitlines():
        if "resource " in line:
            on = line.rstrip().endswith("string/" + name)
        elif on and (m := CFG.match(line)):
            if m.group(1):
                out.add(m.group(1))
    return out


def table(rows, dumps):
    """rows: (package, resource); dumps: package -> [aapt2 text]. TSV lines, no duplicate."""
    res = []
    for pkg, name in dict.fromkeys(rows):
        q = set(ALWAYS)
        for d in dumps.get(pkg, []):
            q |= locales(d, name)
        res.append(f"{pkg}\t{name}\t{','.join(sorted(q))}")
    return res


if __name__ == "__main__":
    root, names = sys.argv[1], "system/overlays/names/names.tsv"
    rows = [tuple(l.split("\t")[1:3]) for l in open(names) if l.strip() and not l.startswith("#")]
    dumps = {p: [subprocess.run(["aapt2", "dump", "resources", a], capture_output=True, text=True).stdout
                 for a in sorted(glob.glob(f"{root}/{p}/*.apk"))] for p, _ in rows}
    print("\n".join(table(rows, dumps)))
