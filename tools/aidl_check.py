#!/usr/bin/env python3
"""Check that the quickstep AIDL of the repository match the interfaces compiled in an APK.

    tools/aidl_check.py <apk> <aidl_dir>

For every interface with a body under <aidl_dir>, reads from the APK's dex (via the SDK's
`dexdump -d`) the DESCRIPTOR, the TRANSACTION_* codes of the Stub, the method signatures
and whether the Proxy waits for a reply (oneway or not), then compares them to the AIDL:
name, transaction code, parameter and return types, oneway. Prints a Markdown table and
the gaps; exits 1 if there is any gap. Python 3 stdlib only.
"""
import glob
import itertools
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from typing import NamedTuple

FIRST_CALL_TRANSACTION = 1
PRIMITIVES = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
              "int": "I", "long": "J", "float": "F", "double": "D"}
BUILTINS = {"String": "java.lang.String", "CharSequence": "java.lang.CharSequence",
            "IBinder": "android.os.IBinder", "List": "java.util.List", "Map": "java.util.Map",
            "FileDescriptor": "java.io.FileDescriptor"}


class Method(NamedTuple):
    code: int | None  # transaction code as sent on the wire
    params: tuple  # JVM descriptors
    ret: str  # JVM descriptor
    oneway: bool | None  # None: no Stub$Proxy in the dex, so unknown


class Interface(NamedTuple):
    descriptor: str | None
    methods: dict


def _strip_comments(text):
    return re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", text, flags=re.S))


def _jvm(type_name, package, imports):
    dims = type_name.count("[]")
    base = re.sub(r"<.*>", "", type_name.replace("[]", "")).strip()
    if base in PRIMITIVES:
        desc = PRIMITIVES[base]
    else:
        fq = base if "." in base else imports.get(base) or BUILTINS.get(base) or f"{package}.{base}"
        parts = fq.split(".")
        # The first capitalised segment is the outer class; what follows is nested ($).
        first_class = next(i for i, p in enumerate(parts) if p[:1].isupper())
        desc = "L" + "/".join(parts[:first_class]) + "/" + "$".join(parts[first_class:]) + ";"
    return "[" * dims + desc


def _split_params(text):
    params, depth, cur = [], 0, ""
    for ch in text:
        depth += ch == "<"
        depth -= ch == ">"
        if ch == "," and depth == 0:
            params.append(cur)
            cur = ""
        else:
            cur += ch
    return [p.strip() for p in params + [cur] if p.strip()]


def parse_aidl(text):
    """Return (qualified name, {method: Method}) or None for a declaration without a body."""
    text = _strip_comments(text)
    package = re.search(r"\bpackage\s+([\w.]+)\s*;", text).group(1)
    imports = {fq.rsplit(".", 1)[1]: fq for fq in re.findall(r"\bimport\s+([\w.]+)\s*;", text)}
    m = re.search(r"\b(oneway\s+)?interface\s+(\w+)\s*\{(.*)\}", text, re.S)
    if not m:
        return None
    all_oneway, name, body = bool(m.group(1)), m.group(2), m.group(3)
    methods = {}
    for index, stmt in enumerate(s for s in (" ".join(x.split()) for x in body.split(";")) if s):
        stmt = re.sub(r"@\w+(\([^)]*\))?\s*", "", stmt)
        mm = re.fullmatch(r"(oneway\s+)?(.+?)\s+(\w+)\s*\((.*)\)\s*(?:=\s*(\d+))?", stmt)
        if not mm:
            raise ValueError(f"{name}: cannot parse '{stmt}'")
        params = tuple(
            _jvm(re.sub(r"^(in|out|inout)\s+", "", p).rsplit(" ", 1)[0], package, imports)
            for p in _split_params(mm.group(4))
        )
        aidl_id = int(mm.group(5)) if mm.group(5) else index
        methods[mm.group(3)] = Method(aidl_id + FIRST_CALL_TRANSACTION, params,
                                      _jvm(mm.group(2), package, imports),
                                      all_oneway or bool(mm.group(1)))
    return f"{package}.{name}", methods


def _split_descriptor(sig):
    params, ret = re.fullmatch(r"\((.*)\)(.+)", sig).groups()
    return tuple(re.findall(r"\[*(?:[ZBCSIJFDV]|L[^;]+;)", params)), ret


