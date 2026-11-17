#!/usr/bin/env bash
# Runs the install's copy of the user's icons pack (icons.sh) as KernelSU does, in root with its
# busybox sh (owner and SELinux label are set as at install), in a scratch folder of /data/local/tmp: a made-up Download/ (the pack unpacked at the top, one
# level down, or in its zip; invented PNG-signed files, no real icon) and a made-up Bold7/icons/.
# No module, nothing else touched. Exits non-zero on failure.
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-icons-test
W=$(mktemp -d)
trap 'rm -rf "$W"; adb shell "su -c \"rm -rf $T\""' EXIT
png() { printf '\211PNG\r\n\032\n%s' "$2" > "$1"; } # a PNG signature and some bytes: all icons.sh reads
mkdir -p "$W/top/Bold7-icons" "$W/sub/Bold7-fichier-9900/Bold7-icons" "$W/zip"
png "$W/top/Bold7-icons/com.whatsapp.png" old-whatsapp
png "$W/top/Bold7-icons/com.kept.app.png" from-the-pack
png "$W/top/Bold7-icons/whatsapp.png" not-a-package
printf 'GIF89a not a png at all' > "$W/top/Bold7-icons/com.fake.app.png"
head -c 1100000 /dev/zero | { printf '\211PNG\r\n\032\n'; cat; } > "$W/top/Bold7-icons/com.huge.app.png"
echo "Icons from old versions, for my own use." > "$W/top/Bold7-icons/SOURCES.txt"
png "$W/sub/Bold7-fichier-9900/Bold7-icons/com.instagram.android.png" old-instagram
mkdir -p "$W/zip/Bold7-icons" "$W/zip/Bold7-fichier-9900"
png "$W/zip/Bold7-icons/org.example.zipped.png" zipped
png "$W/zip/Bold7-icons/com.whatsapp.png" old-whatsapp
echo exe > "$W/zip/Bold7-fichier-9900/loader.exe"
(cd "$W/zip" && zip -q -r ../Bold7-fichier-9900.zip Bold7-icons Bold7-fichier-9900)
adb shell "su -c 'rm -rf $T' && mkdir -p $T/mod" && adb push src/icons.sh $T/mod/ >/dev/null
adb push "$W/top" "$W/sub" $T/ >/dev/null
adb push "$W/Bold7-fichier-9900.zip" $T/ >/dev/null

bad=0
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2', expected '$3'"; bad=1; fi; }
# A fresh Download/ from the named parts, an empty storage with the user's own com.kept.app.png when
# asked; prints icons.sh's line, then the icons' names and contents.
run() {
  adb shell "cd $T && su -c 'rm -rf dl media' && mkdir -p dl media && for p in $1; do cp -R \$p/. dl/ 2>/dev/null || cp \$p dl/; done
    [ '${2:-}' = kept ] && mkdir -p media/Bold7/icons && printf '\\211PNG\\r\\n\\032\\nmine' > media/Bold7/icons/com.kept.app.png
    su -c 'DOWNLOAD=$T/dl ICONS=$T/media/Bold7/icons /data/adb/ksu/bin/busybox sh mod/icons.sh mod'; echo \"rc=\$?\"
    for f in media/Bold7/icons/*; do [ -f \$f ] && echo \"\${f##*/}=\$(tail -c +9 \$f | head -c 20)\"; done; [ -d mod/icons-pack ] && echo leftover; true" | tr -d '\r'
}

out=$(run "")
expect "no pack: nothing said, nothing made" "$out" "rc=0"

out=$(run top)
expect "the folder at the top: the line" "$(echo "$out" | head -1)" "      Icons: 2 copied to $T/media/Bold7/icons, 3 not a PNG icon skipped"
expect "the folder at the top: the icons" "$(echo "$out" | tail -n +3 | tr '\n' ' ')" "com.kept.app.png=from-the-pack com.whatsapp.png=old-whatsapp "

expect "owner, mode and label of Download/" "$(adb shell "cd $T && stat -c '%u:%g %a' dl media/Bold7/icons/com.whatsapp.png media/Bold7/icons | tr '\n' ' '; ls -Zd dl media/Bold7/icons/com.whatsapp.png | awk '{print \$1}' | sort -u" | tr -d '\r')" "2000:2000 777 2000:2000 660 2000:2000 2770 u:object_r:shell_data_file:s0"

out=$(run top kept)
expect "an icon already there is kept" "$(echo "$out" | grep kept.app)" "com.kept.app.png=mine"
expect "and said" "$(echo "$out" | head -1)" "      Icons: 1 copied to $T/media/Bold7/icons, 1 already there kept, 3 not a PNG icon skipped"

out=$(run sub)
expect "one level down (the zip unpacked in its own folder)" "$(echo "$out" | tail -n +3 | tr '\n' ' ')" "com.instagram.android.png=old-instagram "

out=$(run Bold7-fichier-9900.zip)
expect "the zip itself" "$(echo "$out" | head -1)" "      Icons: 2 copied to $T/media/Bold7/icons"
expect "the zip's icons, not its exe" "$(echo "$out" | tail -n +3 | tr '\n' ' ')" "com.whatsapp.png=old-whatsapp org.example.zipped.png=zipped "

out=$(run "top Bold7-fichier-9900.zip")
expect "the folder and its zip: each icon once" "$(echo "$out" | head -1)" "      Icons: 3 copied to $T/media/Bold7/icons, 3 not a PNG icon skipped"
expect "no scratch folder left" "$(echo "$out" | grep -c leftover || true)" "0"

exit $bad
