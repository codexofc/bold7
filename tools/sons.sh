#!/usr/bin/env bash
# Android's default sounds set to the Bold 9900's (BlackBerry OS 7.1), and back exactly.
#
#   tools/sons.sh          read-only: each setting, its value and the file it plays
#   tools/sons.sh poser [--ecraser]  saves the current values in build/state/sons-avant.txt of the main
#                          checkout (once: an existing save is kept, never overwritten), then sets
#                          ringtone Spirit, notification Sanguine Remix, alarm Dream
#                          (docs/BBOS7-VISUEL.md, section 20). A setting that plays a file outside
#                          the system partitions (the user's choice, e.g. a ringtone in
#                          /sdcard/Ringtones) is left alone and said so; `poser --ecraser` sets it too
#   tools/sons.sh retour   checks the save, then puts back each setting that still plays what
#                          poser set or a sound of the module (any other is the user's later choice,
#                          kept), and deletes the save. With or without a save, it fails unless,
#                          at the end, no setting plays a sound of the module or a MediaStore row
#                          that is gone. Once the module is gone, rescans the internal volume so
#                          MediaStore forgets its sounds. tools/uninstall.sh runs it, in both runs.
#
# poser and retour CHANGE THE PHONE'S STATE: ask the phone's owner first.
# The three come with the module (/product/media/audio, the 9900's AAC as is). Writing one of these settings deletes its ringtone cache
# (SettingsProvider.mutateSystemSetting), so the next play reads the new value. Only these three
# settings: a sound of the module chosen for a notification channel, a contact or an alarm is out
# of reach (the sound profiles and the apps' channels).
set -euo pipefail
cd "$(dirname "$0")/.."
# -n: a while-read loop must not lose its input to adb. No `| grep -q` on its output under
# pipefail (SIGPIPE): its output is captured, then tested.
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
# The main checkout's build/, also from a worktree: poser and retour may run from different ones.
# SONS_SAVE: for tools/test_sons.py only.
SAVE=${SONS_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/sons-avant.txt}
A=/product/media/audio
KEYS="ringtone notification_sound alarm_alert"
PROBE=$A/ringtones/Spirit.m4a
# The 9900's default for each setting (Normal profile; the alarm is deduced, docs/BBOS7-VISUEL.md).
target() {
  case $1 in
    ringtone) echo $A/ringtones/Spirit.m4a ;;
    notification_sound) echo $A/notifications/SanguineRemix.m4a ;;
    alarm_alert) echo $A/alarms/Dream.m4a ;;
  esac
}
# The module's sounds: the table its build reads.
OURS=$(grep -v -e '^#' -e '^[[:space:]]*$' system/module/sounds.tsv | cut -f1 | sed "s|^|$A/|")
ours() { [ -n "$1" ] && grep -qxF -- "$1" <<<"$OURS"; }
# The user's choice, not a stock sound: a MediaStore item of any volume but internal (even if its
# row is unreadable), or a file outside the system partitions. $1 = the setting's value, $2 = its file.
personal() {
  local vol
  vol=$(sed -n 's|^content://media/\([^/]*\)/.*|\1|p' <<<"$1")
  { [ -n "$vol" ] && [ "$vol" != internal ]; } ||
    { [ -n "$2" ] && [[ $2 != /product/* && $2 != /system/* && $2 != /system_ext/* && $2 != /vendor/* ]]; }
}
# A value these settings may hold and we may write back: unset, or a MediaStore item, no quote.
valid() { [ "$1" = null ] || { [[ $1 == content://media/* ]] && [[ $1 != *"'"* ]]; }; }

rows() { sh "content query --uri content://media/$1/audio/media --projection $2 --where \"$3\""; }
# The file a setting value plays: the _data of its MediaStore row (any volume), empty if none.
file_of() {
  local vol id
  vol=$(sed -n 's|^content://media/\([^/]*\)/audio/media/[0-9][0-9]*.*|\1|p' <<<"$1")
  id=$(sed -n 's|^content://media/[^/]*/audio/media/\([0-9][0-9]*\).*|\1|p' <<<"$1")
  [ -z "$id" ] || rows "$vol" _data "_id=$id" | sed -n 's/.*_data=//p'
}
# The value Android's own picker would store for a file: id, title, canonical (empty if not indexed).
uri_of() {
  local row id title
  row=$(rows internal _id:title "_data='$1'")
  [[ $row == Row:* ]] || return 0
  row=${row%%$'\n'*}
  id=$(sed -n 's/.*_id=\([0-9]*\),.*/\1/p' <<<"$row")
  title=$(sed -n 's/.*title=//p' <<<"$row")
  echo "content://media/internal/audio/media/$id?title=${title// /%20}&canonical=1"
}
put() {
  valid "$2" || die "$1: refusing to write $2"
  if [ "$2" = null ]; then sh "settings delete system $1" >/dev/null; else sh "settings put system $1 '$2'"; fi
  [ "$(sh "settings get system $1")" = "$2" ] || die "$1 did not take the value $2"
}
# Fails if a setting plays a sound of the module, or a MediaStore item whose row is gone (a sound
# the module took away, once MediaStore has forgotten it).
clean() {
  local k v f
  for k in $KEYS; do
    v=$(sh "settings get system $k")
    [[ $v == content://media/* ]] || continue
    f=$(file_of "$v")
    [ -n "$f" ] || die "$k=$v plays no MediaStore row (a sound gone with the module?): choose another one in Android's Settings."
    ! ours "$f" || die "$k plays $f, a sound of the module: choose another one in Android's Settings."
  done
}
# Synchronous scan of /system, /product... (MediaProvider.call "scan_volume").
scan() { sh "content call --uri content://media --method scan_volume --arg internal" >/dev/null; }

adb get-state >/dev/null
case ${1:-} in
'')
  for k in $KEYS; do
    v=$(sh "settings get system $k")
    printf '%s=%s\n  %s\n' "$k" "$v" "$(file_of "$v")"
  done
  ;;
poser)
  { [ $# -le 2 ] && { [ -z "${2:-}" ] || [ "$2" = --ecraser ]; }; } || die "usage: tools/sons.sh poser [--ecraser]"
  [ -n "$(sh "ls $PROBE 2>/dev/null; true")" ] || die "$PROBE absent: the module (with its sounds) is not mounted."
  if [ -e "$SAVE" ]; then
    echo "Keeping the values saved in $SAVE."
  else
    saved=
    for k in $KEYS; do
      v=$(sh "settings get system $k")
      valid "$v" || die "$k=$v: not a value retour could write back, nothing saved nor changed."
      ! ours "$(file_of "$v")" || die "$k already plays a sound of the module: no original value to save."
      saved+="$k"$'\t'"$v"$'\n'
    done
    mkdir -p "$(dirname "$SAVE")"
    printf '%s' "$saved" >"$SAVE"
    echo "Saved in $SAVE:"
    cat "$SAVE"
  fi
  # Everything resolved before anything changes; MediaStore may not have seen the module's files.
  for pass in 1 2; do
    missing=
    for k in $KEYS; do [ -n "$(uri_of "$(target $k)")" ] || missing+=" $(target $k)"; done
    [ -z "$missing" ] && break
    [ $pass = 1 ] || die "Not in MediaStore after a scan:$missing"
    echo "Not in MediaStore yet:$missing. Scanning the internal volume."
    scan
  done
  # Which settings are the user's own, decided before any write. With --ecraser, one that differs from
  # the line the save holds would be lost (retour would give back the older value): refused.
  keep=
  for k in $KEYS; do
    cur=$(sh "settings get system $k")
    personal "$cur" "$(file_of "$cur")" || continue
    if [ "${2:-}" != --ecraser ]; then
      keep+=" $k"
      echo "$k kept: it plays $cur, a choice outside the module (tools/sons.sh poser --ecraser to replace it)."
    else
      [ "$(awk -F'\t' -v k=$k '$1 == k { print $2 }' "$SAVE")" = "$cur" ] ||
        die "$k=$cur is not the value saved in $SAVE: replacing it would lose it. Run tools/sons.sh retour first, then poser --ecraser. Nothing changed."
    fi
  done
  for k in $KEYS; do
    [[ " $keep " == *" $k "* ]] || put $k "$(uri_of "$(target $k)")"
  done
  for k in $KEYS; do echo "$k=$(sh "settings get system $k")"; done
  ;;
retour)
  if [ -e "$SAVE" ]; then
    # The whole save is checked before anything is written: one line per key, each key once.
    [ "$(wc -l <"$SAVE" | tr -d ' ')" = 3 ] || die "$SAVE: not 3 lines, nothing changed."
    for k in $KEYS; do
      [ "$(grep -c "^$k"$'\t' "$SAVE" || true)" = 1 ] || die "$SAVE: no single line for $k, nothing changed."
    done
    while IFS=$'\t' read -r k v; do
      [[ " $KEYS " == *" $k "* ]] && valid "$v" || die "$SAVE: unexpected line '$k	$v', nothing changed."
    done <"$SAVE"
    while IFS=$'\t' read -r k v; do
      cur=$(sh "settings get system $k")
      f=$(file_of "$cur")
      # What poser set, a sound of the module, or a row gone with it: back to the saved value.
      if [ "$f" = "$(target $k)" ] || ours "$f" || { [[ $cur == content://media/* ]] && [ -z "$f" ]; }; then
        put "$k" "$v"
        echo "$k back to $v"
      else
        echo "$k kept: $cur (${f:-not a MediaStore item}), chosen after poser."
      fi
    done <"$SAVE"
    clean
    rm "$SAVE"
    echo "Save $SAVE used and deleted."
  else
    clean
  fi
  # Module gone but its sounds still listed: the pickers would offer files that no longer exist.
  if [ -z "$(sh "ls $PROBE 2>/dev/null; true")" ] && [[ $(rows internal _id "_data='$PROBE'") == Row:* ]]; then
    scan
    [[ $(rows internal _id "_data='$PROBE'") != Row:* ]] || die "MediaStore still lists $PROBE after a scan."
    echo "Rescanned the internal volume: MediaStore no longer lists the module's sounds."
  fi
  ;;
*) die "usage: tools/sons.sh [poser|retour]" ;;
esac
