#!/usr/bin/env bash
# The 9900's End key: out of a call, End goes back to the home screen. And back exactly.
#
#   tools/touches.sh          read-only: end_button_behavior (system)
#   tools/touches.sh poser    saves end_button_behavior in build/state/touches-avant.txt of the main
#                             checkout (once: an existing save is kept, never overwritten), then sets
#                             it to 1 (home). The Q25 has 2 (sleep). Refuses a value that is neither
#                             unset nor a number, and 1 without a save (the original value would be
#                             lost). tools/install.sh runs it.
#   tools/touches.sh retour   checks the save; if the setting is still 1, writes the saved value back
#                             (deleted if it was unset); otherwise keeps the user's later choice.
#                             Deletes the save. Without a save, writes nothing. Exits 2 when the save
#                             is broken (kept, nothing written), 1 on any other failure.
#                             tools/uninstall.sh runs it, in both runs.
#
# poser and retour CHANGE THE PHONE'S STATE: ask the phone's owner first.
# What the setting does (PhoneWindowManager.interceptKeyBeforeQueueing in the Q25's services.jar):
# it is read for KEYCODE_ENDCALL only, which the module's Q25_keyboard.kl gives to End
# (KEY_HOMEPAGE). On End's up, when no call was ended on its down and it was not held: 1 calls
# goHome() (the home intent, nothing when home is already in front), 2 puts the screen to sleep,
# 3 does both (sleep when already home). Without the module End is HOME again and this setting
# changes nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
broken() { echo "$*" >&2; exit 2; }
# The main checkout's build/, also from a worktree. TOUCHES_SAVE: for tools/test_touches.py only.
SAVE=${TOUCHES_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/touches-avant.txt}
KEY=end_button_behavior
HOME_=1

# A read that fails ("cmd: Failure calling service settings: Failed transaction", an exception, no
# answer) is never a value: the script stops at once, before it writes the setting or deletes its save.
checked() {
  case $1 in '' | cmd:* | *Failure* | *Exception*)
    echo "The phone did not answer (${1:-nothing}): stopped, nothing more written, the save kept. Try again in a moment." >&2
    kill -TERM $$; exit 1 ;;
  esac
  printf '%s\n' "$1"
}
get() { checked "$(sh "settings get system $KEY")"; }
# A value we may save and write back: unset, or a number (Settings.System.getIntForUser).
valid() { [ "$1" = null ] || [[ $1 =~ ^[0-9]+$ ]]; }
put() {
  valid "$1" || die "refusing to write $1"
  if [ "$1" = null ]; then sh "settings delete system $KEY" >/dev/null; else sh "settings put system $KEY $1"; fi
  [ "$(get)" = "$1" ] || die "$KEY did not take the value $1 (now $(get))"
}

adb get-state >/dev/null
case ${1:-} in
'')
  echo "$KEY=$(get)"
  ;;
poser)
  if [ -e "$SAVE" ]; then
    echo "Keeping the value saved in $SAVE: $(cat "$SAVE")"
  else
    saved=$(get)
    valid "$saved" || die "$KEY=$saved: not a value retour could write back, nothing saved nor changed."
    [ "$saved" != $HOME_ ] || die "$KEY is already $HOME_ and no save: no original value to save, nothing changed."
    mkdir -p "$(dirname "$SAVE")"
    printf '%s\n' "$saved" >"$SAVE"
    echo "Saved in $SAVE: $saved"
  fi
  [ "$(get)" = $HOME_ ] || put $HOME_
  echo "$KEY=$(get)"
  ;;
retour)
  [ -e "$SAVE" ] || { echo "No save: $KEY=$(get) left as it is."; exit 0; }
  [ "$(wc -l <"$SAVE" | tr -d ' ')" = 1 ] || broken "$SAVE: not one line, nothing changed."
  saved=$(cat "$SAVE")
  valid "$saved" || broken "$SAVE: unexpected '$saved', nothing changed."
  cur=$(get)
  if [ "$cur" = $HOME_ ]; then
    put "$saved"
    echo "$KEY back to $saved"
  else
    echo "$KEY kept: $cur, chosen after poser."
  fi
  rm "$SAVE"
  echo "Save $SAVE used and deleted."
  ;;
*) die "usage: tools/touches.sh [poser|retour]" ;;
esac
