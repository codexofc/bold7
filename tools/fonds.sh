#!/usr/bin/env bash
# The 9900's wallpapers (BlackBerry OS 7.1) on the phone, where Android's pickers find them.
#
#   tools/fonds.sh          read-only: the current wallpaper (dumpsys wallpaper) and what
#                           /sdcard/Pictures/Bold7 holds
#   tools/fonds.sh poser    copies assets-gen/wallpapers/*.png (tools/import-assets.sh fonds, 15
#                           images of 720x720) to /sdcard/Pictures/Bold7 and scans the volume.
#                           Changes NO wallpaper setting: the user picks one, so there is
#                           nothing to save
#   tools/fonds.sh retour   deletes /sdcard/Pictures/Bold7 and rescans, so MediaStore forgets it.
#                           The wallpaper in use is a copy Android made, it stays until the user
#                           picks another (Android's own default to get it back)
#
# poser and retour CHANGE THE PHONE'S STATE (files on /sdcard only): ask the phone's owner first.
set -euo pipefail
cd "$(dirname "$0")/.."
D=/sdcard/Pictures/Bold7
sh() { adb shell -n "$@" | tr -d '\r'; }
scan() { sh "content call --uri content://media --method scan_volume --arg external_primary" >/dev/null; }

case ${1:-} in
  "")
    sh "dumpsys wallpaper" | grep -E "^System wallpaper|^Lock wallpaper|mWidth|mHeight|mWallpaperComponent" | head -8
    sh "ls $D 2>&1" || true ;;
  poser)
    [ "$(ls assets-gen/wallpapers/*.png 2>/dev/null | wc -l | tr -d ' ')" = 15 ] || { echo "Run tools/import-assets.sh fonds first." >&2; exit 1; }
    sh "mkdir -p $D"
    adb push assets-gen/wallpapers/. "$D/" >/dev/null
    scan
    echo "$(sh "ls $D" | wc -l | tr -d ' ') wallpapers in $D" ;;
  retour)
    sh "rm -rf $D"
    scan
    echo "$D removed" ;;
  *) echo "Usage: tools/fonds.sh [poser|retour]" >&2; exit 2 ;;
esac
