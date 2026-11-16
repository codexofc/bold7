#!/usr/bin/env bash
# A release of Bold7, made ready and published by nobody: the files land in build/release/<version>/
# and the gh command that would publish them is printed.
#
#   tools/release.sh <version> [notes.md]
#
#   <version>  major.minor or major.minor.patch; versionCode = major*10000 + minor*100 + patch,
#              never lower than module.prop's
#   notes.md   what changed, in Markdown; by default the subjects of the commits since the last v* tag.
#              Never empty: KernelSU's manager blocks an update whose changelog is empty.
#
# Steps, each stopping the script on failure:
#   1. module.prop gets version, versionCode and updateJson (the latest release's update.json): the
#      manager offers the update by itself, and the change is to commit with the release; any later
#      failure puts module.prop back as it was;
#   2. the public module zip, ./gradlew -Pbold7.public=true :system:module:moduleZip. While the
#      module cannot extract the BlackBerry resources at install yet, the build refuses and lists what
#      is missing: this script says so and stops, without any zip;
#   3. the zip checked: its module.prop is the one of step 1, and no entry is a BlackBerry file (sha256
#      in assets-private's lists or among assets-gen's files) or sits where the development zip puts
#      them;
#   4. bold7.zip and update.json signed in Ed25519 by keys/bold7-release.pem (its passphrase:
#      bold7.release.password in local.properties), each signature checked with the public key
#      system/module/release/bold7-release.pub;
#   5. update.json (version, versionCode, zipUrl, changelog) and CHANGELOG.md written.
#
# BOLD7_RELEASE_ZIP=<zip> skips step 2 and checks that zip instead; BOLD7_ROOT replaces the
# repository's root; OPENSSL names an OpenSSL 3 or later (pkeyutl -rawin).
set -euo pipefail

usage() { echo "usage: tools/release.sh <major.minor[.patch]> [notes.md]" >&2; exit 2; }
[ $# -ge 1 ] && [ $# -le 2 ] || usage
V=$1
[[ $V =~ ^([0-9]{1,3})\.([0-9]{1,2})(\.([0-9]{1,2}))?$ ]] || usage
CODE=$(( 10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[4]:-0} ))

ROOT=${BOLD7_ROOT:-$(git rev-parse --show-toplevel)}
REPO=codexofc/bold7
DOWNLOAD=https://github.com/$REPO/releases/download/v$V
UPDATE_JSON=https://github.com/$REPO/releases/latest/download/update.json
PROP=$ROOT/system/module/src/module.prop
KEY=$ROOT/keys/bold7-release.pem
PUB=$ROOT/system/module/release/bold7-release.pub
OUT=$ROOT/build/release/$V
OPENSSL=${OPENSSL:-openssl}

fail() { echo "$*" >&2; exit 1; }
[ ! -e "$OUT" ] || fail "$OUT exists: remove it to make $V again"
"$OPENSSL" version | grep -qE '^OpenSSL ([3-9]|[1-9][0-9])\.' || fail "$OPENSSL is not OpenSSL 3 or later (pkeyutl -rawin for Ed25519): set OPENSSL"
[ -f "$KEY" ] || fail "missing $KEY"
[ -f "$PUB" ] || fail "missing $PUB"
BOLD7_RELEASE_PASSWORD=$(sed -n 's/^bold7\.release\.password=//p' "$ROOT/local.properties" 2>/dev/null | tail -1)
[ -n "$BOLD7_RELEASE_PASSWORD" ] || fail "no bold7.release.password in $ROOT/local.properties"
export BOLD7_RELEASE_PASSWORD

