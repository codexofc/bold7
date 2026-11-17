#!/system/bin/sh
# Bold7: the eco mode follows Android's battery saver, the "Battery saving" tile (docs/PERF.md, section 9).
#
#   sh saver.sh <moddir> <cpuctl>     boot-completed.sh, once energy.sh has set the boot floor
#
# Each time the saver turns on or off, energy.sh is called with eco or perf. Rule: the saver's state
# wins at each boot and at each of its changes; a manual `energy.sh … eco|perf` holds until the next.
#
# No polling and no app of ours: each time the saver changes, system_server writes the event
# battery_saver_mode (tag 2739, BatterySaverController.handleBatterySaverStateChanged, services.jar of
# LineageOS 24), after BatterySaverStateMachine has written Settings.Global.low_power. logcat blocks on
# the events buffer, filtered on that tag: it never wakes the phone itself, it only reads what logd
# hands it while something else is logging anyway (about one event a second, screen on), and this
# shell runs on the saver's own lines only. Each one makes it read low_power again: nothing is done
# unless it changed (the event also comes with the saver's policy changes). Android 14 had a property
# to wait on, cache_key.is_power_save_mode; Android 17 keeps those nonces in shared memory.
# Runs until reboot; stops at its next event once the module is disabled or marked for removal, or
# once another instance has started (saver.pid). Its logcat then goes at the event after (SIGPIPE).
MODDIR=$1 CPUCTL=$2
[ -d "$MODDIR" ] || exit 2
# By their paths (KernelSU runs this with its busybox); the test gives fakes.
LOGCAT=${LOGCAT:-/system/bin/logcat} SETTINGS=${SETTINGS:-/system/bin/settings}
TAG=battery_saver_mode
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }

echo $$ >"$MODDIR/saver.pid"
# What energy.sh boot has just applied.
if [ -f "$MODDIR/eco" ]; then last=1; else last=0; fi
follow() {
    s=$("$SETTINGS" get global low_power)
    case $s in
    1) ;;
    0 | null) s=0 ;;
    *)
        # system_server restarting: keep what is in force until the next change.
        say w "saver: low_power '$s' unreadable, floor kept"
        s=$last
        ;;
    esac
    if [ "$s" != "$last" ]; then
        if [ "$s" = 1 ]; then m=eco; else m=perf; fi
        # A failed write is tried again at the next change.
        sh "$MODDIR/energy.sh" "$MODDIR" "$CPUCTL" $m && last=$s
    fi
}
follow
# logcat prints the buffer first, then each new line: a change made after the read above is in it.
"$LOGCAT" -b events -v brief -s $TAG 2>/dev/null | {
    while read -r _; do
        [ "$(cat "$MODDIR/saver.pid" 2>/dev/null)" = $$ ] || exit 0
        if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
            say i "saver: module disabled or removed, battery saver no longer followed"
            exit 0
        fi
        follow
    done
    say w "saver: logcat of $TAG ended, battery saver no longer followed"
    exit 1
}
