#!/usr/bin/env bash
# Android's interface sounds that the Bold 9900 does not have, and its automatic rotation, switched off,
# and back exactly.
#
#   tools/silence.sh          read-only: each setting and its value
#   tools/silence.sh poser    saves, in build/state/silence-avant.txt of the main checkout (once: an
#                             existing save is kept, never overwritten), the settings below that
#                             differ from their target, then sets each to 0. A setting already at 0
#                             is neither saved nor touched. Refuses a value that is neither unset
#                             nor a number, before writing anything.
#   tools/silence.sh retour   checks the save, then puts back each saved setting that still holds
#                             the target (deleted if it was unset); any other value is a choice made
#                             after poser, kept. Deletes the save. Without a save, writes nothing.
#                             Exits 2 when the save is broken (kept, nothing written), 1 on any
#                             other failure. tools/uninstall.sh runs it, in both runs.
#
# poser and retour CHANGE THE PHONE'S STATE: ask the phone's owner first.
# The 9900 plays nothing when the charger is plugged, when the screen locks or unlocks, or when
# the screen is touched (it has no touch sounds). Key tones are an option
# of Typing and Language > Keyboard, a typing matter, not touched here. The low battery
# sound (low_battery_sound, power_sounds_enabled) stays: the 9900 warns too and the guide says its
# low battery notification cannot be turned off. Ringtone, notification and alarm: tools/sons.sh.
# SILENCE_SAVE: for tools/test_silence.py only.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
broken() { echo "$*" >&2; exit 2; }
SAVE=${SILENCE_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/silence-avant.txt}
# space key: charger plugged (sound, vibration), lock and unlock, touch sounds (Settings.System.SOUND_EFFECTS_ENABLED),
# and the automatic rotation (Settings.System.ACCELEROMETER_ROTATION): the Q25's screen is square and the
# 9900's screen does not turn, so rotation serves nothing here. The module's framework RRO
# also says the device has no automatic rotation (config_supportAutoRotation).
# And Android's « … pasted from your clipboard » toast (Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS):
# the 9900 says nothing when an app pastes.
KEYS="global:charging_sounds_enabled secure:charging_vibration_enabled system:lockscreen_sounds_enabled system:sound_effects_enabled system:accelerometer_rotation secure:clipboard_show_access_notifications"

# A read that fails ("cmd: Failure calling service settings: Failed transaction", an exception, no
# answer) is never a value: the script stops at once, before it writes the setting or deletes its save.
checked() {
  case $1 in '' | cmd:* | *Failure* | *Exception*)
    echo "The phone did not answer (${1:-nothing}): stopped, nothing more written, the save kept. Try again in a moment." >&2
    kill -TERM $$; exit 1 ;;
  esac
  printf '%s\n' "$1"
}
get() { checked "$(sh "settings get ${1%%:*} ${1#*:}")"; }
valid() { [ "$1" = null ] || [[ $1 =~ ^[0-9]+$ ]]; }
put() { # <space:key> <value>
  valid "$2" || die "refusing to write $2"
  if [ "$2" = null ]; then sh "settings delete ${1%%:*} ${1#*:}" >/dev/null; else sh "settings put ${1%%:*} ${1#*:} $2" >/dev/null; fi
  [ "$(get "$1")" = "$2" ] || die "$1 did not take the value $2 (now $(get "$1"))"
}

adb get-state >/dev/null
case ${1:-} in
'')
  for k in $KEYS; do echo "${k#*:}=$(get "$k")"; done
  ;;
poser)
  if [ -e "$SAVE" ]; then
    echo "Keeping the values saved in $SAVE."
  else
    lines=""
    for k in $KEYS; do
      v=$(get "$k")
      valid "$v" || die "$k=$v: not a value retour could write back, nothing saved nor changed."
      [ "$v" = 0 ] || lines="$lines${k#*:} $v"$'\n'
    done
    mkdir -p "$(dirname "$SAVE")"
    printf '%s' "$lines" >"$SAVE"
    echo "Saved in $SAVE: $(echo "$lines" | tr '\n' ';')"
  fi
  for k in $KEYS; do
    [ "$(get "$k")" = 0 ] || put "$k" 0
    echo "${k#*:}=$(get "$k")"
  done
  ;;
retour)
  [ -e "$SAVE" ] || { echo "No save: nothing put back."; exit 0; }
  rows=()
  while read -r key value extra; do
    [ -z "$key" ] && continue
    full=""
    for k in $KEYS; do [ "${k#*:}" = "$key" ] && full=$k; done
    [ -n "$full" ] && [ -z "$extra" ] && valid "${value:-x}" || broken "$SAVE: unexpected line '$key $value $extra', nothing changed."
    rows+=("$full $value")
  done <"$SAVE"
  for r in ${rows[@]+"${rows[@]}"}; do
    k=${r% *}; v=${r#* }; cur=$(get "$k")
    if [ "$cur" = 0 ]; then put "$k" "$v"; echo "${k#*:} back to $v"; else echo "${k#*:} kept: $cur, chosen after poser."; fi
  done
  rm "$SAVE"
  echo "Save $SAVE used and deleted."
  ;;
*) die "usage: tools/silence.sh [poser|retour]" ;;
esac
