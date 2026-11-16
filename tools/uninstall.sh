#!/usr/bin/env bash
# Removes Bold7 from the Q25, with Vector, in two runs around a reboot, for development.
#
#   The module gives back itself what it changed (system/module/src/setup.sh): its
#   uninstall.sh, at the boot that follows the removal, leaves a script in /data/adb/boot-completed.d
#   which, once the phone is unlocked, gives back the values saved in /data/adb/bold7-state (sounds,
#   silence, End key, trackpad, HOME, DIALER, input method), each only while it is still the one
#   Bold7 set, turns Do Not Disturb off if a profile's policy was left, deletes the bold7_* settings,
#   disables SystemUI's palette overlays, uninstalls the apps, then deletes itself.
#   What stays here: the saves an older tools/install.sh and the tools/*.sh poser scripts wrote in
#   build/state of the main checkout, given back before the removal, and what only a living launcher
#   or the Mac can do.
#   Both runs first put the sound settings back (tools/sons.sh retour), then the first crash dialog
#   (tools/erreurs.sh retour), the interface sounds (tools/silence.sh retour), the End key
#   (tools/touches.sh retour), the trackpad (tools/trackpad.sh retour) and the DIALER role
#   (tools/dialer.sh retour), each from its save in build/state; a failure there does not stop the
#   script, it is said again as the last line and the script exits non-zero.
#   Then, should a profile have left its Do Not Disturb policy in place of the user's, it turns Do
#   Not Disturb off so that the launcher, still running, gives it back; failing that, saves it in
#   build/state/dnd-policy-avant.txt, shows it, says it again as the last line and exits non-zero.
#   Deletes the launcher's profiles preferences and withdraws its Do Not Disturb access.
#   Then the input method an older tools/install.sh saved (build/state/saisie-avant.txt), and Google
#   Messages' API consent switched back off on its own screen (phone unlocked; a failure is said
#   again as the last line).
#   1st run: marks the bold7 and zygisk_vector KernelSU modules for removal; KernelSU deletes them at
#   the reboot, and runs the module's uninstall.sh.
#   2nd run, after the reboot and the unlock: waits for the module's restore to be done (its script
#   gone from /data/adb/boot-completed.d; what it could not give back is shown), checks that none of
#   the module's mounts is left, gives home back to Launcher3 (enabled again if it was disabled),
#   removes the empty /data/misc/<uuid> Vector leaves behind, then compares with the state saved
#   before the first install (build/state/before-install.txt of the main checkout).
#
# It refuses while the BB10 build (module "cascade") is still on the phone, and says how to remove it.
#
# CHANGES THE PHONE'S STATE: ask the phone's owner first.
set -euo pipefail
cd "$(dirname "$0")/.."
sh() { adb shell "$@" | tr -d '\r'; }
# The main checkout's build/state, also from a worktree (tools/sons.sh does the same).
STATE=$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state
BBKB=com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME
L=dev.bold7.launcher
L3=com.android.launcher3
. tools/messages-api.sh