def parse_dexdump(lines, wanted):
    """Read `dexdump -d` output; return {qualified name: Interface} for the wanted interfaces."""
    names = {}
    for fq in wanted:
        base = "L" + fq.replace(".", "/")
        names.update({base + ";": (fq, "iface"), base + "$Stub;": (fq, "stub"),
                      base + "$Stub$Proxy;": (fq, "proxy")})
    seen = {}  # (fq, kind) -> {"static": {name: value}, "virtual": {name: type}, "reply": set()}
    cur = section = item = None
    for line in lines:
        m = re.match(r"\s*Class descriptor\s*: '(.*)'", line)
        if m:
            cur = names.get(m.group(1))
            if cur:
                seen[cur] = {"static": {}, "virtual": {}, "reply": set()}
            section = item = None
            continue
        if not cur:
            continue
        s = line.strip()
        if s.startswith(("Static fields", "Instance fields", "Direct methods", "Virtual methods")):
            section = s.split()[0].lower()
        elif m := re.match(r"name\s*: '(.*)'", s):
            item = m.group(1)
        elif (m := re.match(r"type\s*: '(.*)'", s)) and section == "virtual":
            seen[cur]["virtual"][item] = m.group(1)
        elif (m := re.match(r"value\s*: (.*)", s)) and section == "static":
            seen[cur]["static"][item] = m.group(1).strip('"')
        elif "Landroid/os/Parcel;.readException:" in s and section == "virtual":
            seen[cur]["reply"].add(item)
    found = {}
    for fq in wanted:
        iface = seen.get((fq, "iface"))
        if iface is None:
            continue
        stub = seen.get((fq, "stub"), {"static": {}})["static"]
        proxy = seen.get((fq, "proxy"))
        methods = {}
        for name, sig in iface["virtual"].items():
            params, ret = _split_descriptor(sig)
            code = stub.get("TRANSACTION_" + name)
            methods[name] = Method(int(code) if code else None, params, ret,
                                   name not in proxy["reply"] if proxy else None)
        found[fq] = Interface(iface["static"].get("DESCRIPTOR"), methods)
    return found


def compare(fq, repo, device):
    """Return (table rows, gaps) for one interface; device is an Interface or None."""
    if device is None:
        return [], [f"{fq}: interface missing on device"]
    gaps = [] if device.descriptor == fq else [f"{fq}: descriptor {device.descriptor} on device"]
    rows = []
    for name in sorted(repo.keys() | device.methods.keys()):
        r, d = repo.get(name), device.methods.get(name)
        before = len(gaps)
        if d is None:
            gaps.append(f"{fq}.{name}: missing on device")
        elif r is None:
            gaps.append(f"{fq}.{name}: missing in repo")
        else:
            if r.code != d.code:
                gaps.append(f"{fq}.{name}: transaction {r.code} in repo, {d.code} on device")
            if r.params != d.params:
                gaps.append(f"{fq}.{name}: parameters ({''.join(r.params)}) in repo, ({''.join(d.params)}) on device")
            if r.ret != d.ret:
                gaps.append(f"{fq}.{name}: returns {r.ret} in repo, {d.ret} on device")
            if d.oneway is None:
                gaps.append(f"{fq}.{name}: oneway unknown on device (no Stub$Proxy)")
            elif r.oneway != d.oneway:
                word = lambda o: "oneway" if o else "not oneway"
                gaps.append(f"{fq}.{name}: {word(r.oneway)} in repo, {word(d.oneway)} on device")
        rows.append((fq.rsplit(".", 1)[1], name, d.code if d else "-", r.code if r else "-",
                     "ok" if len(gaps) == before else "GAP"))
    return rows, gaps


def _dexdump():
    if os.environ.get("DEXDUMP"):
        return os.environ["DEXDUMP"]
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    found = sorted(glob.glob(f"{sdk}/build-tools/*/dexdump"),
                   key=lambda p: [int(x) for x in re.findall(r"\d+", p.split("/")[-2])])
    if not found:
        sys.exit("dexdump not found: set DEXDUMP or ANDROID_HOME (SDK build-tools)")
    return found[-1]


def _dex_lines(apk, tmp):
    dexdump = _dexdump()
    with zipfile.ZipFile(apk) as z:
        dexes = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        z.extractall(tmp, dexes)
    for dex in dexes:
        proc = subprocess.Popen([dexdump, "-d", os.path.join(tmp, dex)], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, errors="replace")
        yield from proc.stdout
        if proc.wait() != 0:
            sys.exit(f"dexdump failed on {dex}")


def main(apk, aidl_dir):
    repo = {}
    for path in sorted(glob.glob(f"{aidl_dir}/**/*.aidl", recursive=True)):
        parsed = parse_aidl(open(path).read())
        if parsed:
            repo[parsed[0]] = parsed[1]
    with tempfile.TemporaryDirectory() as tmp:
        device = parse_dexdump(_dex_lines(apk, tmp), set(repo))
    print(f"# {os.path.basename(apk)} vs {aidl_dir}\n")
    print("| Interface | Method | Device | Repo | |\n|---|---|---|---|---|")
    all_gaps = []
    for fq in sorted(repo):
        rows, gaps = compare(fq, repo[fq], device.get(fq))
        all_gaps += gaps
        for row in rows:
            print("| " + " | ".join(str(c) for c in row) + " |")
    count = sum(len(m) for m in repo.values())
    print(f"\n{len(repo)} interfaces, {count} methods in repo, {len(all_gaps)} gap(s)")
    for gap in all_gaps:
        print("GAP " + gap)
    return 1 if all_gaps else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
