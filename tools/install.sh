#!/usr/bin/env bash
# Builds the Bold7 KernelSU module and installs it on the Q25, for development: the same zip a user
# installs from KernelSU's manager, pushed then installed by ksud.
#
# CHANGES THE PHONE'S STATE: ask the phone's owner first. The module becomes active at the next reboot, which
# this script does not do. Before the first install it saves a tools/state.sh snapshot in
# build/state/before-install.txt of the main checkout, the reference tools/uninstall.sh is checked
# against.
#
# Everything else is the module's own (system/module/src/setup.sh), so that installing
# the zip from the manager is enough:
#   customize.sh refuses a phone that is not a Q25 under BenOS, a metamodule, the BB10 build (module
#   "cascade", packages dev.cascade.*) or a stock Q25_keyboard.kl other than the one the module's copy
#   was made from, all before changing anything; saves once, in /data/adb/bold7-state, the values Bold7
#   changes; drops an update of the launcher installed over its priv-app; installs Vector if it is
#   missing (the zip embeds it) and the apps (pm install);
#   boot-completed.sh, once the user's storage is open, sets once each value saved (the sounds of OS 7,
#   the silence, the End key, Q25TrackpadCustomizer's service off, HOME, DIALER, Typing) and gives
#   the apps their permissions, app-ops, accesses and Doze exemption once per install, then scopes
#   dev.bold7.sysui in Vector (SystemUI restarted once).
# What stays here, for development only: the build fingerprint (another one means an OTA: re-check
# docs/DEVICE.md), the reference state, and Google Messages' consent "Allow Bixby and text on Galaxy
# devices", set on its own screen (tools/messages-api.sh, phone unlocked), which for a user stays their
# own gesture.
#   tools/install.sh --permissions   the module's access step again, at once (setup.sh access), then
#                                    the consent and Google Messages restarted with the bridge.
set -euo pipefail
cd "$(dirname "$0")/.."

# docs/DEVICE.md. Another build means an OTA: re-check DEVICE.md before going further.
FINGERPRINT=Xelex/Xelex10_Ultra/Xelex10_Ultra:14/20240427/UP1v:user/release-keys
sh() { adb shell "$@" | tr -d '\r'; }
MESSAGES=dev.bold7.messages
MODULE=/data/adb/modules/bold7
. tools/messages-api.sh
# The main checkout's build/state, also from a worktree (tools/sons.sh does the same).
STATE=$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)/build/state
installed() { [[ $(sh "pm path $1" || true) == package:* ]]; }

adb get-state >/dev/null
[ "$(sh getprop ro.build.fingerprint)" = "$FINGERPRINT" ] ||
  { echo "Unexpected build fingerprint: re-check docs/DEVICE.md after the OTA." >&2; exit 1; }
sh "su -c 'ksud -V'" | grep -q ksud || { echo "KernelSU (ksud) not reachable as root." >&2; exit 1; }
# After a reboot: the module's access step at once, then the consent; nothing built or installed.
if [ "${1:-}" = --permissions ]; then
  rc=0
  sh "su -c 'sh $MODULE/setup.sh access $MODULE'" || rc=1
  if installed $MESSAGES; then
    set_consent true || rc=1
    restart_messages
  fi
  exit $rc
fi

mkdir -p "$STATE"
if ! sh "su -c 'ls /data/adb/modules'" | grep -qx bold7 && [ ! -e "$STATE/before-install.txt" ]; then
  tools/state.sh > "$STATE/before-install.txt"
  echo "Reference state saved: $STATE/before-install.txt"
fi

./gradlew -q :system:module:moduleZip
trap 'adb shell rm -f /data/local/tmp/bold7-module.zip' EXIT
adb push system/module/build/dist/bold7-module.zip /data/local/tmp/bold7-module.zip >/dev/null
# customize.sh's refusal makes ksud exit non-zero: nothing installed, the script stops (set -e).
sh "su -c 'ksud module install /data/local/tmp/bold7-module.zip'"

# Messages was installed by customize.sh just now: Google Messages' consent, for development only.
rc=0
if installed $MESSAGES; then set_consent true || rc=1; fi
echo "Installed. Reboot: at the first unlock the module sets Bold7 as home, Phone and input method,"
echo "the sounds and settings of OS 7 and the apps' access (bold7.log of the module). Then"
echo "tools/install.sh --permissions restarts Google Messages with the bridge."
exit $rc
