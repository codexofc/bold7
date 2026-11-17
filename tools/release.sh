#!/usr/bin/env bash
# A release of Bold7, made ready and published by nobody: the files land in
# build/release/<version>-<line>/ and the commands that would publish them are printed.
#
# Bold7 has one line per system, named by module.prop's updateJson, which never changes on a line:
# https://raw.githubusercontent.com/codexofc/bold7/main/update/<line>.json (benos, lineage24). The
# KernelSU manager of each line reads its own file there, so it never offers the other line's zip.
# The release is v<version>-<line>, its zip bold7-<version>-<line>.zip.
#
#   tools/release.sh <version> [notes.md]
#
#   <version>  major.minor or major.minor.patch; versionCode = major*10000 + minor*100 + patch,
#              never lower than module.prop's
#   notes.md   what changed, in Markdown; by default the subjects of the commits since the last v* tag.
#              Never empty: KernelSU's manager blocks an update whose changelog is empty.
#
# Steps, each stopping the script on failure:
#   1. module.prop gets version (<version>-<line>) and versionCode: the manager offers the update by
#      itself, and the change is to commit with the release; any later failure puts module.prop back
#      as it was;
#   2. the public module zip, ./gradlew -Pbold7.public=true -Pbold7.release=true :system:module:moduleZip. While the
#      module cannot extract the BlackBerry resources at install yet, the build refuses and lists what
#      is missing: this script says so and stops, without any zip;
#   3. the zip checked: its module.prop is the one of step 1, and no entry is a BlackBerry file (sha256
#      in assets-private's lists or among assets-gen's files) or sits where the development zip puts
#      them;
#   4. the zip and update.json signed in Ed25519 by keys/bold7-release.pem (its passphrase:
#      bold7.release.password in local.properties), each signature checked with the public key
#      system/module/release/bold7-release.pub;
#   5. update.json (version, versionCode, zipUrl, changelog) and CHANGELOG.md written, the archive of
#      the Camera's sources copied beside the zip (GPL-3.0: the module carries the APK of Bold7's fork
#      of BenOS Camera, NOTICE; vendor/bold7-camera/*-src.tar.gz, made by git archive of the fork's
#      commit that NOTICE names), and update.json
#      copied to public/update/<line>.json, to commit with module.prop: the public repository serves it
#      once exported, after the release is published (never before: the manager would offer a zip that
#      does not exist yet).
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
PROP=$ROOT/system/module/src/module.prop
KEY=$ROOT/keys/bold7-release.pem
PUB=$ROOT/system/module/release/bold7-release.pub
OPENSSL=${OPENSSL:-openssl}