# The notes, never empty.
if [ $# -eq 2 ]; then
  NOTES=$(cat "$2")
else
  last=$(git -C "$ROOT" describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)
  NOTES=$(git -C "$ROOT" log --no-merges --format='- %s' ${last:+"$last..HEAD"})
fi
[ -n "$(printf '%s' "$NOTES" | tr -d '[:space:]')" ] || fail "empty release notes: the manager would block the update"

# 1. module.prop
old=$(sed -n 's/^versionCode=//p' "$PROP")
[ "$CODE" -ge "${old:-0}" ] || fail "versionCode $CODE is lower than module.prop's $old"
bak=$(mktemp); cp "$PROP" "$bak"; ok=0
trap '[ $ok = 1 ] || cp "$bak" "$PROP"; rm -f "$bak"' EXIT
sed -i '' -e "s/^version=.*/version=$V/" -e "s/^versionCode=.*/versionCode=$CODE/" -e '/^updateJson=/d' "$PROP"
echo "updateJson=$UPDATE_JSON" >> "$PROP"

# 2. the public zip
if [ -n "${BOLD7_RELEASE_ZIP:-}" ]; then
  ZIP=$BOLD7_RELEASE_ZIP
else
  (cd "$ROOT" && ./gradlew -Pbold7.public=true :system:module:moduleZip) ||
    fail "The public mode cannot produce a complete module yet (the list is above): no zip made, module.prop left as it was."
  ZIP=$ROOT/system/module/build/dist/bold7-module.zip
fi
[ -f "$ZIP" ] || fail "no zip at $ZIP"

# 3. the zip's module.prop, and nothing of BlackBerry
/usr/bin/python3 -I - "$ZIP" "$ROOT" "$V" "$CODE" "$UPDATE_JSON" <<'EOF'
import hashlib, pathlib, sys, zipfile
zp, root, version, code, update = sys.argv[1], pathlib.Path(sys.argv[2]), *sys.argv[3:]
problems = []
with zipfile.ZipFile(zp) as z:
    names = z.namelist()
    prop = dict(l.split("=", 1) for l in z.read("module.prop").decode().splitlines() if "=" in l) if "module.prop" in names else {}
    for k, v in (("version", version), ("versionCode", code), ("updateJson", update)):
        if prop.get(k) != v:
            problems.append(f"module.prop: {k} is {prop.get(k)!r}, not {v!r}")
    known = {}
    lists = [*root.glob("assets-private/*.sha256"), *root.glob("assets-private/*/*.sha256")]
    for s in lists:
        for line in s.read_text(errors="replace").splitlines():
            if len(line) > 66:
                known[line[:64]] = f"{s.name}: {line[66:]}"
    gen = root / "assets-gen"
    for f in (gen.rglob("*") if gen.is_dir() else []):
        if f.is_file():
            known.setdefault(hashlib.sha256(f.read_bytes()).hexdigest(), f"assets-gen/{f.relative_to(gen)}")
    places = ("layer/product/media/bold7/", "layer/product/media/audio/", "layer/product/media/bootanimation.zip", "layer/system/fonts/BBAlpha")
    for n in names:
        if n.endswith("/"):
            continue
        hit = known.get(hashlib.sha256(z.read(n)).hexdigest())
        if hit:
            problems.append(f"{n}: BlackBerry file ({hit})")
        elif n.startswith(places):
            problems.append(f"{n}: where the development zip puts BlackBerry files")
if not lists:
    print("warning: no sha256 list in assets-private: checked against assets-gen and the paths only", file=sys.stderr)
for p in problems:
    print(p, file=sys.stderr)
sys.exit(1 if problems else 0)
EOF

# 4. and 5. files, signatures
mkdir -p "$OUT"
cp "$ZIP" "$OUT/bold7.zip"
printf '# Bold7 %s\n\n%s\n' "$V" "$NOTES" > "$OUT/CHANGELOG.md"
cat > "$OUT/update.json" <<EOF
{
  "version": "$V",
  "versionCode": $CODE,
  "zipUrl": "$DOWNLOAD/bold7.zip",
  "changelog": "$DOWNLOAD/CHANGELOG.md"
}
EOF
for f in bold7.zip update.json; do
  "$OPENSSL" pkeyutl -sign -rawin -inkey "$KEY" -passin env:BOLD7_RELEASE_PASSWORD -in "$OUT/$f" -out "$OUT/$f.sig" ||
    { rm -rf "$OUT"; fail "signing $f failed (key or passphrase)"; }
  "$OPENSSL" pkeyutl -verify -pubin -rawin -inkey "$PUB" -in "$OUT/$f" -sigfile "$OUT/$f.sig" >/dev/null ||
    { rm -rf "$OUT"; fail "the signature of $f does not verify with $PUB: is it the release key's public half?"; }
done

ok=1
echo "Bold7 $V (versionCode $CODE) ready in $OUT, signatures checked. module.prop now says $V: commit it."
echo "To publish, once the tag v$V is pushed:"
echo "  gh release create v$V --repo $REPO --title 'Bold7 $V' --notes-file '$OUT/CHANGELOG.md' \\"
echo "    '$OUT/bold7.zip' '$OUT/bold7.zip.sig' '$OUT/update.json' '$OUT/update.json.sig' '$OUT/CHANGELOG.md'"
