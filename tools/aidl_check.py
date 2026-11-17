#!/usr/bin/env python3
"""Check that the quickstep AIDL of the repository match the interfaces compiled in APKs.

    tools/aidl_check.py [--absent <interface.method>]... <apk>... <aidl_dir>

For every interface with a body under <aidl_dir>, reads from each APK's dex (via the SDK's
`dexdump -d`) the DESCRIPTOR, the TRANSACTION_* codes of the Stub, the method signatures
and whether the Proxy waits for a reply (oneway or not). Where R8 inlined the DESCRIPTOR and
the codes, it reads them from the code: in a Proxy, the string given to writeInterfaceToken
and the constant given to IBinder.transact; in a Stub alone, the switch key (or the constant
compared) that leads onTransact to each call, oneway if that case writes no reply. Of the
APKs, it keeps for each interface the one that knows most of it, then compares it to the AIDL:
name, transaction code, parameter and return types, oneway. Prints a Markdown table and
the gaps; exits 1 if there is any gap. --absent names a method R8 removed from every APK's
copy of the interface (nobody there calls it) and that was checked by hand: its "missing on
device" is a note, not a gap. Python 3 stdlib only.
"""
import glob
import itertools
import os
import re
import struct
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
    # (fq, kind) -> {"static": {name: value}, "virtual": {name: type}, "reply": set(),
    #                "codes": {method: code}, "descriptor": str}; regs: the method's constants so far
    seen = {}
    cur = section = item = None
    regs = {}
    for line in lines:
        m = re.match(r"\s*Class descriptor\s*: '(.*)'", line)
        if m:
            cur = names.get(m.group(1))
            if cur:
                seen[cur] = {"static": {}, "virtual": {}, "reply": set(), "codes": {}, "descriptor": None,
                             "switches": {}, "cases": [], "calls": [], "replies": []}
            section = item = None
            continue
        if not cur:
            continue
        s = line.strip()
        if s.startswith(("Static fields", "Instance fields", "Direct methods", "Virtual methods")):
            section = s.split()[0].lower()
        elif m := re.match(r"name\s*: '(.*)'", s):
            item = m.group(1)
            regs = {}
        elif (m := re.match(r"type\s*: '(.*)'", s)) and section == "virtual":
            seen[cur]["virtual"][item] = m.group(1)
        elif (m := re.match(r"value\s*: (.*)", s)) and section == "static":
            seen[cur]["static"][item] = m.group(1).strip('"')
        elif "Landroid/os/Parcel;.readException:" in s and section == "virtual":
            seen[cur]["reply"].add(item)
        elif section == "virtual" and (m := re.search(r"\|([0-9a-f]{4}): (.*)", s)):
            at, op = int(m.group(1), 16), m.group(2)
            c = seen[cur]
            if m := re.match(r"(?:packed|sparse)-switch v\d+, ([0-9a-f]+)", op):
                c["switches"][int(m.group(1), 16)] = at
            elif op.startswith("switch-data ") and at in c["switches"]:
                for kt in op.split()[1:]:
                    k, t = map(int, kt.split(":"))
                    c["cases"].append((c["switches"][at] + t, k))
            elif (m := re.match(r"if-eq (v\d+), (v\d+), ([0-9a-f]+)", op)) \
                    and any(isinstance(regs.get(r), int) for r in m.group(1, 2)):
                k = next(regs[r] for r in m.group(1, 2) if isinstance(regs.get(r), int))
                c["cases"].append((int(m.group(3), 16), k))  # a Stub of few methods compares instead
            elif (m := re.match(r"invoke-interface \{[^}]*\}, L([\w/$]+);\.(\w+):", op)) \
                    and m.group(1).replace("/", ".") == cur[0]:
                c["calls"].append((at, m.group(2)))
            elif "Landroid/os/Parcel;.writeNoException:" in op:
                c["replies"].append(at)
            if m := re.match(r'const(?:/4|/16)? (v\d+), #int (-?\d+)', op):
                regs[m.group(1)] = int(m.group(2))
            elif m := re.match(r'const-string (v\d+), "(.*)"', op):
                regs[m.group(1)] = m.group(2)
            elif m := re.match(r"invoke-\w+ \{([^}]*)\}, Landroid/os/(\w+);\.(\w+):", op):
                args = [a.strip() for a in m.group(1).split(",")]
                if (m.group(2), m.group(3)) in (("Parcel", "writeInterfaceToken"), ("Parcel", "enforceInterface")):
                    seen[cur]["descriptor"] = regs.get(args[1])
                elif (m.group(2), m.group(3)) == ("IBinder", "transact"):
                    seen[cur]["codes"][item] = regs.get(args[1])
    found = {}
    for fq in wanted:
        iface = seen.get((fq, "iface"))
        if iface is None:
            continue
        stub_class = seen.get((fq, "stub"), {"static": {}})
        stub = stub_class["static"]
        switched = _switch_codes(stub_class) if "cases" in stub_class else {}
        proxy = seen.get((fq, "proxy"))
        methods = {}
        for name, sig in iface["virtual"].items():
            params, ret = _split_descriptor(sig)
            code = stub.get("TRANSACTION_" + name)
            code = int(code) if code else proxy["codes"].get(name) if proxy else None
            oneway = name not in proxy["reply"] if proxy else None
            if name in switched:
                code = code if code is not None else switched[name][0]
                oneway = oneway if oneway is not None else switched[name][1]
            methods[name] = Method(code, params, ret, oneway)
        descriptor = (iface["static"].get("DESCRIPTOR") or (proxy["descriptor"] if proxy else None)
                      or stub_class.get("descriptor"))
        found[fq] = Interface(descriptor, methods)
    return found


