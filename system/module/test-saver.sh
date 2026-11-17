#!/usr/bin/env bash
# Runs the module's battery saver follower (saver.sh) with the phone's own sh, in a scratch folder
# of /data/local/tmp, against fakes: logcat plays a script of saver changes, settings prints a file,
# log (the logcat writer) does nothing, energy.sh only records the mode it is given.
# No property, setting or cgroup is touched, nothing reaches the phone's logcat. Exits non-zero on failure.
#   system/module/test-saver.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-saver-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/saver.sh $T/ >/dev/null
# logcat runs each line of steps (a shell command that changes low_power, or touches a flag), then
# prints a battery_saver_mode line as system_server's event would show, and waits (2 s at most) for
# saver.sh to read low_power again before the next step; it ends after the last one, as a logcat
# that dies. Asked anything but the saver's events, it fails. settings counts its reads.
adb shell "cat > $T/logcat" <<EOF
[ "\$*" = "-b events -v brief -s battery_saver_mode" ] || exit 1
while IFS= read -r l <&3; do
  r=\$(cat $T/reads); eval "\$l"; echo "I/battery_saver_mode( 1000): [0,0,1,0,1,full,0]"
  n=0; while [ "\$(cat $T/reads)" = "\$r" ] && [ \$n -lt 20 ]; do sleep 0.1; n=\$((n + 1)); done
done 3< $T/steps
EOF
adb shell "cat > $T/settings" <<EOF
echo \$((\$(cat $T/reads) + 1)) > $T/reads; cat $T/low_power
EOF
adb shell "echo 'echo \"\$3\" >> \"\$1/calls\"; [ ! -e \"\$1/fail\" ]' > $T/energy.sh; echo 'exit 0' > $T/log; chmod 755 $T/logcat $T/settings $T/log"
# $1: eco flag (eco), a failing energy.sh (fail) or nothing; $2: low_power at start; then the
# steps, one per argument. Prints the exit code and the modes energy.sh was called with.
run() {
  local flag=$1 lp=$2; shift 2
  printf '%s\n' "$@" | adb shell "rm -f $T/eco $T/calls $T/disable $T/remove $T/fail $T/saver.pid; cat > $T/steps; echo 0 > $T/reads; echo '$lp' > $T/low_power; [ '$flag' = eco ] && touch $T/eco; [ '$flag' = fail ] && touch $T/fail; PATH=$T:\$PATH LOGCAT=$T/logcat SETTINGS=$T/settings sh $T/saver.sh $T $T/cpuctl >/dev/null 2>&1; echo \$?; cat $T/calls 2>/dev/null || true" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }
# Step helper: low_power becomes $1, then $2 runs.
st() { echo "echo $1 > $T/low_power${2:+; $2}"; }

expect "boot state kept: nothing called"          "$(run '' 0)" "1 "
expect "saver on at boot wins over perf"          "$(run '' 1)" "1 eco "
expect "saver off at boot wins over eco"          "$(run eco 0)" "1 perf "
expect "unset low_power counts as off"            "$(run eco null)" "1 perf "
expect "tile on then off"                         "$(run '' 0 "$(st 1)" "$(st 0)")" "1 eco perf "
expect "policy change alone: nothing called"      "$(run '' 0 "$(st 0)" "$(st 0)")" "1 "
expect "unreadable low_power: floor kept"         "$(run eco 1 "$(st 'Failure')" "$(st 1)")" "1 "
expect "unreadable, then a real change"           "$(run '' 0 "$(st 'Failure')" "$(st 1)")" "1 eco "
expect "failed write is tried again"              "$(run fail 1 "$(st 1 "rm $T/fail")")" "1 eco eco "
expect "module disabled: stops at next change"    "$(run '' 0 "$(st 1 "touch $T/disable")")" "0 "
expect "module removed: stops at next change"     "$(run '' 0 "$(st 1 "touch $T/remove")")" "0 "
expect "another instance: this one stops"         "$(run '' 0 "$(st 1 "echo 1 > $T/saver.pid")")" "0 "
expect "no module folder: nothing done"           "$(adb shell "PATH=$T:\$PATH LOGCAT=$T/logcat SETTINGS=$T/settings sh $T/saver.sh $T/none $T/cpuctl >/dev/null 2>&1; echo \$?; [ -e $T/none ] && echo created || true" | tr -d '\r' | tr '\n' ' ')" "2 "
expect "each stop in the module log"              "$(adb shell "grep -c 'saver:' $T/bold7.log" | tr -d '\r')" "13"
expect "a logcat that ends is said"               "$(adb shell "grep -c 'logcat of battery_saver_mode ended' $T/bold7.log" | tr -d '\r')" "9"
adb shell "rm -rf $T"
