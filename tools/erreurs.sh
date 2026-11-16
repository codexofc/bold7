#!/usr/bin/env bash
# No dialog at an app's first crash, as on BlackBerry, and back exactly.
#
#   tools/erreurs.sh          read-only: show_first_crash_dialog and hide_error_dialogs (global),
#                             show_first_crash_dialog_dev_option (secure)
#   tools/erreurs.sh poser    saves show_first_crash_dialog in build/state/erreurs-avant.txt of the
#                             main checkout (once: an existing save is kept, never overwritten),
#                             then sets it to 0, AOSP's value (the Q25 has 1). Refuses a value that
#                             is neither unset nor a number, and 0 without a save (the original
#                             value would be lost).
#   tools/erreurs.sh retour   checks the save; if the setting is still 0, writes the saved value
#                             back (deleted if it was unset); otherwise keeps the user's later choice.
#                             Deletes the save. Without a save, writes nothing. Exits 2 when the
#                             save is broken (kept, nothing written), 1 on any other failure.
#                             tools/uninstall.sh runs it, in both runs.
#
# poser and retour CHANGE THE PHONE'S STATE: ask the phone's owner first.
# What the setting does (AppErrors.handleShowAppErrorUi in the Q25's services.jar): with 0, and
# the developer option show_first_crash_dialog_dev_option unset, a crash shows its dialog only
# when it repeats (the app crashed less than ActivityManagerConstants.MIN_CRASH_INTERVAL, 2 min,
# before); otherwise the app closes without a word. Once a crash dialog has shown, no other for
# the same app for 2 min (mProcessCrashShowDialogTimes). Not-responding dialogs never read it.
# hide_error_dialogs is never written: it would also close a blocked app without offering Wait.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
broken() { echo "$*" >&2; exit 2; }
# The main checkout's build/, also from a worktree. ERREURS_SAVE: for tools/test_erreurs.py only.
SAVE=${ERREURS_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/erreurs-avant.txt}
KEY=show_first_crash_dialog

get() { sh "settings get global $KEY"; }
# A value we may save and write back: unset, or a number (Settings.Global.getInt).
valid() { [ "$1" = null ] || [[ $1 =~ ^[0-9]+$ ]]; }
put() {
  valid "$1" || die "refusing to write $1"
  if [ "$1" = null ]; then sh "settings delete global $KEY" >/dev/null; else sh "settings put global $KEY $1"; fi
  [ "$(get)" = "$1" ] || die "$KEY did not take the value $1 (now $(get))"
}

adb get-state >/dev/null
case ${1:-} in
'')
  echo "$KEY=$(get)"
  echo "hide_error_dialogs=$(sh 'settings get global hide_error_dialogs')"
  echo "show_first_crash_dialog_dev_option=$(sh 'settings get secure show_first_crash_dialog_dev_option')"
  ;;
poser)
  if [ -e "$SAVE" ]; then
    echo "Keeping the value saved in $SAVE: $(cat "$SAVE")"
  else
    saved=$(get)
    valid "$saved" || die "$KEY=$saved: not a value retour could write back, nothing saved nor changed."
    [ "$saved" != 0 ] || die "$KEY is already 0 and no save: no original value to save, nothing changed."
    mkdir -p "$(dirname "$SAVE")"
    printf '%s\n' "$saved" >"$SAVE"
    echo "Saved in $SAVE: $saved"
  fi
  [ "$(get)" = 0 ] || put 0
  echo "$KEY=$(get)"
  ;;
retour)
  [ -e "$SAVE" ] || { echo "No save: $KEY=$(get) left as it is."; exit 0; }
  [ "$(wc -l <"$SAVE" | tr -d ' ')" = 1 ] || broken "$SAVE: not one line, nothing changed."
  saved=$(cat "$SAVE")
  valid "$saved" || broken "$SAVE: unexpected '$saved', nothing changed."
  cur=$(get)
  if [ "$cur" = 0 ]; then
    put "$saved"
    echo "$KEY back to $saved"
  else
    echo "$KEY kept: $cur, chosen after poser."
  fi
  rm "$SAVE"
  echo "Save $SAVE used and deleted."
  ;;
*) die "usage: tools/erreurs.sh [poser|retour]" ;;
esac
