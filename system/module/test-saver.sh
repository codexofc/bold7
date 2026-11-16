#!/usr/bin/env bash
# Runs the module's battery saver follower (saver.sh) with the phone's own sh, in a scratch folder
# of /data/local/tmp, against fakes: resetprop plays a script of property changes, settings prints
# a file, log (the logcat writer) does nothing, energy.sh only records the mode it is given.
# No property, setting or cgroup is touched, nothing reaches the phone's logcat. Exits non-zero on failure.
#   system/module/test-saver.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-saver-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/saver.sh $T/ >/dev/null
# resetprop NAME prints the nonce; resetprop -w runs the next line of steps (a shell command that
# changes the nonce or low_power), or fails once there is none, which ends saver.sh. Like the real
# one, it returns at once when the value given ($3) is no longer the property's: a saver.sh that
# does not pass its last nonce would spin, so more than 20 waits fail.
adb shell "cat > $T/resetprop" <<EOF
if [ "\$1" != -w ]; then cat $T/nonce; exit; fi
c=\$(cat $T/waits 2>/dev/null || echo 0); echo \$((c + 1)) > $T/waits; [ \$c -ge 20 ] && exit 1
[ "\$3" != "\$(cat $T/nonce)" ] && exit 0
l=\$(head -n 1 $T/steps); [ -n "\$l" ] || exit 1
sed -i 1d $T/steps; eval "\$l"
EOF
adb shell "echo 'cat $T/low_power' > $T/settings; echo 'echo \"\$3\" >> \"\$1/calls\"; [ ! -e \"\$1/fail\" ]' > $T/energy.sh; echo 'exit 0' > $T/log; chmod 755 $T/resetprop $T/settings $T/log"
# $1: eco flag (eco), a failing energy.sh (fail) or nothing; $2: low_power at start; then the
# steps, one per argument (the nonce starts at n1). Prints the exit code and the modes energy.sh was called with.
run() {
  local flag=$1 lp=$2; shift 2
  printf '%s\n' "$@" | adb shell "rm -f $T/eco $T/waits $T/calls $T/disable $T/remove $T/fail $T/saver.pid; cat > $T/steps; echo n1 > $T/nonce; echo '$lp' > $T/low_power; [ '$flag' = eco ] && touch $T/eco; [ '$flag' = fail ] && touch $T/fail; PATH=$T:\$PATH RESETPROP=$T/resetprop SETTINGS=$T/settings sh $T/saver.sh $T $T/cpuctl >/dev/null 2>&1; echo \$?; cat $T/calls 2>/dev/null || true" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }
# Step helpers: the nonce moves to $1, low_power becomes $2.
st() { echo "echo $1 > $T/nonce; echo $2 > $T/low_power${3:+; $3}"; }

expect "boot state kept: nothing called"          "$(run '' 0)" "1 "
expect "saver on at boot wins over perf"          "$(run '' 1)" "1 eco "
expect "saver off at boot wins over eco"          "$(run eco 0)" "1 perf "
expect "unset low_power counts as off"            "$(run eco null)" "1 perf "
expect "tile on then off"                         "$(run '' 0 "$(st n2 1)" "$(st n3 0)")" "1 eco perf "
expect "policy change alone: nothing called"      "$(run '' 0 "$(st n2 0)" "$(st n3 0)")" "1 "
expect "unreadable low_power: floor kept"         "$(run eco 1 "$(st n2 'Failure')" "$(st n3 1)")" "1 "
expect "unreadable, then a real change"           "$(run '' 0 "$(st n2 'Failure')" "$(st n3 1)")" "1 eco "
expect "woken without a change: stops"            "$(run '' 0 "$(st n1 1)" "$(st n2 1)")" "1 "
expect "failed write is tried again"              "$(run fail 1 "$(st n2 1 "rm $T/fail")")" "1 eco eco "
expect "module disabled: stops at next change"    "$(run '' 0 "$(st n2 1 "touch $T/disable")")" "0 "
expect "module removed: stops at next change"     "$(run '' 0 "$(st n2 1 "touch $T/remove")")" "0 "
expect "another instance: this one stops"         "$(run '' 0 "$(st n2 1 "echo 1 > $T/saver.pid")")" "0 "
expect "no module folder: nothing done"           "$(adb shell "PATH=$T:\$PATH RESETPROP=$T/resetprop SETTINGS=$T/settings sh $T/saver.sh $T/none $T/cpuctl >/dev/null 2>&1; echo \$?; [ -e $T/none ] && echo created || true" | tr -d '\r' | tr '\n' ' ')" "2 "
expect "each stop in the module log"              "$(adb shell "grep -c 'saver:' $T/bold7.log" | tr -d '\r')" "14"
adb shell "rm -rf $T"
