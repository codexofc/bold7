#!/usr/bin/env bash
# Runs the module's boot-loop guard with the phone's own sh, in a scratch folder of
# /data/local/tmp: no module, no reboot, nothing else touched. Exits non-zero on failure.
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-guard-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/post-fs-data.sh src/boot-completed.sh src/compile.sh $T/ >/dev/null
# boot-completed.sh also starts energy.sh, then saver.sh: here a stand-in of energy.sh that only
# sleeps 5 s (energy.sh waits for init at boot), so the guard's test touches no cgroup (test-energy.sh tests
# energy.sh) and shows that boot-completed.sh does not wait for it.
adb shell "printf 'exec >/dev/null 2>&1\\nsleep 5\\n' > $T/energy.sh"
# saver.sh, started once it is done, runs until reboot: a stand-in that records its start.
adb shell "printf 'echo started > \"\$1/saver-started\"\\n' > $T/saver.sh"
run() { adb shell "sh $T/$1; cat $T/boot_count; [ -e $T/disable ] && echo disabled || true" | tr -d '\r' | tr '\n' ' '; }
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }

expect "first boot counts one"            "$(run post-fs-data.sh)" "1 "
expect "completed boot resets"            "$(run boot-completed.sh)" "0 "
adb shell "echo garbage > $T/boot_count"
expect "unreadable count starts over"     "$(run post-fs-data.sh)" "1 "
expect "second unfinished boot"           "$(run post-fs-data.sh)" "2 "
expect "third boot trips the guard"       "$(run post-fs-data.sh)" "0 disabled "
expect "energy.sh does not hold boot-completed.sh" "$(adb shell "s=\$(date +%s); sh $T/boot-completed.sh; echo \$((\$(date +%s) - s))" | tr -d '\r' | awk '{ print ($1 < 2) ? "under 2 s" : $1 " s" }')" "under 2 s"
expect "saver.sh starts once energy.sh is done" "$(adb shell "rm -f $T/saver-started; sh $T/boot-completed.sh; [ -e $T/saver-started ] || echo waiting; sleep 6; cat $T/saver-started" | tr -d '\r' | tr '\n' ' ')" "waiting started "
# energy.sh failing (boot-completed.sh joins them with &&): saver.sh must not start.
adb shell "printf 'exit 1\\n' > $T/energy.sh"
expect "saver.sh does not start if energy.sh fails" "$(adb shell "rm -f $T/saver-started; sh $T/boot-completed.sh; sleep 1; [ -e $T/saver-started ] && echo started || echo not started" | tr -d '\r' | tr '\n' ' ')" "not started "
adb shell "rm -rf $T"
