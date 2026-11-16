#!/usr/bin/env bash
# The screenshots of the public README, filled with the showcase's made-up data (core/showcase).
#
#   tools/showcase-capture.sh [screen…]
#
# Screens, by the names public/README.md expects under media/screens/: home notifications drawer
# search messages thread phone calendar clock options lock menu connections (all by default). Each is captured at
# the Q25's native 720x720 into $OUT/media/screens/<screen>.png (OUT: build/showcase of the main
# checkout, also from a worktree).
#
# CHANGES THE PHONE'S STATE, for the run only: sets the global setting bold7_showcase (the apps,
# the home, its search, its banner, the status bars and the lock screen show the made-up data, never
# the user's), force-stops each app it opens, and for "lock" sleeps the screen and unlocks it again
# with `wm dismiss-keyguard` (no code is ever typed). On exit, even interrupted (EXIT, INT, TERM, HUP): the setting deleted, the
# apps stopped, the home in front.
#
# Stops, touching nothing, unless the phone is awake, unlocked and on Bold7's home; and before each
# key, unless the window it is meant for is in front (other people may use the phone meanwhile).
set -euo pipefail
cd "$(dirname "$0")/.."
MAIN=$(cd "$(dirname "$(git rev-parse --git-common-dir)")" && pwd)
OUT=${OUT:-$MAIN/build/showcase}/media/screens
ALL="home notifications drawer search messages thread phone calendar clock options lock menu connections"
screens=${*:-$ALL}
for s in $screens; do [[ " $ALL " == *" $s "* ]] || { echo "Unknown screen: $s (one of: $ALL)" >&2; exit 2; }; done

sh() { adb shell "$@" | tr -d '\r'; }
LAUNCHER=dev.bold7.launcher/dev.bold7.launcher.LauncherActivity
# The last mCurrentFocus of the dump is the live one: an earlier one can be a dialog's stale record.
focused() { sh dumpsys window | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | tail -1; }

# Waits up to 5 s for [window] (a component or part of it) in front, else stops.
expect() {
  local w
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    w=$(focused)
    [[ $w == *"$1"* ]] && return 0
    sleep 0.5
  done
  echo "Expected $1 in front, found ${w:-nothing}: stopped." >&2
  exit 1
}

# One key for [window], once it is in front, then a second's pause.
key() {
  expect "$1"
  sh input keyevent "$2"
  sleep 1
}

shoot() {
  mkdir -p "$OUT"
  sleep "${2:-1.5}"
  adb exec-out screencap -p > "$OUT/$1.png"
  local size
  size=$(sips -g pixelWidth -g pixelHeight "$OUT/$1.png" | awk '/pixel/ {printf "%s ", $2}')
  [ "$size" = "720 720 " ] || { echo "$1.png is $size, not 720x720: stopped." >&2; exit 1; }
  echo "$OUT/$1.png"
}

# An app, started fresh so that it reads the setting, and in front.
app() {
  sh am force-stop "${2%%/*}"
  opened+=("${2%%/*}")
  sh am start -W -n "$2" "${@:3}" >/dev/null
  expect "${2#*/}"
}

home() {
  sh input keyevent KEYCODE_HOME
  sleep 1
  expect $LAUNCHER
}

opened=()
restore() {
  adb shell settings delete global bold7_showcase >/dev/null || true
  adb shell settings delete global bold7_showcase_event >/dev/null || true
  for p in "${opened[@]+"${opened[@]}"}"; do adb shell am force-stop "$p" || true; done
  adb shell input keyevent KEYCODE_HOME || true
}

adb get-state >/dev/null
[[ $(sh dumpsys power) == *mWakefulness=Awake* ]] || { echo "The screen is off: wake and unlock the phone, then run again." >&2; exit 1; }
[[ $(sh dumpsys window) != *isKeyguardShowing=true* ]] || { echo "The phone is locked: unlock it, then run again." >&2; exit 1; }
[ "$(focused)" = $LAUNCHER ] || { echo "Bold7's home is not in front ($(focused)): another app is in use, stopped." >&2; exit 1; }

trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
sh settings put global bold7_showcase 1
# The launcher reads its inbox again when the setting changes (HubWatch), SystemUI its counters and
# the carrier's name.
sleep 2

# The lock screen last: it sleeps the phone, and another session may lock it again meanwhile.
order=" $screens "
[[ $order != *" lock "* ]] || order="${order/ lock / } lock"
for s in $order; do
  case $s in
    home) home; shoot home ;;
    # The tray raised by a swipe up from its row (tools/perf.sh, TRAY).
    drawer) home; expect $LAUNCHER; sh input swipe 360 670 360 300 150; shoot drawer; home ;;
    # Typed on the home, which opens the search (launch by typing).
    search) home; sh input text o; sleep 1; expect Launcher; sh input text l; sleep 1; expect Launcher; sh input text i; shoot search 2; key Launcher KEYCODE_ESCAPE; home ;;
    # A tap on the indicator row opens the list under the banner.
    notifications) home; sh input tap 360 117; shoot notifications 2; home ;;
    messages) app messages dev.bold7.messages/.MessagesActivity; shoot messages 2 ;;
    # The focus starts on the date's header: down to the newest row (Olivia Bennett's), then opened.
    thread)
      app thread dev.bold7.messages/.MessagesActivity
      key MessagesActivity KEYCODE_DPAD_DOWN
      key MessagesActivity KEYCODE_DPAD_CENTER
      expect ConversationActivity
      shoot thread 2 ;;
    phone) app phone dev.bold7.phone/.PhoneActivity; shoot phone 2 ;;
    # The Day view opens on the hour of now: the focus taken up (or down) to 08, so that the
    # morning's appointments show whatever the time of the capture.
    calendar)
      app calendar dev.bold7.calendar/.CalendarActivity
      h=$(sh date +%H)
      n=$((10#$h - 8))
      dir=KEYCODE_DPAD_UP
      if [ $n -lt 0 ]; then n=$((-n)); dir=KEYCODE_DPAD_DOWN; fi
      while [ $n -gt 0 ]; do key CalendarActivity $dir; n=$((n - 1)); done
      shoot calendar 1.5 ;;
    clock) app clock dev.bold7.clock/.ClockActivity; shoot clock 2 ;;
    # In the launcher's process: started, never force-stopped.
    options) sh am start -W -n dev.bold7.launcher/.options.OptionsActivity >/dev/null; expect OptionsActivity; shoot options 2; home ;;
    menu) home; key $LAUNCHER KEYCODE_MENU; shoot menu; key $LAUNCHER KEYCODE_ESCAPE ;;
    connections)
      # A tap on the banner opens it (sim-23).
      home
      sh input tap 360 55
      shoot connections 2
      home ;;
    lock)
      home
      key $LAUNCHER KEYCODE_SLEEP
      sleep 2
      sh input keyevent KEYCODE_WAKEUP
      sleep 2
      [[ $(sh dumpsys window) == *isKeyguardShowing=true* ]] || { echo "No lock screen after waking: stopped." >&2; exit 1; }
      shoot lock 1
      # Lifted without a code, as a lock still in its grace period is; otherwise it stays locked.
      sh wm dismiss-keyguard
      sleep 2
      [[ $(sh dumpsys window) != *isKeyguardShowing=true* ]] || { echo "The phone stays locked: unlock it by hand." >&2; exit 1; }
      expect $LAUNCHER ;;
  esac
done
