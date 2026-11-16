#!/usr/bin/env bash
# The DIALER role (calls, their screen) given to Bold7's Phone (dev.bold7.phone), and back exactly.
#
#   tools/dialer.sh          read-only: the role's holder, Telecom's default and system dialers,
#                            whether dev.bold7.phone is installed, the save
#   tools/dialer.sh poser    saves the role's holder in build/state/dialer-avant.txt of the main
#                            checkout (once: an existing save is kept, never overwritten), then gives
#                            the role to dev.bold7.phone and checks it in the role and in Telecom.
#                            Refuses without the app, a holder that is not a package name, and
#                            dev.bold7.phone already holding it without a save (the original holder
#                            would be lost).
#   tools/dialer.sh retour   checks the save; if dev.bold7.phone still holds the role, gives it back
#                            to the saved holder (to Google's Phone if that one is gone), checked;
#                            otherwise keeps the user's later choice. Deletes the save. Without a save,
#                            gives the role to Google's Phone if dev.bold7.phone holds it, else does
#                            nothing. Exits 2 when the save is broken (kept, nothing changed), 1 on
#                            any other failure (the save kept). tools/uninstall.sh runs it, in both
#                            runs, before uninstalling the app.
#
# poser and retour CHANGE THE PHONE'S STATE (who shows every call): ask the phone's owner first.
# The way back without a computer: Android's Settings, Apps, Default apps, Phone app. By adb, in
# one command: adb shell cmd role add-role-holder android.app.role.DIALER com.google.android.dialer
# Uninstalling dev.bold7.phone also gives the role back to Google's Phone (the role's
# fallBackToDefaultHolder, config_defaultDialer), which stays Telecom's system dialer throughout.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell -n "$@" | tr -d '\r'; }
die() { echo "$*" >&2; exit 1; }
broken() { echo "$*" >&2; exit 2; }
# The main checkout's build/, also from a worktree. DIALER_SAVE, DIALER_WAIT: for tools/test_dialer.py only.
SAVE=${DIALER_SAVE:-$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state/dialer-avant.txt}
WAIT=${DIALER_WAIT:-5}
ROLE=android.app.role.DIALER
OURS=dev.bold7.phone
# config_defaultDialer of the Q25 (cmd overlay lookup), the system dialer of Telecom.
GOOGLE=com.google.android.dialer

holder() { sh "cmd role get-role-holders $ROLE"; }
installed() { [[ $(sh "pm path $1" || true) == package:* ]]; }
valid() { [[ $1 =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$ ]]; }
# give <package>: the role to it, checked in the role and in Telecom. Right after boot the role
# service can time out (TimeoutException, tools/uninstall.sh): asked again for a minute.
give() {
  local out i
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    out=$(sh "cmd role add-role-holder $ROLE $1" 2>&1 || true)
    case $out in *Exception*) sleep "$WAIT" ;; *) break ;; esac
  done
  [ "$(holder)" = "$1" ] || die "DIALER is held by '$(holder)', not $1: adb shell cmd role add-role-holder $ROLE $1"
  [ "$(sh 'telecom get-default-dialer')" = "$1" ] || die "Telecom's default dialer is '$(sh 'telecom get-default-dialer')', not $1."
  echo "DIALER given to $1."
}

adb get-state >/dev/null
case ${1:-} in
'')
  echo "DIALER=$(holder)"
  echo "default dialer=$(sh 'telecom get-default-dialer')"
  echo "system dialer=$(sh 'telecom get-system-dialer')"
  if installed $OURS; then echo "$OURS installed"; else echo "$OURS not installed"; fi
  if [ -e "$SAVE" ]; then echo "save $SAVE: $(cat "$SAVE")"; else echo "no save"; fi
  ;;
poser)
  installed $OURS || die "$OURS is not installed: tools/install.sh installs it. Nothing changed."
  if [ -e "$SAVE" ]; then
    echo "Keeping the holder saved in $SAVE: $(cat "$SAVE")"
  else
    saved=$(holder)
    valid "$saved" || die "DIALER is held by '$saved': not a holder retour could give the role back to. Nothing saved nor changed."
    [ "$saved" != $OURS ] || die "$OURS already holds DIALER and nothing is saved: no original holder to save. Nothing changed."
    mkdir -p "$(dirname "$SAVE")"
    printf '%s\n' "$saved" >"$SAVE"
    echo "Saved in $SAVE: $saved"
  fi
  [ "$(holder)" = $OURS ] || give $OURS
  echo "Back to the saved holder: tools/dialer.sh retour"
  ;;
retour)
  if [ ! -e "$SAVE" ]; then
    if [ "$(holder)" = $OURS ]; then
      echo "No save, and $OURS holds DIALER: giving it to $GOOGLE."
      give $GOOGLE
    else
      echo "No save: DIALER=$(holder) left as it is."
    fi
    exit 0
  fi
  [ "$(wc -l <"$SAVE" | tr -d ' ')" = 1 ] || broken "$SAVE: not one line, nothing changed."
  saved=$(cat "$SAVE")
  valid "$saved" || broken "$SAVE: unexpected '$saved', nothing changed."
  cur=$(holder)
  if [ "$cur" = $OURS ]; then
    if ! installed "$saved"; then
      echo "$saved, the holder saved, is not installed any more: $GOOGLE instead."
      saved=$GOOGLE
    fi
    give "$saved"
  else
    echo "DIALER kept: $cur, chosen after poser."
  fi
  rm "$SAVE"
  echo "Save $SAVE used and deleted."
  ;;
*) die "usage: tools/dialer.sh [poser|retour]" ;;
esac
