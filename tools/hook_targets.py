#!/usr/bin/env python3
"""Check the SystemUI hook targets of Bold7 against a ROM's own binaries, before it reaches the phone.

    tools/hook_targets.py [--targets system/sysui/src/main/resources/hook-targets.tsv] <container>...

A container is an APK or jar (its classesN.dex read through the SDK's `dexdump`) or a text file
already produced by `dexdump`. Give SystemUI.apk and framework.jar of the same build: a few hooks
sit in framework classes loaded by SystemUI. For each row of the targets file, says whether the
class and the member are there with the same parameters; for a missing one, where a member of
that name (or a class of that simple name) lives now. Prints a Markdown table and one verdict per
group (a group goes in whole or not at all); exits 1 if any hook or use is missing. Stdlib only.

The rules are the module's own (system/sysui TargetTable.kt): exact class, name and parameters; a
name ending in "$*" matches the bare name or that name and one suffix, and only when exactly one
method of the class has it with these parameters (two: "ambiguous", the group off). Run it on each
new build of LineageOS before it reaches the phone.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

PARAMS = re.compile(r"\((\[*([ZBCSIJFD]|L[^;]+;))*\)")


def parse_dexdump(lines, index=None):
    """{class: {"m": {(name, type)}, "f": {name}}} from `dexdump` output (default or -h)."""
    index = index if index is not None else {}
    cls = section = name = None
    for line in lines:
        s = line.strip()
        if s.startswith("Class descriptor"):
            cls = s.split("'")[1][1:-1].replace("/", ".")
            index.setdefault(cls, {"m": set(), "f": set()})
        elif s.startswith(("Static fields", "Instance fields")):
            section = "f"
        elif s.startswith(("Direct methods", "Virtual methods")):
            section = "m"
        elif s.startswith(("Interfaces", "source_file_idx")):
            section = None
        elif cls and section and s.startswith("name ") and "'" in s:
            name = s.split("'")[1]
            if section == "f":
                index[cls]["f"].add(name)
        elif cls and section == "m" and name and s.startswith("type ") and "'" in s:
            index[cls]["m"].add((name, s.split("'")[1]))
            name = None
    return index


def _dexdump():
    if os.environ.get("DEXDUMP"):
        return os.environ["DEXDUMP"]
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    found = sorted(glob.glob(f"{sdk}/build-tools/*/dexdump"),
                   key=lambda p: [int(x) for x in re.findall(r"\d+", p.split("/")[-2])])
    if not found:
        sys.exit("dexdump not found: set DEXDUMP or ANDROID_HOME (SDK build-tools)")
    return found[-1]


def read_container(path, index):
    if not zipfile.is_zipfile(path):
        with open(path, errors="replace") as f:
            return parse_dexdump(f, index)
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(path) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        z.extractall(tmp, dexes)
        for dex in dexes:
            proc = subprocess.Popen([_dexdump(), os.path.join(tmp, dex)], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, text=True, errors="replace")
            parse_dexdump(proc.stdout, index)
            if proc.wait() != 0:
                sys.exit(f"dexdump failed on {path}!{dex}")
    return index


def read_targets(path):
    rows = []
    for line in open(path):
        if line.strip() and not line.startswith("#"):
            group, kind, cls, member, sig = line.rstrip("\n").split("\t")
            if kind not in ("hook", "uses") or not (sig == "-" or PARAMS.fullmatch(sig)):
                sys.exit(f"bad row in {path}: {line.rstrip()}")
            rows.append((group, kind, cls, member, sig))
    return rows


def _name_matches(name, member):
    if not member.endswith("$*"):
        return name == member
    return name == member[:-2] or name.startswith(member[:-2] + "$")


def _sig_matches(dex_type, sig):
    return dex_type.startswith(sig)


def check(index, row):
    """(status, detail) of one target: present, missing class, missing member, signature changed."""
    _, _, cls, member, sig = row
    if cls not in index:
        simple = cls.rsplit(".", 1)[-1]
        moved = sorted(c for c in index if c.rsplit(".", 1)[-1] == simple)
        return "class missing", ", ".join(moved[:3])
    entry = index[cls]
    if sig == "-":
        if member in entry["f"]:
            return "present", ""
        return "member missing", ""
    same_name = [t for n, t in entry["m"] if _name_matches(n, member)]
    hit = sorted({n for n, t in entry["m"] if _name_matches(n, member) and _sig_matches(t, sig)})
    if len(hit) > 1:
        return "ambiguous", ", ".join(hit)
    if hit:
        return "present", hit[0] if member.endswith("$*") else ""
    if same_name:
        return "signature changed", "; ".join(sorted(same_name)[:3])
    elsewhere = sorted(c for c, e in index.items() if any(_name_matches(n, member) and _sig_matches(t, sig) for n, t in e["m"]))
    return "member missing", ("elsewhere: " + ", ".join(elsewhere[:3])) if elsewhere else ""


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--targets", default=os.path.join(os.path.dirname(__file__), "..", "system", "sysui", "src", "main", "resources", "hook-targets.tsv"))
    ap.add_argument("containers", nargs="+")
    args = ap.parse_args(argv)
    index = {}
    for path in args.containers:
        read_container(path, index)
    rows = read_targets(args.targets)
    print("| Group | Kind | Target | Status | Detail |\n|---|---|---|---|---|")
    broken = defaultdict(int)
    total = defaultdict(int)
    for row in rows:
        status, detail = check(index, row)
        group, kind, cls, member, sig = row
        total[group] += 1
        if status != "present":
            broken[group] += 1
        print(f"| {group} | {kind} | `{cls}.{member}{'' if sig == '-' else sig}` | {status} | {detail} |")
    print()
    for group in total:
        verdict = "complete" if not broken[group] else f"{broken[group]} of {total[group]} missing: group off"
        print(f"{group}: {verdict}")
    hooks = [r for r in rows if r[1] == "hook"]
    missing_hooks = sum(1 for r in hooks if check(index, r)[0] != "present")
    print(f"\n{len(hooks)} hooks, {missing_hooks} missing; {len(rows) - len(hooks)} uses")
    return 1 if any(broken.values()) else 0


if __name__ == "__main__":
    sys.exit(main())
