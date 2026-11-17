#!/usr/bin/env bash
# Plays bold7-led on the phone, without the module, as root, on stand-in LEDs: a folder of
# /data/local/tmp with red, green and blue, each a brightness and a trigger file, and a global
# setting of its own (bold7_led_test). It keeps what it found and turns the triggers off; it flashes
# the colour written to the setting (red, amber as red and green together, off), changes at each
# write; a second instance is refused (lock); SIGTERM writes back the triggers and brightness it
# found.
#
#   system/module/test-led.sh      after ./gradlew :system:module:moduleZip (it pushes the binary)
#
# CHANGES THE PHONE'S STATE for its run: the global setting bold7_led_test, deleted at the end
# (trap). The real LEDs (/sys/class/leds), bold7_led and the module are never touched. Run it under
# the phone's lock (/tmp/bold7-phone.lock) when the phone is shared: the scratch folder is fixed.
# Exits non-zero on the first failure.
set -euo pipefail
cd "$(dirname "$0")"
BIN=build/led/bin/bold7-led
[ -x "$BIN" ] || { echo "$BIN missing: ./gradlew :system:module:moduleZip first" >&2; exit 2; }
T=/data/local/tmp/bold7-led-test
S=bold7_led_test
su_() { adb shell "su -c \"$1\"" | tr -d '\r'; }
cleanup() { su_ "pkill -TERM -x bold7-led-test; settings delete global $S; rm -rf $T" >/dev/null 2>&1 || true; }
fail() { echo "FAIL $1" >&2; su_ "cat $T/d/bold7.log" >&2 || true; cleanup; exit 1; }
expect() { [ "$2" = "$3" ] && echo "ok   $1" || fail "$1: got '$2', expected '$3'"; }
trap cleanup EXIT

adb shell "rm -rf $T && mkdir -p $T/d" >/dev/null
adb push "$BIN" $T/bold7-led-test >/dev/null
# Red was left blinking by a timer, green lit, blue off and untouched.
su_ "chmod 755 $T/bold7-led-test; for c in red green blue; do mkdir -p $T/leds/\\\$c; done; \
echo 'none [timer] battery-full' > $T/leds/red/trigger; echo 255 > $T/leds/red/brightness; \
echo '[none] timer' > $T/leds/green/trigger; echo 255 > $T/leds/green/brightness; \
echo '[none] timer' > $T/leds/blue/trigger; echo 0 > $T/leds/blue/brightness; \
settings put global $S off"
has() { [[ "$1" == *"$2"* ]] && echo yes || echo no; }
start() { su_ "setsid $T/bold7-led-test $T/d $T/leds $S </dev/null >/dev/null 2>&1 &"; }
# Waits up to 10 s for [pattern] in the log to be seen [n] times.
await() { for _ in $(seq 50); do [ "$(su_ "grep -c '$1' $T/d/bold7.log || true")" -ge "${2:-1}" ] && return 0; sleep 0.2; done; fail "no '$1' in the log"; }
# The three brightness files on one line (bold7-led writes them without a newline).
three="echo \\\$(cat $T/leds/red/brightness) \\\$(cat $T/leds/green/brightness) \\\$(cat $T/leds/blue/brightness)"
leds() { su_ "$three"; }
# The brightness of the three, sampled every 50 ms for 4 s: the set of states seen.
seen() { su_ "i=0; while [ \\\$i -lt 80 ]; do $three; sleep 0.05; i=\\\$((i+1)); done" | sort -u | tr '\n' '|'; }

start
await "started"
expect "triggers kept, as found"               "$(su_ "grep -o 'red timer 255, green none 255, blue none 0' $T/d/bold7.log")" "red timer 255, green none 255, blue none 0"
expect "red's timer turned off"                "$(su_ "cat $T/leds/red/trigger")" "none"
expect "off: all dark"                         "$(leds)" "0 0 0"
start
await "another bold7-led holds"
expect "second instance refused, first alive"  "$(su_ "pidof bold7-led-test | wc -w")" "1"

su_ "settings put global $S red"
await "$S: red"
expect "red flashes, alone"                    "$(seen)" "0 0 0|255 0 0|"
su_ "settings put global $S amber"
await "$S: amber"
# The files are written one after the other: a sample may fall between red's write and green's.
a=$(seen)
expect "amber: red and green lit together"     "$(has "$a" "255 255 0|")" "yes"
expect "amber: never blue"                     "$(has "$a" " 255|")" "no"
su_ "settings put global $S off"
await "$S: off" 2
expect "off: all dark again"                   "$(seen)" "0 0 0|"
su_ "settings put global $S purple"
sleep 1
expect "an unknown colour is off"              "$(seen)" "0 0 0|"

su_ "settings put global $S red"
await "$S: red" 2
su_ "pkill -TERM -x bold7-led-test"
await "given back"
expect "SIGTERM: brightness back"              "$(leds)" "255 255 0"
expect "SIGTERM: red's trigger back"           "$(su_ "cat $T/leds/red/trigger")" "timer"
expect "no instance left"                      "$(su_ "pidof bold7-led-test | wc -w")" "0"
echo "all passed"