fail() { echo "$*" >&2; exit 1; }
UPDATE_JSON=$(sed -n 's/^updateJson=//p' "$PROP")
[[ $UPDATE_JSON =~ ^https://raw\.githubusercontent\.com/$REPO/main/update/([a-z0-9]+)\.json$ ]] ||
  fail "module.prop's updateJson is '$UPDATE_JSON', not https://raw.githubusercontent.com/$REPO/main/update/<line>.json"
LINE=${BASH_REMATCH[1]}
TAG=v$V-$LINE
NAME=bold7-$V-$LINE.zip
DOWNLOAD=https://github.com/$REPO/releases/download/$TAG
OUT=$ROOT/build/release/$V-$LINE
[ ! -e "$OUT" ] || fail "$OUT exists: remove it to make $V again"
"$OPENSSL" version | grep -qE '^OpenSSL ([3-9]|[1-9][0-9])\.' || fail "$OPENSSL is not OpenSSL 3 or later (pkeyutl -rawin for Ed25519): set OPENSSL"
[ -f "$KEY" ] || fail "missing $KEY"
[ -f "$PUB" ] || fail "missing $PUB"
CAMERA_SRC=$(ls "$ROOT"/vendor/bold7-camera/*-src.tar.gz 2>/dev/null || true)
[ -n "$CAMERA_SRC" ] && [ "$(printf '%s\n' "$CAMERA_SRC" | wc -l)" -eq 1 ] ||
  fail "not one archive of the Camera's sources in $ROOT/vendor/bold7-camera (*-src.tar.gz): the release must carry it (GPL-3.0, NOTICE)"
CAMERA_SRC_NAME=$(basename "$CAMERA_SRC")
BOLD7_RELEASE_PASSWORD=$(sed -n 's/^bold7\.release\.password=//p' "$ROOT/local.properties" 2>/dev/null | tail -1)
[ -n "$BOLD7_RELEASE_PASSWORD" ] || fail "no bold7.release.password in $ROOT/local.properties"
export BOLD7_RELEASE_PASSWORD
# Browser sign-in for Outlook needs Bold7's Microsoft client in local.properties, read by apps/hub's
# build: a release without it would turn it off without a word. BOLD7_NO_OAUTH=1 releases without.
# Google's client never goes into a release (-Pbold7.release=true, apps/hub/build.gradle.kts):
# Gmail goes by app password.
if [ "${BOLD7_NO_OAUTH:-}" != 1 ]; then
  grep -q '^bold7\.oauth\.microsoft\.id=.' "$ROOT/local.properties" ||
    fail "no bold7.oauth.microsoft.id in $ROOT/local.properties: browser sign-in would be off (BOLD7_NO_OAUTH=1 to release without it)"
fi

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
sed -i '' -e "s/^version=.*/version=$V-$LINE/" -e "s/^versionCode=.*/versionCode=$CODE/" "$PROP"

# 2. the public zip
if [ -n "${BOLD7_RELEASE_ZIP:-}" ]; then
  ZIP=$BOLD7_RELEASE_ZIP
else
  (cd "$ROOT" && ./gradlew -Pbold7.public=true -Pbold7.release=true :system:module:moduleZip) ||
    fail "The public mode cannot produce a complete module yet (the list is above): no zip made, module.prop left as it was."
  ZIP=$ROOT/system/module/build/dist/bold7-module.zip
fi
[ -f "$ZIP" ] || fail "no zip at $ZIP"

# 3. the zip's module.prop, and nothing of BlackBerry
/usr/bin/python3 -I - "$ZIP" "$ROOT" "$V-$LINE" "$CODE" "$UPDATE_JSON" <<'EOF'
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
cp "$ZIP" "$OUT/$NAME"
cp "$CAMERA_SRC" "$OUT/$CAMERA_SRC_NAME"
printf '# Bold7 %s\n\n%s\n' "$V-$LINE" "$NOTES" > "$OUT/CHANGELOG.md"
cat > "$OUT/update.json" <<EOF
{
  "version": "$V-$LINE",
  "versionCode": $CODE,
  "zipUrl": "$DOWNLOAD/$NAME",
  "changelog": "$DOWNLOAD/CHANGELOG.md"
}
EOF
for f in "$NAME" update.json; do
  "$OPENSSL" pkeyutl -sign -rawin -inkey "$KEY" -passin env:BOLD7_RELEASE_PASSWORD -in "$OUT/$f" -out "$OUT/$f.sig" ||
    { rm -rf "$OUT"; fail "signing $f failed (key or passphrase)"; }
  "$OPENSSL" pkeyutl -verify -pubin -rawin -inkey "$PUB" -in "$OUT/$f" -sigfile "$OUT/$f.sig" >/dev/null ||
    { rm -rf "$OUT"; fail "the signature of $f does not verify with $PUB: is it the release key's public half?"; }
done

mkdir -p "$ROOT/public/update"
cp "$OUT/update.json" "$ROOT/public/update/$LINE.json"

ok=1
echo "Bold7 $V-$LINE (versionCode $CODE) ready in $OUT, signatures checked."
echo "module.prop now says $V-$LINE and public/update/$LINE.json names the new zip: commit both."
echo "To publish (a draft first, seen only by the owners; --prerelease for a line not yet tested):"
echo "  gh release create $TAG --repo $REPO --draft --target main --title 'Bold7 $V ($LINE)' --notes-file '$OUT/CHANGELOG.md' \\"
echo "    '$OUT/$NAME' '$OUT/$NAME.sig' '$OUT/update.json' '$OUT/update.json.sig' '$OUT/CHANGELOG.md' '$OUT/$CAMERA_SRC_NAME'"
echo "then gh release edit $TAG --repo $REPO --draft=false, and only then the export carrying public/update/$LINE.json."
