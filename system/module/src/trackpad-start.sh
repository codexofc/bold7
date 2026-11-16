#!/system/bin/sh
# Bold7: starts bold7-trackpad once nothing may be typed on Android's
# own keyboard any more.
#
#   sh trackpad-start.sh <moddir>      boot-completed.sh, in the background
#
# Boot completes while the SIM's PIN screen may still be up (seen on the Q25: PIN_REQUIRED at 04:03:14.16,
# boot-completed.sh at 04:03:15.47): taking the keyboard then, mid-PIN, is what this avoids. It
# waits until gsm.sim.state (one field per slot, "LOADED,ABSENT") has no slot left in
# PIN_REQUIRED, PUK_REQUIRED, NOT_READY or UNKNOWN, nor is empty; then execs the program. Never
# within MAX_WAIT seconds (a PIN left untyped, a radio off at boot): given up for this boot, the
# trackpad stays Android's until the next one (or a start by hand).
#
# No polling: resetprop -w sleeps on the property until its value moves, under a timeout for what
# is left of MAX_WAIT. A wake-up without a change waits a second before looking again.
MODDIR=$1
[ -d "$MODDIR" ] || exit 2
# By their paths (KernelSU runs this with its busybox); test-trackpad.sh gives fakes.
GETPROP=${GETPROP:-/system/bin/getprop} RESETPROP=${RESETPROP:-/data/adb/ksu/bin/resetprop}
BIN=${BIN:-$MODDIR/bin/bold7-trackpad} MAX_WAIT=${MAX_WAIT:-900}
PROP=gsm.sim.state
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }

# Whether the SIM still waits for a code or is not known yet.
locked() {
    case ",$1," in
    ,,|*,PIN_REQUIRED,*|*,PUK_REQUIRED,*|*,NOT_READY,*|*,UNKNOWN,*) return 0 ;;
    esac
    return 1
}

start=$(date +%s)
while :; do
    s=$("$GETPROP" $PROP)
    if ! locked "$s"; then
        say i "trackpad: SIM '$s', starting bold7-trackpad"
        exec "$BIN" "$MODDIR"
    fi
    left=$((start + MAX_WAIT - $(date +%s)))
    if [ "$left" -le 0 ]; then
        say w "trackpad: SIM still '$s' after ${MAX_WAIT}s, bold7-trackpad not started for this boot"
        exit 0
    fi
    if [ -n "$s" ]; then
        timeout "$left" "$RESETPROP" -w $PROP "$s" >/dev/null 2>&1
    else
        timeout "$left" "$RESETPROP" -w $PROP >/dev/null 2>&1
    fi
    [ "$("$GETPROP" $PROP)" != "$s" ] || sleep 1
done
