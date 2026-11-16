#!/usr/bin/env bash
# Runs the module's SystemUI crash-loop guard with the phone's own sh, in a scratch folder of
# /data/local/tmp, on made-up event lines: no module, no reboot (the guard's command is echo),
# nothing else touched. The last case feeds it a real am_proc_died line of this phone's logcat,
# renamed to SystemUI, to check the format it parses. Exits non-zero on failure.
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-sysui-guard-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/sysui-guard.sh $T/ >/dev/null
IN=$(mktemp)
trap 'rm -f "$IN"' EXIT
S=com.android.systemui
# Event lines as `logcat -b events -v monotonic` prints them: "<seconds> uid pid tid I tag: [...]".
lines() { for x in "$@"; do printf '%12s  1000  1234  1300 I am_proc_died: [0,4242,%s,-800,0]\n' "${x%%:*}.123" "${x#*:}"; done; }
# Feeds the lines on stdin to the guard; prints what it ran and whether it disabled the module.
run() {
  cat > "$IN"
  adb push "$IN" $T/in.txt >/dev/null
  adb shell "rm -f $T/disable; sh $T/sysui-guard.sh $T 'echo rebooted' < $T/in.txt; [ -e $T/disable ] && echo disabled || true" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }

expect "two deaths in 60 s: nothing"            "$(lines 10:$S 20:$S | run)" ""
expect "three deaths in 60 s: disable, reboot"  "$(lines 10:$S 20:$S 30:$S | run)" "rebooted disabled "
expect "exactly 60 s still counts"              "$(lines 100:$S 130:$S 160:$S | run)" "rebooted disabled "
expect "three deaths over 90 s: nothing"        "$(lines 10:$S 50:$S 100:$S | run)" ""
expect "the window slides"                      "$(lines 10:$S 50:$S 100:$S 105:$S | run)" "rebooted disabled "
expect "other processes do not count" "$( { lines 10:$S:screenshot 11:com.android.settings 12:$S 13:$S
  echo '--------- beginning of events'
  echo '   14.000  1000  1234  1300 I am_proc_start: [0,1,10000,com.android.systemui,service,x]'; } | run)" ""

real=$(adb shell "logcat -b events -v monotonic -d | grep -m1 'am_proc_died: \['" | tr -d '\r')
if [ -z "$real" ]; then
  echo "skip real logcat line: no am_proc_died since boot (open and close a few apps, then rerun)"
else
  echo "real line: $real"
  renamed=$(printf '%s\n' "$real" | sed -E "s/(am_proc_died: \[[0-9]+,[0-9]+,)[^],]*/\1$S/")
  expect "real logcat format is parsed"        "$(printf '%s\n%s\n%s\n' "$renamed" "$renamed" "$renamed" | run)" "rebooted disabled "
fi
adb shell "rm -rf $T"
