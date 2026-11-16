#!/system/bin/sh
# Bold7: the eco mode follows Android's battery saver, the "Battery saving" tile (docs/PERF.md, section 9).
#
#   sh saver.sh <moddir> <cpuctl>     boot-completed.sh, once energy.sh has set the boot floor
#
# Each time the saver turns on or off, energy.sh is called with eco or perf. Rule: the saver's state
# wins at each boot and at each of its changes; a manual `energy.sh … eco|perf` holds until the next.
#
# No polling and no app of ours: system_server bumps the property cache_key.is_power_save_mode each
# time the saver changes (BatterySaverController), after writing Settings.Global.low_power
# (BatterySaverStateMachine). resetprop -w sleeps on that one property's futex until its value moves:
# nothing wakes at rest. The nonce also moves on policy changes (BatterySaverPolicy): low_power is
# read again, and nothing is done unless it changed.
# Runs until reboot; stops at its next wake-up once the module is disabled or marked for removal,
# or once another instance has started (saver.pid).
MODDIR=$1 CPUCTL=$2
[ -d "$MODDIR" ] || exit 2
# By their paths (KernelSU runs this with its busybox); the test gives fakes.
RESETPROP=${RESETPROP:-/data/adb/ksu/bin/resetprop} SETTINGS=${SETTINGS:-/system/bin/settings}
PROP=cache_key.is_power_save_mode
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }

echo $$ >"$MODDIR/saver.pid"
# What energy.sh boot has just applied.
if [ -f "$MODDIR/eco" ]; then last=1; else last=0; fi
# The nonce first: a change after this read wakes the wait below.
nonce=$("$RESETPROP" $PROP)
while :; do
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
    if ! "$RESETPROP" -w $PROP "$nonce" >/dev/null 2>&1; then
        say w "saver: wait on $PROP failed, battery saver no longer followed"
        exit 1
    fi
    n=$("$RESETPROP" $PROP)
    # A wait that returns without a change would spin: stop instead.
    if [ "$n" = "$nonce" ]; then
        say w "saver: woken without a change of $PROP, battery saver no longer followed"
        exit 1
    fi
    nonce=$n
    [ "$(cat "$MODDIR/saver.pid" 2>/dev/null)" = $$ ] || exit 0
    if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
        say i "saver: module disabled or removed, battery saver no longer followed"
        exit 0
    fi
done
