#!/system/bin/sh
# Bold7: CPU floor of the top-app group, and its eco mode (docs/PERF.md, section 8).
#
#   sh energy.sh <moddir> <cpuctl> boot      boot-completed.sh, with /dev/cpuctl
#   sh energy.sh <moddir> <cpuctl> perf|eco  saver.sh, when the battery saver changes; or by hand, as root
#   sh energy.sh <moddir> <cpuctl>           prints the floor in force and the kept choice
#
# Gestures start on cold cores (little ones at 500 to 700 MHz, nothing rises before ~150 ms), and
# MediaTek's own boosts do nothing on this ROM. A uclamp.min on the top-app group (the foreground
# app, but also zygote, the camera servers, the keyboard and UI threads of system_server) makes
# the scheduler ask, as soon as one of its threads runs, a frequency fit for that share of a big
# core's capacity: 20 % puts a little core near 1.5 GHz and leaves the big ones at their floor.
# Nothing runs, nothing is asked: idle costs nothing. 0 is the ROM's value, and eco mode.
# The value lives in the cgroup, in memory: a boot without the module leaves the ROM's 0.
# eco follows Android's battery saver (saver.sh, PERF.md section 9); the kept choice is what boot applies
# before saver.sh, which then applies the saver's own state.
MODDIR=$1 CPUCTL=$2 MODE=$3
[ -d "$MODDIR" ] || exit 2
PERF=20
case $WAIT in '' | *[!0-9]*) WAIT=30 ;; esac
FILE=$CPUCTL/top-app/cpu.uclamp.min
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }

if [ ! -f "$FILE" ]; then
    say w "energy: no $FILE, nothing done"
    exit 0
fi
apply() {
    if ! echo "$1" >"$FILE"; then
        say w "energy: $FILE not written"
        exit 1
    fi
    say i "energy: $2, top-app uclamp.min $(cat "$FILE")"
}
case $MODE in
boot)
    # MediaTek's init writes 100 at post-fs-data and 0.00 on sys.boot_completed, the trigger that
    # starts this script too: our value goes after that reset, or it would be overwritten.
    n=0
    while [ "$(cat "$FILE")" != 0.00 ]; do
        if [ $n -ge "$WAIT" ]; then
            say w "energy: top-app uclamp.min still $(cat "$FILE") after $WAIT s, left as is"
            exit 1
        fi
        sleep 1
        n=$((n + 1))
    done
    if [ -f "$MODDIR/eco" ]; then apply 0 eco; else apply $PERF perf; fi
    ;;
perf)
    rm -f "$MODDIR/eco"
    apply $PERF perf
    ;;
eco)
    if ! touch "$MODDIR/eco"; then
        say w "energy: eco flag not written, eco not kept"
        exit 1
    fi
    apply 0 eco
    ;;
'')
    if [ -f "$MODDIR/eco" ]; then c=eco; else c=perf; fi
    echo "top-app uclamp.min $(cat "$FILE"), kept: $c"
    ;;
*)
    echo "usage: energy.sh <moddir> <cpuctl> [boot|perf|eco]" >&2
    exit 2
    ;;
esac
