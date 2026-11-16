#!/usr/bin/env bash
# Plays bold7-trackpad's start and its ways back on the phone, without the module, in a scratch
# folder of /data/local/tmp:
#   1. trackpad-start.sh with the phone's sh against fakes (getprop reads a file, resetprop -w plays
#      the next SIM state, the program only records that it started): it starts at once on a ready
#      SIM, waits through PIN_REQUIRED, PUK_REQUIRED, NOT_READY, UNKNOWN and an empty state, and
#      gives up after MAX_WAIT. Touches nothing.
#   2. the program itself, as root, on a stand-in keyboard (`bold7-trackpad --fake`, a uinput device
#      named bold7-test-kbd with the keys and axes of Q25_keyboard), a stand-in touchscreen
#      (`bold7-trackpad --fake-touch`, bold7-test-ts, the axes and property of s2716b_ts) and a mode
#      file of its own in the scratch folder: it takes both devices, each with its twin, and writes
#      0; a second instance is refused (lock); kill -9 of the worker, then of the parent, then
#      bold7_trackpad_off = 1 each give both back (twins gone) with the mode file at 1;
#      bold7_trackpad_palm_band at 0 leaves the touchscreen alone; off still set,
#      it refuses to start.
#
#   system/module/test-trackpad.sh      after ./gradlew :system:module:moduleZip (it pushes the binary)
#
# Part 2 CHANGES THE PHONE'S STATE for its run: four throwaway input devices (the stand-ins and the
# program's twins, named bold7-test-kbd and bold7-test-ts, which Android sees come and go; the
# touchscreens send no event) and the global settings bold7_trackpad_off and
# bold7_trackpad_palm_band, deleted at the end (trap): ask the phone's owner first. It refuses while a
# bold7-trackpad runs (the module's, which the setting would stop) or while
# bold7_trackpad_palm_band is set (the user's). The real keyboard, the real touchscreen (s2716b_ts),
# /proc/q20_switch_key_mouse and the module are never touched. Exits non-zero on the first failure.
set -euo pipefail
cd "$(dirname "$0")"
BIN=build/trackpad/bin/bold7-trackpad
[ -x "$BIN" ] || { echo "$BIN missing: ./gradlew :system:module:moduleZip first" >&2; exit 2; }
T=/data/local/tmp/bold7-trackpad-test
NAME=bold7-test-kbd
TNAME=bold7-test-ts
sh_() { adb shell "$1" | tr -d '\r'; }
su_() { adb shell "su -c \"$1\"" | tr -d '\r'; }
cleanup() {
  su_ "pkill -KILL -x bold7-trackpad; pkill -KILL -x fake-kbd; pkill -KILL -x fake-ts; settings delete global bold7_trackpad_off; settings delete global bold7_trackpad_palm_band; rm -rf $T" >/dev/null 2>&1 || true
}
fail() { echo "FAIL $1" >&2; su_ "cat $T/d/bold7.log" >&2 || true; cleanup; exit 1; }
expect() { [ "$2" = "$3" ] && echo "ok   $1" || fail "$1: got '$2', expected '$3'"; }

# ---- 1. the start, against fakes ----
adb shell "rm -rf $T && mkdir -p $T"
adb push src/trackpad-start.sh $T/ >/dev/null
# getprop prints the state file; resetprop -w plays the next line of steps (a new state), or fails
# when there is none (then the wait ends as a timeout would); the program records its start.
sh_ "echo 'cat $T/state' > $T/getprop; echo 'exit 0' > $T/log; echo 'echo started > $T/started' > $T/prog"
sh_ "printf '%s\n' '[ \"\$1\" = -w ] || exit 1' 'l=\$(head -n 1 $T/steps); [ -n \"\$l\" ] || exit 1' 'sed -i 1d $T/steps; echo \"\$l\" > $T/state' > $T/resetprop"
sh_ "chmod 755 $T/getprop $T/resetprop $T/log $T/prog"
# $1: MAX_WAIT; $2: the state at start; then the states the property takes. Prints the exit code
# and whether the program started.
run() {
  local max=$1 first=$2; shift 2
  printf '%s\n' "$@" | adb shell "rm -f $T/started $T/bold7.log; cat > $T/steps; echo '$first' > $T/state; PATH=$T:\$PATH GETPROP=$T/getprop RESETPROP=$T/resetprop BIN=$T/prog MAX_WAIT=$max sh $T/trackpad-start.sh $T; echo \$?; cat $T/started 2>/dev/null || echo waited" | tr -d '\r' | tr '\n' ' '
}
expect "ready SIM: started at once"              "$(run 5 LOADED)" "0 started "
expect "two slots, one absent: started"          "$(run 5 LOADED,ABSENT)" "0 started "
expect "no SIM: started"                         "$(run 5 ABSENT)" "0 started "
expect "PIN typed: started after"                "$(run 5 PIN_REQUIRED READY)" "0 started "
expect "PUK, then not ready, then loaded"        "$(run 5 PUK_REQUIRED,ABSENT NOT_READY,ABSENT LOADED,ABSENT)" "0 started "
expect "unknown, then loaded"                    "$(run 5 UNKNOWN LOADED)" "0 started "
expect "not set yet, then loaded"                "$(run 5 '' LOADED)" "0 started "
expect "PIN never typed: given up"               "$(run 2 PIN_REQUIRED)" "0 waited "
expect "the giving up in the log"                "$(sh_ "grep -c 'not started for this boot' $T/bold7.log")" "1"
expect "no module folder: nothing done"          "$(sh_ "BIN=$T/prog sh $T/trackpad-start.sh $T/none; echo \$?")" "2"

