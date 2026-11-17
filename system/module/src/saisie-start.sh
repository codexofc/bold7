#!/system/bin/sh
# Bold7: selects Typing (dev.bold7.saisie) again after each boot.
#
#   sh saisie-start.sh <moddir>      boot-completed.sh, in the background
#
# Typing is not directBootAware, on purpose: before the user's encrypted storage opens, Android
# only lists the input methods that are, finds Typing missing, and writes LatinIME (directBootAware) as the default
# (InputMethodManagerService.buildInputMethodListLocked), never going back.
# This waits for sys.user.0.ce_available to be true, then, only if bold7_saisie is 1 (written by
# tools/install.sh when Typing is selected, deleted by tools/uninstall.sh and by the emergency
# switch) and Typing is installed, enables and selects it, and reads default_input_method back.
# Android may still be listing the input methods of the unlocked user: a selection that did not
# take is tried again, TRIES times, RETRY_SLEEP seconds apart. Never within MAX_WAIT seconds:
# given up for this boot, LatinIME stays.
#
# No polling: resetprop -w sleeps on the property until its value moves, under a timeout for what
# is left of MAX_WAIT. A wake-up without a change waits a second before looking again.
MODDIR=$1
[ -d "$MODDIR" ] || exit 2
# By their paths (KernelSU runs this with its busybox); test-saisie.sh gives fakes.
GETPROP=${GETPROP:-/system/bin/getprop} RESETPROP=${RESETPROP:-/data/adb/ksu/bin/resetprop}
SETTINGS=${SETTINGS:-/system/bin/settings} IME=${IME:-/system/bin/ime} PM=${PM:-/system/bin/pm}
MAX_WAIT=${MAX_WAIT:-900} TRIES=${TRIES:-5} RETRY_SLEEP=${RETRY_SLEEP:-2}
PROP=sys.user.0.ce_available
ID=dev.bold7.saisie/.SaisieService
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2" </dev/null >/dev/null 2>&1; echo "$2" >>"$LOG"; }
# Android's tools hand their file descriptors to system_server, which may not use a file of
# /data/adb (SELinux): with bold7.log as stderr (setup.sh apply calls this script), every call
# failed and printed "cmd: Failure calling service settings: Failed transaction" as its output, read
# as the value of bold7_saisie (first boot of the public zip on the Q25). So every tool runs with
# /dev/null or a pipe, and a failure is never a value: asked again, RETRY_SLEEP apart.
exec </dev/null 2>/dev/null
ASK_TRIES=${ASK_TRIES:-30}
ask() {
    local out i
    i=1
    while :; do
        if out=$("$@"); then
            case $out in cmd:* | *Failure* | *Exception*) ;; *) printf '%s\n' "$out"; return 0 ;; esac
        fi
        [ "$i" -lt "$ASK_TRIES" ] || return 1
        i=$((i + 1))
        sleep "$RETRY_SLEEP"
    done
}

start=$(date +%s)
while :; do
    s=$("$GETPROP" $PROP)
    [ "$s" = true ] && break
    left=$((start + MAX_WAIT - $(date +%s)))
    if [ "$left" -le 0 ]; then
        say w "saisie: encrypted storage still closed after ${MAX_WAIT}s, Typing not selected for this boot"
        exit 0
    fi
    if [ -n "$s" ]; then
        timeout "$left" "$RESETPROP" -w $PROP "$s" >/dev/null 2>&1
    else
        timeout "$left" "$RESETPROP" -w $PROP >/dev/null 2>&1
    fi
    [ "$("$GETPROP" $PROP)" != "$s" ] || sleep 1
done

if ! flag=$(ask "$SETTINGS" get global bold7_saisie); then
    say w "saisie: bold7_saisie could not be read (the settings service did not answer), Typing not selected"
    exit 1
fi
if [ "$flag" != 1 ]; then
    say i "saisie: bold7_saisie is not 1, the input method left as it is"
    exit 0
fi
case $("$PM" path dev.bold7.saisie 2>/dev/null) in
package:*) ;;
*) say w "saisie: dev.bold7.saisie not installed, the input method left as it is"; exit 0 ;;
esac
i=1
while :; do
    # Enabled again on each try: right after the storage opens, the list of input methods may not
    # have Typing yet.
    "$IME" enable $ID >/dev/null 2>&1
    "$IME" set $ID >/dev/null 2>&1
    now=$(ask "$SETTINGS" get secure default_input_method)
    if [ "$now" = "$ID" ]; then
        say i "saisie: Typing selected"
        exit 0
    fi
    if [ "$i" -ge "$TRIES" ]; then
        say w "saisie: Typing not selected after $TRIES tries (default_input_method=$now)"
        exit 1
    fi
    i=$((i + 1))
    sleep "$RETRY_SLEEP"
done