adb get-state >/dev/null
# Without root, an empty module list below would pass for the second run.
sh "su -c 'ksud -V'" | grep -q ksud || { echo "KernelSU (ksud) not reachable as root." >&2; exit 1; }
# The BB10 build (formerly Cascade) is not this script's to remove (tools/guard-old.sh).
. tools/guard-old.sh
refuse_if_old_build "Then this script removes Bold7." || exit 1
# Stops here, before anything is removed, if a setting would be left on a sound of the module.
tools/sons.sh retour
# Never stops the script: show_first_crash_dialog does not leave with anything removed here. A
# failure is said again as the last line and makes the script exit non-zero, on every way out
# (trap on EXIT: also after the first run). erreurs.sh exits 2 on a broken save, which running it
# again cannot mend.
erreurs=0
tools/erreurs.sh retour || erreurs=$?
# Interface sounds: same rule. silence.sh exits 2 on a broken save.
silence=0
tools/silence.sh retour || silence=$?
# end_button_behavior: same rule; harmless once the module's Q25_keyboard.kl is gone.
touches=0
tools/touches.sh retour || touches=$?
# The trackpad's daemon stops now, before its module goes: keyboard and trackpad Android's again.
trackpad=0
tools/trackpad.sh retour || trackpad=$?
# Calls first: the role back to Google's Phone (or the holder saved), while our Phone is still there.
dialer=0
tools/dialer.sh retour || dialer=$?
# Set below when the Do Not Disturb policy could not be given back.
dnd_left=
# Set below when Google Messages' API consent could not be switched back off.
consent_left=
uninstall_end() {
  local failed=0
  case $dialer in
    0) ;;
    2) failed=1; echo "DIALER not given back: $STATE/dialer-avant.txt is broken (see above), kept. Read it, then adb shell cmd role add-role-holder android.app.role.DIALER <its package>, and delete it." >&2 ;;
    *) failed=1; echo "DIALER not given back by tools/dialer.sh retour (see above): adb shell cmd role add-role-holder android.app.role.DIALER com.google.android.dialer" >&2 ;;
  esac
  case $trackpad in
    0) ;;
    2) failed=1; echo "Q25TrackpadCustomizer's service not put back: $STATE/trackpad-avant.txt is broken (see above), kept. Read it, set enabled_accessibility_services by hand, then delete it." >&2 ;;
    *) failed=1; echo "The trackpad not fully given back (see above): run tools/trackpad.sh retour again." >&2 ;;
  esac
  case $erreurs in
    0) ;;
    2) failed=1; echo "show_first_crash_dialog not put back: $STATE/erreurs-avant.txt is broken (see above), kept, nothing written. Read it, set the original value by hand (adb shell settings put global show_first_crash_dialog <value>, or settings delete if it was null), then delete it." >&2 ;;
    *) failed=1; echo "show_first_crash_dialog not put back (see above), the save $STATE/erreurs-avant.txt is kept: run tools/erreurs.sh retour again." >&2 ;;
  esac
  case $silence in
    0) ;;
    2) failed=1; echo "The interface sounds not put back: $STATE/silence-avant.txt is broken (see above), kept, nothing written. Read it, set each original value by hand (adb shell settings put <global|secure|system> <key> <value>, or settings delete if it was null), then delete it." >&2 ;;
    *) failed=1; echo "The interface sounds not put back (see above), the save $STATE/silence-avant.txt is kept: run tools/silence.sh retour again." >&2 ;;
  esac
  case $touches in
    0) ;;
    2) failed=1; echo "end_button_behavior not put back: $STATE/touches-avant.txt is broken (see above), kept, nothing written. Read it, set the original value by hand (adb shell settings put system end_button_behavior <value>, or settings delete if it was null), then delete it." >&2 ;;
    *) failed=1; echo "end_button_behavior not put back (see above), the save $STATE/touches-avant.txt is kept: run tools/touches.sh retour again." >&2 ;;
  esac
  if [ "$dnd_left" = '?' ]; then
    failed=1
    echo "Do Not Disturb policy not checked: the launcher's profiles preferences could not be read (see above). If a profile's Do Not Disturb is on, turn it off and set your people and apps back by hand in Android's Settings, Sound, Do Not Disturb." >&2
  elif [ -n "$dnd_left" ]; then
    failed=1
    echo "Do Not Disturb policy not put back (see above): saved in $STATE/dnd-policy-avant.txt. Set its people and apps back by hand in Android's Settings, Sound, Do Not Disturb, then delete that file." >&2
  fi
  if [ -n "$consent_left" ]; then
    failed=1
    echo "Google Messages' consent \"Allow Bixby and text on Galaxy devices\" left on (see above): unlock the phone and run this script again, or switch it off by hand (adb shell su -c 'am start -n $GM_CONSENT')." >&2
  fi
  if [ "$failed" != 0 ]; then exit 1; fi
}
trap uninstall_end EXIT
# A Do Not Disturb policy as the launcher saves it (categories,calls,messages,visual effects,
# conversations: NotificationManager.Policy), in words.
dnd_policy_show() {
  local cat calls msgs visual conv bit through='' held='' who convs
  IFS=, read -r cat calls msgs visual conv <<<"$1"
  if ! [[ "$cat,$calls,$msgs,$visual,$conv" =~ ^[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+$ ]]; then
    echo "  unreadable: $1"
    return
  fi
  for bit in 1:reminders 2:events 4:messages 8:calls 16:repeat_callers 32:alarms 64:media 128:system 256:conversations; do
    if (( cat & ${bit%%:*} )); then through+=" ${bit#*:}"; fi
  done
  for bit in 1:screen_off 2:screen_on 4:full_screen_intent 8:lights 16:peek 32:status_bar 64:badge 128:ambient 256:notification_list; do
    if (( visual & ${bit%%:*} )); then held+=" ${bit#*:}"; fi
  done
  who=(anyone contacts starred none)
  convs=(unset all important none)
  echo "  let through:${through:- nothing}"
  echo "  calls from: ${who[$calls]:-$calls}; messages from: ${who[$msgs]:-$msgs}; conversations: ${convs[$conv]:-$conv}"
  echo "  visual effects held back:${held:- none}"
}
# Phone Calls Only, Silent and All Alerts Off put the profile's Do Not Disturb policy
# in place of the user's, kept in the launcher's preferences (PREFS below, device-protected storage,
# read as root); the launcher gives it back as soon as Do Not Disturb ends, and only it can (no
# shell command sets a policy). Never stops the script: Do Not Disturb is turned off from the shell,
# which makes a living launcher give the policy back. Without it (the launcher not running,
# KernelSU safe mode, the second run), the policy is saved in $STATE/dnd-policy-avant.txt, shown in
# words, said again as the last line, and the script exits non-zero. The preferences are deleted
# either way (the profile chosen and the volumes go with them), and the launcher's access withdrawn.
PREFS=/data/user_de/0/$L/shared_prefs/profiles.xml
saved_policy() { sh "su -c 'cat $PREFS 2>/dev/null; true'" | sed -n 's/.*name="dnd_policy">\([^<]*\)<.*/\1/p'; }
# An adb or su that fails (set -e, pipefail) must never stop the removal: "?" stands for a policy
# that could not be read, handled as one not given back.
policy=$(saved_policy || echo '?')
if [ -n "$policy" ]; then
  echo "A profile's Do Not Disturb policy stands in for yours: turning Do Not Disturb off."
  sh 'cmd notification set_dnd off' >/dev/null 2>&1 ||
    echo "Could not turn Do Not Disturb off: adb shell cmd notification set_dnd off" >&2
  for _ in $(seq 10); do
    sleep 1
    policy=$(saved_policy || echo '?')
    if [ -z "$policy" ]; then break; fi
  done
  if [ -z "$policy" ]; then
    echo "The launcher gave your Do Not Disturb policy back."
  elif [ "$policy" = '?' ]; then
    dnd_left='?'
    echo "Could not read the launcher's preferences ($PREFS): is Do Not Disturb still on with a profile's policy? Check by hand." >&2
  else
    mkdir -p "$STATE"
    echo "$policy" > "$STATE/dnd-policy-avant.txt"
    dnd_left=$policy
    echo "The launcher did not give your Do Not Disturb policy back ($policy), saved in $STATE/dnd-policy-avant.txt:" >&2
    dnd_policy_show "$policy" >&2
  fi
fi
sh "su -c 'rm -f $PREFS'" >/dev/null 2>&1 ||
  echo "Could not delete $PREFS: adb shell su -c 'rm -f $PREFS'" >&2
# The launcher's Do Not Disturb access (tools/install.sh) goes with its package; withdrawn here
# too, should an update of the launcher outlive the module. Never stops the script.
sh "cmd notification disallow_dnd $L" >/dev/null 2>&1 ||
  echo "Could not withdraw the Do Not Disturb access of $L: cmd notification disallow_dnd $L" >&2
# The showcase's made-up data (tools/showcase-capture.sh) switched off, should a capture have been
# interrupted; the module's restore deletes it too, with every bold7_ setting. Never stops the script.
sh "settings delete global bold7_showcase" >/dev/null 2>&1 ||
  echo "Could not delete bold7_showcase: adb shell settings delete global bold7_showcase" >&2
# Bold7's input method: the one an older tools/install.sh saved in
# $STATE/saisie-avant.txt selected again, the enabled list put back; the save goes once checked. The
# module's own save (/data/adb/bold7-state) and Typing itself are the module's restore's. Never stops
# the script.
SAISIE_SAVE=$STATE/saisie-avant.txt
sh "settings delete global bold7_saisie" >/dev/null 2>&1 ||
  echo "Could not delete bold7_saisie: adb shell settings delete global bold7_saisie" >&2
# The profile the launcher publishes for the lock screen (the module's restore deletes every bold7_*).
sh "settings delete global bold7_profile" >/dev/null 2>&1 ||
  echo "Could not delete bold7_profile: adb shell settings delete global bold7_profile" >&2
if [ -e "$SAISIE_SAVE" ]; then
  ime=$(sed -n 's/^default_input_method=//p' "$SAISIE_SAVE")
  list=$(sed -n 's/^enabled_input_methods=//p' "$SAISIE_SAVE")
  if [ "$(wc -l <"$SAISIE_SAVE" | tr -d ' ')" != 2 ] || [ -z "$ime" ] || [ "$ime" = null ] || [[ $ime$list == *"'"* ]]; then
    echo "$SAISIE_SAVE is not the save tools/install.sh writes, kept: selecting $BBKB, the enabled list left as it is." >&2
    ime=$BBKB list=
  fi
  sh "ime set $ime" >/dev/null 2>&1 || true
  if [ -n "$list" ] && [ "$list" != null ]; then
    sh "settings put secure enabled_input_methods '$list'" >/dev/null 2>&1 ||
      echo "Could not put enabled_input_methods back: adb shell settings put secure enabled_input_methods '$list'" >&2
  fi
  now=$(sh "settings get secure default_input_method" 2>/dev/null || true)
  if [ "$now" != "$ime" ]; then
    echo "The input method is $now, not $ime: adb shell ime set $ime" >&2
  else
    echo "Input method $ime."
    if [ -n "$list" ]; then rm -f "$SAISIE_SAVE"; fi
  fi
fi
# Google Messages' API consent, given by tools/install.sh: off again while Messages of
# Bold7 is still there, whatever the bridge. Never stops the script.
if [[ $(sh "pm path $GM" 2>/dev/null || true) == package:* ]]; then
  set_consent false || consent_left=1
fi
# A command that fails stops the script here (set -e); only an absent module gives an empty list.
modules=$(sh "su -c 'ls /data/adb/modules'")
present=$(echo "$modules" | grep -xE 'bold7|zygisk_vector' || true)
if [ -n "$present" ]; then
  # Removal, never disable (docs/DEVICE.md).
  for m in $present; do sh "su -c 'ksud module uninstall $m'"; done
  echo "Marked for removal:" $present". Reboot, then run tools/uninstall.sh again."
  exit 0
fi

# The module's uninstall.sh left a script in /data/adb/boot-completed.d that gives back, once the
# phone is unlocked, what goes through Android (setup.sh restore), then deletes itself.
if [ -n "$(sh "su -c 'ls /data/adb/boot-completed.d/bold7-retour.sh 2>/dev/null; true'")" ]; then
  echo "The module's restore has not run yet: unlock the phone, wait a minute, then run this again." >&2
  exit 1
fi
restore_left=$(sh "su -c 'cat /data/adb/bold7-state/restore.log 2>/dev/null; true'")
if [ -n "$restore_left" ]; then
  echo "The module's restore did not give everything back, /data/adb/bold7-state is kept:" >&2
  echo "$restore_left" >&2
fi

# Our overlays carry the source "bold7", the image is mounted on the module's img/
# (system/module/src/post-fs-data.sh).
left=$(sh "su -c 'grep -e \" - overlay bold7 \" -e /data/adb/modules/bold7 /proc/1/mountinfo; true'")
[ -z "$left" ] || { echo "Module gone but mounts left (reboot first?):" >&2; echo "$left" >&2; exit 1; }

# Between test sessions home stays Launcher3. Now that the recents overlay
# is gone it is the recents provider again, without which it crash-loops as home (docs/DEVICE.md).
if sh "pm list packages -d" | grep -qx "package:$L3"; then
  sh "pm enable $L3" >/dev/null
  echo "Re-enabled $L3."
fi
# Right after boot the role service can time out (TimeoutException): try again for a minute.
for i in $(seq 12); do
  out=$(sh "cmd role add-role-holder android.app.role.HOME $L3" 2>&1 || true)
  case $out in *Exception*) ;; *) break ;; esac
  [ "$i" = 12 ] && { echo "HOME could not be given back to $L3: run tools/uninstall.sh again." >&2; exit 1; }
  sleep 5
done

# Vector's daemon leaves an empty /data/misc/<uuid> labelled xposed_data (spike S2). Only an
# empty one goes (rmdir refuses anything else), and only that label.
for d in $(sh "su -c 'ls -dZ /data/misc/*'" |
             awk '/:xposed_data:/ { for (i = 1; i <= NF; i++) if ($i ~ /^\/data\/misc\//) print $i }'); do
  if [ -n "$(sh "su -c 'rmdir $d && echo gone; true'")" ]; then
    echo "Removed Vector's empty $d."
  else
    echo "Kept $d (xposed_data, not empty): look at it before deleting it." >&2
  fi
done

echo "Modules gone, no mount left, home is $(sh 'cmd role get-role-holders android.app.role.HOME')."
echo "Differences with $STATE/before-install.txt:"
tools/state.sh | diff "$STATE/before-install.txt" - && echo "(none)"
