#!/usr/bin/env bash
# Runs the module's launcher compile step (compile.sh) with the phone's own sh, in a scratch folder
# of /data/local/tmp, against a fake cmd that prints a made-up dexopt status and records what it is
# asked: nothing is compiled, nothing else touched. Exits non-zero on failure.
#   system/module/test-compile.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-compile-test
adb shell "rm -rf $T && mkdir -p $T"
adb push src/compile.sh $T/ >/dev/null
# The fake cmd: `package art dump` prints $T/status as ART Service would, `package compile` records
# its arguments and fails if $T/fail exists.
adb shell "cat > $T/cmd && chmod 755 $T/cmd" <<EOF
#!/system/bin/sh
case "\$1 \$2" in
'package art') echo "[dev.bold7.launcher]"; echo "  arm64: [status=\$(cat $T/status)] [reason=unknown] [primary-abi]" ;;
'package compile') echo "\$*" >>$T/calls; [ -e $T/fail ] && { echo Failure; exit 1; }; echo Success ;;
esac
EOF
# Sets the status and the flag, runs the step; prints the compile calls and whether the flag is left.
# $T/apk stands for the mounted launcher APK; a third argument removes it first.
run() {
  adb shell "rm -f $T/calls $T/fail; touch $T/apk; ${3:-true}; echo $1 > $T/status; ${2:-touch $T/compile}; sh $T/compile.sh $T $T/cmd $T/apk; cat $T/calls 2>/dev/null; [ -e $T/compile ] && echo flag-left || true" | tr -d '\r' | tr '\n' ' '
}
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; adb shell "rm -rf $T"; exit 1; }; }

C="package compile -m speed-profile -f dev.bold7.launcher "
expect "no install, nothing asked"         "$(run run-from-apk true)" ""
expect "interpreted launcher is compiled"  "$(run run-from-apk)" "$C"
expect "verify only is compiled"           "$(run verify)" "$C"
expect "speed-profile is left alone"       "$(run speed-profile)" ""
expect "speed is left alone"               "$(run speed)" ""
expect "a failed compile is not retried"   "$(run run-from-apk "touch $T/compile $T/fail")" "$C"
expect "unmounted launcher keeps the flag"  "$(run run-from-apk "" "rm $T/apk")" "flag-left "
expect "and compiles at the next boot"      "$(adb shell "rm -f $T/calls; touch $T/apk; sh $T/compile.sh $T $T/cmd $T/apk; cat $T/calls; [ -e $T/compile ] && echo flag-left || true" | tr -d '\r' | tr '\n' ' ')" "$C"
expect "each decision in the module log"    "$(adb shell "grep -c 'compile:' $T/bold7.log" | tr -d '\r')" "7"
adb shell "rm -rf $T"
