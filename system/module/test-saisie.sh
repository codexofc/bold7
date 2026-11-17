#!/usr/bin/env bash
# Runs the module's selection of Typing after boot (saisie-start.sh) with the phone's own sh, in a
# scratch folder of /data/local/tmp, against fakes: getprop prints a file, resetprop -w plays a
# script of property changes, settings, ime and pm read and write files, log does nothing. No
# property, setting nor input method is touched, nothing reaches the phone's logcat. Exits
# non-zero on failure.
#   system/module/test-saisie.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-saisie-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/saisie-start.sh $T/ >/dev/null
# resetprop -w runs the next line of steps (a shell command that changes ce), or fails once there
# is none. Like the real one, it returns at once when the value given ($3) is no longer the
# property's; more than 20 waits fail.
adb shell "cat > $T/resetprop" <<EOF
c=\$(cat $T/waits 2>/dev/null || echo 0); echo \$((c + 1)) > $T/waits; [ \$c -ge 20 ] && exit 1
[ -n "\$3" ] && [ "\$3" != "\$(cat $T/ce)" ] && exit 0
l=\$(head -n 1 $T/steps); [ -n "\$l" ] || exit 1
sed -i 1d $T/steps; eval "\$l"
EOF
# settings get global bold7_saisie / secure default_input_method; ime enable|set ID (set fails
# while setfail counts down); pm path prints a path when installed exists. Like system_server under
# SELinux, settings fails when one of its file descriptors is a file of the phone (the log, a save),
# and while sfail counts down: "cmd: Failure calling service settings: Failed transaction" on its output.
adb shell "cat > $T/settings" <<EOF
n=\$(cat $T/sfail 2>/dev/null || echo 0)
if [ -f /proc/\$\$/fd/0 ] || [ -f /proc/\$\$/fd/1 ] || [ -f /proc/\$\$/fd/2 ] || [ \$n -gt 0 ]; then
  echo \$((n - 1)) > $T/sfail; echo 'cmd: Failure calling service settings: Failed transaction (2147483646)'; exit 255
fi
case \$3 in bold7_saisie) cat $T/flag ;; default_input_method) cat $T/default ;; esac
EOF
adb shell "cat > $T/ime" <<EOF
echo "\$1" >> $T/calls
[ "\$1" = set ] || exit 0
n=\$(cat $T/setfail); if [ \$n -gt 0 ]; then echo \$((n - 1)) > $T/setfail; exit 1; fi
echo "\$2" > $T/default
EOF
adb shell "echo '[ -e $T/installed ] && echo package:/data/app/base.apk; true' > $T/pm; echo 'exit 0' > $T/log; echo 'cat $T/ce' > $T/getprop; chmod 755 $T/resetprop $T/settings $T/ime $T/pm $T/log $T/getprop"
# $1: ce at start; $2: bold7_saisie; $3: installed or not; $4: ime set failures before it takes;
# then the steps, one per argument. Prints the exit code, the ime calls and the default at the end.
# SFAIL: settings failures before it answers; OUT: where the script's output goes (setup.sh apply
# gives it bold7.log, a file).
SFAIL=0 OUT=/dev/null
run() {
  local ce=$1 flag=$2 inst=$3 fail=$4; shift 4
  printf '%s\n' "$@" | adb shell "rm -f $T/waits $T/calls $T/installed; cat > $T/steps; echo '$ce' > $T/ce; echo '$flag' > $T/flag; echo $fail > $T/setfail; echo $SFAIL > $T/sfail; echo com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME > $T/default; [ '$inst' = yes ] && touch $T/installed; PATH=$T:\$PATH GETPROP=$T/getprop RESETPROP=$T/resetprop SETTINGS=$T/settings IME=$T/ime PM=$T/pm MAX_WAIT=3 TRIES=3 RETRY_SLEEP=0 ASK_TRIES=4 sh $T/saisie-start.sh $T </dev/null >>$OUT 2>&1; echo \$?; cat $T/calls 2>/dev/null || true; cut -d/ -f1 $T/default" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }
B=com.blackberry.keyboard S=dev.bold7.saisie

expect "storage open at start: selected"            "$(run true 1 yes 0)" "0 enable set $S "
expect "storage opens later: selected"              "$(run '' 1 yes 0 "echo false > $T/ce" "echo true > $T/ce")" "0 enable set $S "
expect "switch off: nothing done"                   "$(run true null yes 0)" "0 $B "
expect "switch deleted then 0: nothing done"        "$(run true 0 yes 0)" "0 $B "
expect "not installed: nothing done"                "$(run true 1 no 0)" "0 $B "
expect "storage never opens: given up"              "$(run false 1 yes 0)" "0 $B "
expect "selection taken at the third try"           "$(run true 1 yes 2)" "0 enable set enable set enable set $S "
expect "selection never taken: fails"               "$(run true 1 yes 9)" "1 enable set enable set enable set $B "
OUT=$T/apply.log
expect "output to a file (setup.sh apply): selected" "$(run true 1 yes 0)" "0 enable set $S "
OUT=/dev/null SFAIL=3
expect "settings fails 3 times, then answers"       "$(run true 1 yes 0)" "0 enable set $S "
SFAIL=9
expect "settings never answers: not selected, fails" "$(run true 1 yes 0)" "1 $B "
SFAIL=0
expect "no module folder: nothing done"             "$(adb shell "PATH=$T:\$PATH GETPROP=$T/getprop IME=$T/ime sh $T/saisie-start.sh $T/none >/dev/null 2>&1; echo \$?; [ -e $T/none ] && echo created || true" | tr -d '\r' | tr '\n' ' ')" "2 "
expect "each end in the module log"                 "$(adb shell "grep -c 'saisie:' $T/bold7.log" | tr -d '\r')" "11"
adb shell "rm -rf $T"