# ---- 2. the program on a stand-in keyboard (root, throwaway devices) ----
[ -z "$(su_ "pidof bold7-trackpad || true")" ] || fail "a bold7-trackpad runs (the module's?): stop it first (tools/trackpad.sh retour)"
[ "$(sh_ "settings get global bold7_trackpad_palm_band")" = null ] || fail "bold7_trackpad_palm_band is set (the user's?): the test would delete it"
trap cleanup EXIT
adb push "$BIN" $T/bold7-trackpad >/dev/null
su_ "mkdir -p $T/d; cp $T/bold7-trackpad $T/fake-kbd; cp $T/bold7-trackpad $T/fake-ts; chmod 755 $T/bold7-trackpad $T/fake-kbd $T/fake-ts; echo 1 > $T/mode"
su_ "setsid $T/fake-kbd --fake $NAME </dev/null >/dev/null 2>&1 &"
su_ "setsid $T/fake-ts --fake-touch $TNAME </dev/null >/dev/null 2>&1 &"
sleep 1
# $1: the device's name, the stand-in keyboard's by default.
devices() { su_ "grep -c 'Name=\\\"${1:-$NAME}\\\"' /proc/bus/input/devices || true"; }
expect "stand-in keyboard there"                 "$(devices)" "1"
expect "stand-in touchscreen there"              "$(devices $TNAME)" "1"
start() { su_ "setsid $T/bold7-trackpad $T/d $NAME $T/mode $TNAME </dev/null >/dev/null 2>&1 &"; }
# Waits up to 10 s for a line of the log.
wait_for() {
  for _ in $(seq 20); do [ "$(su_ "grep -c '$1' $T/d/bold7.log 2>/dev/null || true")" -ge "${2:-1}" ] && return 0; sleep 0.5; done
  fail "no '$1' in the log"
}
gone() {
  for _ in $(seq 20); do [ -z "$(su_ "pidof bold7-trackpad || true")" ] && return 0; sleep 0.5; done
  fail "bold7-trackpad still running"
}
mode() { su_ "head -c 1 $T/mode"; }
parent() { su_ "grep 'worker [0-9]* started' $T/d/bold7.log | tail -n 1" | sed -n 's/^trackpad\[\([0-9]*\)\].*/\1/p'; }
worker() { su_ "grep 'worker [0-9]* started' $T/d/bold7.log | tail -n 1" | sed -n 's/.*worker \([0-9]*\) started.*/\1/p'; }

start
wait_for "trackpad taken" 1
expect "taken: mode 0"                           "$(mode)" "0"
expect "taken: the stand-in and its twin"        "$(devices)" "2"
expect "taken: the touchscreen too"              "$(su_ "grep -c 'touchscreen taken, touches ignored' $T/d/bold7.log")" "1"
expect "taken: the touchscreen's twin"           "$(devices $TNAME)" "2"
start
wait_for "another bold7-trackpad holds" 1
expect "second instance refused"                 "$(su_ "pidof bold7-trackpad | wc -w")" "2"

su_ "kill -KILL $(worker)"
gone
wait_for "worker killed (signal 9)" 1
expect "worker killed: mode back to 1"           "$(mode)" "1"
expect "worker killed: the twin gone"            "$(devices)" "1"
expect "worker killed: the touchscreen's twin gone" "$(devices $TNAME)" "1"

start
wait_for "trackpad taken" 2
# Taken again: the worker's death let the touchscreen go (a grab still held would be refused).
wait_for "touchscreen taken" 2
su_ "kill -KILL $(parent)"
gone
wait_for "parent gone" 1
expect "parent killed: mode back to 1"           "$(mode)" "1"
expect "parent killed: the twin gone"            "$(devices)" "1"
expect "parent killed: the touchscreen's twin gone" "$(devices $TNAME)" "1"

start
wait_for "trackpad taken" 3
wait_for "touchscreen taken" 3
sh_ "settings put global bold7_trackpad_off 1"
gone
wait_for "bold7_trackpad_off is 1: stopping" 1
expect "off: mode back to 1"                     "$(mode)" "1"
expect "off: the twin gone"                      "$(devices)" "1"
expect "off: the touchscreen's twin gone"        "$(devices $TNAME)" "1"

# Touches at the bottom not to be ignored: the touchscreen is not even taken.
sh_ "settings delete global bold7_trackpad_off; settings put global bold7_trackpad_palm_band 0"
start
wait_for "trackpad taken" 4
expect "palm band 0: touchscreen left alone"     "$(su_ "grep -c '_delay is 0: $TNAME left to Android' $T/d/bold7.log")" "1"
expect "palm band 0: no touchscreen twin"        "$(devices $TNAME)" "1"
sh_ "settings delete global bold7_trackpad_palm_band; settings put global bold7_trackpad_off 1"
gone
wait_for "bold7_trackpad_off is 1: stopping" 2
expect "palm band 0, off: mode back to 1"        "$(mode)" "1"
start
wait_for "bold7_trackpad_off is 1: not started" 1
expect "off: nothing started"                    "$(su_ "pidof bold7-trackpad || true")" ""
echo "all passed"
