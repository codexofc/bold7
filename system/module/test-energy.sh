#!/usr/bin/env bash
# Runs the module's CPU floor step (energy.sh) with the phone's own sh, in a scratch folder of
# /data/local/tmp, against a made-up cpuctl tree: no cgroup is touched, nothing else either.
# Exits non-zero on failure.
#   system/module/test-energy.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-energy-test
F=$T/cpuctl/top-app/cpu.uclamp.min
adb shell "rm -rf $T && mkdir -p $T/cpuctl/top-app"
adb push src/energy.sh $T/ >/dev/null
# Sets the floor file to $1 (or removes it if "none") and the eco flag ($2: eco or nothing), runs
# energy.sh with mode $3 and WAIT=$5 (default 2); $4 runs first in the background (init's reset).
# Once both are done, prints the exit code, the floor file, and whether the eco flag is there.
run() {
  adb shell "rm -f $T/eco; [ '$1' = none ] && rm -f $F || echo '$1' > $F; [ '$2' = eco ] && touch $T/eco; ${4:-true} & WAIT=${5:-2} sh $T/energy.sh $T $T/cpuctl $3 >/dev/null 2>&1; r=\$?; wait; echo \$r; cat $F 2>/dev/null || echo absent; [ -e $T/eco ] && echo eco || true" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }

expect "boot sets the floor"                  "$(run 0.00 '' boot)" "0 20 "
expect "boot in eco mode leaves 0"            "$(run 0.00 eco boot)" "0 0 eco "
expect "boot waits for init's reset"          "$(run 100.00 '' boot "(sleep 1; echo 0.00 > $F)" 4)" "0 20 "
expect "init never resets: left to it"        "$(run 100.00 '' boot true 1)" "1 100.00 "
expect "another writer's value: left to it"   "$(run 35.00 '' boot true 1)" "1 35.00 "
expect "a bad WAIT still waits for the reset" "$(run 100.00 '' boot "(sleep 1; echo 0.00 > $F)" x1)" "0 20 "
expect "eco by hand is kept"                  "$(run 20.00 '' eco)" "0 0 eco "
expect "perf by hand drops the eco flag"      "$(run 0.00 eco perf)" "0 20 "
expect "no uclamp: nothing done, no file"     "$(run none '' boot)" "0 absent "
expect "unknown mode changes nothing"         "$(run 0.00 '' fast)" "2 0.00 "
expect "no module folder: nothing done"       "$(adb shell "echo 0.00 > $F; sh $T/energy.sh $T/none $T/cpuctl boot >/dev/null 2>&1; echo \$?; cat $F; [ -e $T/none ] && echo created || true" | tr -d '\r' | tr '\n' ' ')" "2 0.00 "
expect "status prints floor and choice"       "$(adb shell "echo 20.00 > $F; rm -f $T/eco; sh $T/energy.sh $T $T/cpuctl" | tr -d '\r')" "top-app uclamp.min 20.00, kept: perf"
expect "each decision in the module log"      "$(adb shell "grep -c 'energy:' $T/bold7.log" | tr -d '\r')" "9"
adb shell "rm -rf $T"
