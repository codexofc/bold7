#!/usr/bin/env bash
# The trackpad of OS 7 (bold7-trackpad): what it changes on the phone,
# and the way back.
#
#   tools/trackpad.sh          read-only: the driver's mode (/proc/q20_switch_key_mouse, 1 arrows,
#                              0 raw), bold7-trackpad's processes, the bold7_trackpad_* settings,
#                              and whether Q25TrackpadCustomizer's accessibility service is on
#   tools/trackpad.sh poser    turns Q25TrackpadCustomizer's service off: it switches the driver's
#                              mode at each app change, which bold7-trackpad would have to undo
#                              each time. Saves enabled_accessibility_services in
#                              build/state/trackpad-avant.txt of the main checkout (once: an
#                              existing save is kept, never overwritten), then removes the service
#                              from it, the others (Key Mapper, keymapperbootfix) untouched. The app
#                              stays installed (BenOS's). Refuses a list with an apostrophe.
#   tools/trackpad.sh retour   stops a start still waiting for the SIM (trackpad-start.sh), then
#                              bold7-trackpad (SIGTERM: it gives the keyboard and the
#                              trackpad back to Android itself), checks the driver is in arrow mode
#                              and puts it back otherwise, deletes the bold7_trackpad_* settings,
#                              then turns the service back on: the saved list exactly if the list
#                              is still the one poser left, otherwise the service added to the
#                              current list (the user's later changes kept). Deletes the save. Goes
#                              through every step, and exits 1 if one failed (2: the save is
#                              broken, kept, the service not touched). tools/uninstall.sh runs it,
#                              in both runs.
#
# poser and retour CHANGE THE PHONE'S STATE: ask the phone's owner first.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
# The main checkout's build/, also from a worktree. TRACKPAD_SAVE: for tools/test_trackpad.py only.
SAVE=${TRACKPAD_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/trackpad-avant.txt}
KEY=enabled_accessibility_services
SERVICE=tech.shroyer.q25trackpadcustomizer/tech.shroyer.q25trackpadcustomizer.AppSwitchService
SETTINGS="bold7_trackpad_off bold7_trackpad_horizontal bold7_trackpad_vertical bold7_trackpad_audible bold7_trackpad_palm_band bold7_trackpad_palm_delay"
MODE=/proc/q20_switch_key_mouse

# A read that fails ("cmd: Failure calling service settings: Failed transaction", an exception, no
# answer) is never a value: the script stops at once, before it writes the setting or deletes its save.
checked() {
  case $1 in '' | cmd:* | *Failure* | *Exception*)
    echo "The phone did not answer (${1:-nothing}): stopped, nothing more written, the save kept. Try again in a moment." >&2
    kill -TERM $$; exit 1 ;;
  esac
  printf '%s\n' "$1"
}
get() { checked "$(sh "settings get secure $KEY")"; }
has() { [[ ":$1:" == *":$SERVICE:"* ]]; }
# $1 without the service, in its order.
without() { tr ':' '\n' <<<"$1" | grep -vxF "$SERVICE" | paste -sd: - || true; }
put() {
  [[ $1 != *"'"* ]] || die "refusing to write a list with an apostrophe: $1"
  if [ -z "$1" ] || [ "$1" = null ]; then sh "settings delete secure $KEY" >/dev/null; else sh "settings put secure $KEY '$1'"; fi
  local now; now=$(get)
  [ "$now" = "$1" ] || { [ -z "$1" ] && [ "$now" = null ]; } || die "$KEY did not take the value $1 (now $now)"
}
mode() { sh "su -c 'cat $MODE'"; }
pids() { sh "su -c 'pidof bold7-trackpad'" || true; }

adb get-state >/dev/null
case ${1:-} in
'')
  echo "mode=$(mode)"
  if [ -n "$(pids)" ]; then echo "bold7-trackpad=running"; else echo "bold7-trackpad=stopped"; fi
  for k in $SETTINGS; do echo "$k=$(sh "settings get global $k")"; done
  if has "$(get)"; then echo "q25trackpadcustomizer-service=on"; else echo "q25trackpadcustomizer-service=off"; fi
  ;;
poser)
  cur=$(get)
  has "$cur" || { echo "Q25TrackpadCustomizer's service is already off: nothing saved nor changed."; exit 0; }
  [[ $cur != *"'"* ]] || die "$KEY has an apostrophe: nothing saved nor changed."
  if [ -e "$SAVE" ]; then
    echo "Keeping the list saved in $SAVE."
  else
    mkdir -p "$(dirname "$SAVE")"
    printf '%s\n' "$cur" >"$SAVE"
    echo "Saved in $SAVE: $cur"
  fi
  put "$(without "$cur")"
  echo "$KEY=$(get)"
  ;;
retour)
  failed=0
  # A start still waiting for the SIM (trackpad-start.sh) would take the keyboard later.
  sh "su -c 'pkill -f trackpad-start.sh'" >/dev/null 2>&1 || true
  if [ -n "$(pids)" ]; then
    sh "su -c 'pkill -TERM -x bold7-trackpad'" || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -z "$(pids)" ] && break; sleep 0.5; done
    if [ -n "$(pids)" ]; then
      echo "bold7-trackpad still running: adb shell \"su -c 'pkill -KILL -x bold7-trackpad'\", then tools/trackpad.sh retour again." >&2
      failed=1
    else
      echo "bold7-trackpad stopped."
    fi
  fi
  if [ "$(mode)" != 1 ]; then
    sh "su -c 'echo 1 > $MODE'" || true
    if [ "$(mode)" = 1 ]; then echo "Trackpad back in arrow mode."; else echo "$MODE is $(mode), not 1." >&2; failed=1; fi
  fi
  for k in $SETTINGS; do
    sh "settings delete global $k" >/dev/null || { echo "Could not delete $k: adb shell settings delete global $k" >&2; failed=1; }
  done
  if [ ! -e "$SAVE" ]; then
    echo "No save: $KEY left as it is."
  else
    saved=$(cat "$SAVE")
    if [ "$(wc -l <"$SAVE" | tr -d ' ')" != 1 ] || ! has "$saved" || [[ $saved == *"'"* ]]; then
      echo "$SAVE: not the one-line list poser saved, kept, the service not touched." >&2
      exit 2
    fi
    cur=$(get)
    if [ "$cur" = "$(without "$saved")" ] || { [ "$cur" = null ] && [ -z "$(without "$saved")" ]; }; then
      put "$saved"
      echo "$KEY back to $saved"
    elif has "$cur"; then
      echo "Q25TrackpadCustomizer's service is on already: $KEY kept."
    else
      if [ "$cur" = null ] || [ -z "$cur" ]; then put "$SERVICE"; else put "$cur:$SERVICE"; fi
      echo "$KEY changed after poser: the service added to it ($(get))."
    fi
    rm "$SAVE"
    echo "Save $SAVE used and deleted."
  fi
  exit $failed
  ;;
*) die "usage: tools/trackpad.sh [poser|retour]" ;;
esac