def _switch_codes(stub):
    """{method: (code, oneway)} from a Stub's onTransact: the case whose block holds the call."""
    cases = sorted(stub["cases"])
    found = {}
    for at, name in stub["calls"]:
        before = [c for c in cases if c[0] <= at]
        # The case's block holds one call only: one with another call in between is no case's.
        if not before or any(before[-1][0] <= a < at for a, _ in stub["calls"]):
            continue
        # The reply, if any, follows the call in its block: before the next case or the next call.
        end = min([t for t, _ in cases if t > at] + [a for a, _ in stub["calls"] if a > at], default=float("inf"))
        found[name] = (before[-1][1], not any(at < r < end for r in stub["replies"]))
    return found


def decode_switch_data(line, dex):
    """dexdump prints only the head of a switch payload: decoded from the dex at the line's file
    offset into 'switch-data key:target...', targets relative to the switch. Other lines as they are."""
    m = re.match(r"([0-9a-f]+): .*\|([0-9a-f]{4}): (?:packed|sparse)-switch-data", line)
    if not m:
        return line
    off = int(m.group(1), 16)
    ident, size = struct.unpack_from("<HH", dex, off)
    if ident == 0x100:
        first = struct.unpack_from("<i", dex, off + 4)[0]
        keys, targets = range(first, first + size), struct.unpack_from(f"<{size}i", dex, off + 8)
    else:
        keys = struct.unpack_from(f"<{size}i", dex, off + 4)
        targets = struct.unpack_from(f"<{size}i", dex, off + 4 + 4 * size)
    return f"{m.group(1)}: |{m.group(2)}: switch-data " + " ".join(f"{k}:{t}" for k, t in zip(keys, targets)) + "\n"


def best(candidates):
    """The Interface that knows most of itself (descriptor, codes, oneway), None if there is none."""
    def known(i):
        return (i.descriptor is not None, sum(m.code is not None and m.oneway is not None for m in i.methods.values()))
    return max(candidates, key=known, default=None)


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
        path = os.path.join(tmp, dex)
        with open(path, "rb") as f:
            data = f.read()
        proc = subprocess.Popen([dexdump, "-d", path], stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, errors="replace")
        for line in proc.stdout:
            yield decode_switch_data(line, data) if "switch-data" in line else line
        if proc.wait() != 0:
            sys.exit(f"dexdump failed on {dex}")


def main(apks, aidl_dir, absent=()):
    repo = {}
    for path in sorted(glob.glob(f"{aidl_dir}/**/*.aidl", recursive=True)):
        parsed = parse_aidl(open(path).read())
        if parsed:
            repo[parsed[0]] = parsed[1]
    founds = []
    for apk in apks:
        with tempfile.TemporaryDirectory() as tmp:
            founds.append(parse_dexdump(_dex_lines(apk, tmp), set(repo)))
    device = {fq: best([f[fq] for f in founds if fq in f]) for fq in repo}
    print(f"# {', '.join(os.path.basename(a) for a in apks)} vs {aidl_dir}\n")
    print("| Interface | Method | Device | Repo | |\n|---|---|---|---|---|")
    all_gaps, noted = [], []
    excused = [f"{a}: missing on device" for a in absent]
    for fq in sorted(repo):
        rows, gaps = compare(fq, repo[fq], device.get(fq))
        all_gaps += [g for g in gaps if g not in excused]
        noted += [g for g in gaps if g in excused]
        for row in rows:
            print("| " + " | ".join(str(c) for c in row) + " |")
    count = sum(len(m) for m in repo.values())
    print(f"\n{len(repo)} interfaces, {count} methods in repo, {len(all_gaps)} gap(s)")
    for gap in all_gaps:
        print("GAP " + gap)
    for note in noted:
        print(f"NOTE {note}, checked by hand (--absent)")
    return 1 if all_gaps else 0


if __name__ == "__main__":
    args, absent = sys.argv[1:], []
    while args[:1] == ["--absent"] and len(args) > 1:
        absent.append(args[1])
        args = args[2:]
    if len(args) < 2:
        sys.exit(__doc__)
    sys.exit(main(args[:-1], args[-1], absent))
