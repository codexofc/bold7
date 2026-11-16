#!/system/bin/sh
# Bold7: what tools/install.sh and tools/uninstall.sh did from the Mac, done on the phone by the
# module itself. POSIX sh: KernelSU runs it with its busybox.
#
#   sh setup.sh check <moddir>     customize.sh: a Q25 under BenOS, no metamodule, nothing of the BB10
#                                  build, no removal still being finished, the stock keylayout the
#                                  module's copy was made from. Reads only; fails saying why.
#   sh setup.sh save <moddir>      customize.sh: the values Bold7 changes, saved once in $S
#   sh setup.sh install <moddir>   customize.sh: Vector if missing, then the apps (pm install)
#   sh setup.sh apply <moddir>     boot-completed.sh, in the background, at each boot: once the user's
#                                  storage is open, sets once each value saved, then the apps' access
#                                  once per install
#   sh setup.sh access <moddir>    that access again, now (tools/install.sh --permissions)
#   sh setup.sh restore            the one script uninstall.sh leaves in /data/adb/boot-completed.d:
#                                  gives back what was saved, uninstalls the apps, deletes itself
#
# State in $S (/data/adb/bold7-state, root only), kept across updates of the module:
#   <item>-avant.txt   the values before install, written once by save, never overwritten
#   <item>.fait        set once: by apply, or by save when it refuses (nothing to give back, so
#                      nothing set); never set again, so a later choice of the user stands
#   acces.fait         permissions, app-ops and accesses given for this install (install deletes it)
#   vector.installe    Vector installed by Bold7, removed with it (uninstall.sh)
# Items, and the Mac script whose rules they keep: silence (tools/silence.sh), touches
# (tools/touches.sh), sons (tools/sons.sh), trackpad (tools/trackpad.sh), dialer (tools/dialer.sh),
# home (the HOME role, as dialer), saisie (select_saisie of tools/install.sh). restore gives a value
# back only while it is still the one Bold7 set; a broken save is kept, its value left as it is.
# Google Messages' consent is never set here: it stays the user's gesture.
#
# Every tool by its path, from $TOOLS (test-setup.sh gives fakes); files read under $ROOT.
TOOLS=${TOOLS:-/system/bin}
ADB=${ADB_DIR:-/data/adb}
S=${STATE:-$ADB/bold7-state}
KSUD=${KSUD:-$ADB/ksud}
RESETPROP=${RESETPROP:-$ADB/ksu/bin/resetprop}
ROOT=${ROOT:-}
USER_DE=${USER_DE:-/data/user_de/0}
MODE=${TRACKPAD_MODE:-/proc/q20_switch_key_mouse}
KL=${KL:-/system/usr/keylayout/Q25_keyboard.kl}
MAX_WAIT=${MAX_WAIT:-900} PAUSE=${PAUSE:-1}
VECTOR=$ADB/modules/zygisk_vector
WRAPPER=$ADB/boot-completed.d/bold7-retour.sh
TAB=$(printf '\t')

L=dev.bold7.launcher
HUB=dev.bold7.hub
HUB_LISTENER=$HUB/$HUB.HubListener
MESSAGES=dev.bold7.messages
PHONE=dev.bold7.phone
CLOCK=dev.bold7.clock
CALENDAR=dev.bold7.calendar
CONTACTS=dev.bold7.contacts
TASKS=dev.bold7.tasks
PICTURES=dev.bold7.pictures
SAISIE=dev.bold7.saisie
SAISIE_IME=$SAISIE/.SaisieService
SYSUI=dev.bold7.sysui
# The ordinary apps, in install order: Messages after the Hub and the Phone, which declare the
# signature permissions it asks (READ_HUB, CALL_PROMPT).
APPS="$HUB $PHONE $MESSAGES $SAISIE $CLOCK $CALENDAR $CONTACTS dev.bold7.calculator dev.bold7.flashlight dev.bold7.memopad $TASKS $PICTURES $SYSUI"
GM=com.google.android.apps.messaging
GOOGLE_DIALER=com.google.android.dialer
LAUNCHER3=com.android.launcher3
BBKB=com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME
# The Q25's PermissionController receiver that exempts a hard restricted permission (READ_SMS,
# READ_CALL_LOG); tools/install.sh, grant_permissions.
EXEMPT=com.google.android.permissioncontroller/com.android.permissioncontroller.permission.service.ExemptRestrictedPermission
LAUNCHER_PERMISSIONS="READ_CONTACTS READ_SMS READ_CALL_LOG READ_CALENDAR BLUETOOTH_CONNECT ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION"
ITEMS="silence touches sons trackpad dialer home saisie"
SILENCE="global:charging_sounds_enabled secure:charging_vibration_enabled system:lockscreen_sounds_enabled system:sound_effects_enabled system:accelerometer_rotation secure:clipboard_show_access_notifications"
SONS="ringtone notification_sound alarm_alert"
A=/product/media/audio
PROBE=$A/ringtones/Spirit.m4a
SERVICE=tech.shroyer.q25trackpadcustomizer/tech.shroyer.q25trackpadcustomizer.AppSwitchService

say() { echo "$*"; "$TOOLS/log" -p i -t Bold7 "$*" 2>/dev/null; }
die() { say "! $*"; exit 1; }
gp() { "$TOOLS/getprop" "$1"; }
get() { "$TOOLS/settings" get "$1" "$2" 2>/dev/null; }
# put <namespace> <key> <value>: an empty value or null deletes; read back.
put() {
    if [ -z "$3" ] || [ "$3" = null ]; then
        "$TOOLS/settings" delete "$1" "$2" >/dev/null 2>&1
        [ "$(get "$1" "$2")" = null ] && return 0
    else
        "$TOOLS/settings" put "$1" "$2" "$3" >/dev/null 2>&1
        [ "$(get "$1" "$2")" = "$3" ] && return 0
    fi
    say "! $2 did not take the value '$3' (now $(get "$1" "$2"))"
    return 1
}
num() { [ "$1" = null ] && return 0; case $1 in '' | *[!0-9]*) return 1 ;; esac; }
pkg_name() { echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }
pkg_ok() { case $("$TOOLS/pm" path "$1" 2>/dev/null) in package:*) return 0 ;; esac; return 1; }
lines() { grep -c . "$1"; }
# Waits for the user's encrypted storage (sys.user.0.ce_available, as saisie-start.sh): Typing,
# MediaStore and PermissionController only answer then. resetprop -w sleeps until the value moves.
wait_ce() {
    local start s left
    start=$(date +%s)
    while :; do
        s=$(gp sys.user.0.ce_available)
        [ "$s" = true ] && return 0
        left=$((start + MAX_WAIT - $(date +%s)))
        if [ "$left" -le 0 ]; then
            say "user storage still closed after ${MAX_WAIT}s: nothing done, tried again at the next boot"
            return 1
        fi
        if [ -n "$s" ]; then
            timeout "$left" "$RESETPROP" -w sys.user.0.ce_available "$s" >/dev/null 2>&1
        else
            timeout "$left" "$RESETPROP" -w sys.user.0.ce_available >/dev/null 2>&1
        fi
        [ "$(gp sys.user.0.ce_available)" != "$s" ] || sleep 1
    done
}

# --- roles ---------------------------------------------------------------------------------------
holder() { "$TOOLS/cmd" role get-role-holders "android.app.role.$1" 2>/dev/null; }
# give <role> <package>: right after boot the role service can time out (TimeoutException):
# asked again for a minute, then read back.
give() {
    local n out
    n=0
    while :; do
        out=$("$TOOLS/cmd" role add-role-holder "android.app.role.$1" "$2" 2>&1)
        case $out in *Exception*) ;; *) break ;; esac
        n=$((n + 1))
        [ $n -lt 12 ] || break
        sleep $((PAUSE * 5))
    done
    [ "$(holder "$1")" = "$2" ] && { say "  $1 given to $2"; return 0; }
    say "! $1 is held by '$(holder "$1")', not $2"
    return 1
}

# --- sounds (tools/sons.sh) ----------------------------------------------------------------------
# The 9900's default for each setting (Normal profile, docs/BBOS7-VISUEL.md section 20).
target() {
    case $1 in
    ringtone) echo $A/ringtones/Spirit.m4a ;;
    notification_sound) echo $A/notifications/SanguineRemix.m4a ;;
    alarm_alert) echo $A/alarms/Dream.m4a ;;
    esac
}
rows() { "$TOOLS/content" query --uri "content://media/$1/audio/media" --projection "$2" --where "$3" 2>/dev/null; }
# The file a setting's value plays: the _data of its MediaStore row (any volume), empty if none.
file_of() {
    local vol id
    case $1 in content://media/*/audio/media/[0-9]*) ;; *) return 0 ;; esac
    vol=${1#content://media/}
    vol=${vol%%/*}
    id=${1#content://media/*/audio/media/}
    id=${id%%[!0-9]*}
    rows "$vol" _data "_id=$id" | sed -n 's/.*_data=//p' | head -n 1
}
# The value Android's own picker would store for a file (empty if MediaStore does not know it).
uri_of() {
    local row id title
    row=$(rows internal _id:title "_data='$1'" | grep '^Row:' | head -n 1)
    [ -n "$row" ] || return 0
    id=$(echo "$row" | sed -n 's/.*_id=\([0-9]*\),.*/\1/p')
    title=$(echo "$row" | sed -n 's/.*title=//p' | sed 's/ /%20/g')
    echo "content://media/internal/audio/media/$id?title=$title&canonical=1"
}
# A choice of the user's: an item of a volume other than internal, or a file outside the system
# partitions (a ringtone in /sdcard/Ringtones). $1 the value, $2 its file.
personal() {
    case $1 in content://media/internal/*) ;; content://media/*) return 0 ;; esac
    [ -n "$2" ] || return 1
    case $2 in /product/* | /system/* | /system_ext/* | /vendor/*) return 1 ;; esac
    return 0
}
sound_value() { case $1 in null | content://media/*) return 0 ;; esac; return 1; }
scan() { "$TOOLS/content" call --uri content://media --method scan_volume --arg internal >/dev/null 2>&1; }

# --- trackpad (tools/trackpad.sh) ----------------------------------------------------------------
has_service() { case ":$1:" in *":$SERVICE:"*) return 0 ;; esac; return 1; }
without_service() {
    local out old x
    out= old=$IFS
    IFS=:
    for x in $1; do [ "$x" = "$SERVICE" ] || out=${out:+$out:}$x; done
    IFS=$old
    echo "$out"
}

# --- check ---------------------------------------------------------------------------------------
do_check() {
    local d id
    d=$(gp ro.product.device)
    [ "$d" = Q25 ] || die "this phone is not a Zinwa Q25 (ro.product.device=$d): Bold7 is made for it alone. Nothing changed."
    id=$(gp ro.build.display.id)
    case $id in BenOS*) ;; *) die "this Q25 does not run BenOS (ro.build.display.id=$id): Bold7 is made for BenOS. Nothing changed." ;; esac
    say "- Zinwa Q25 under $id (ro.benos_timestamp=$(gp ro.benos_timestamp))"
    [ ! -e "$ADB/metamodule" ] || die "a metamodule is installed ($ADB/metamodule): Bold7 mounts its files itself, remove the metamodule first. Nothing changed."
    [ ! -e "$WRAPPER" ] || die "the removal of Bold7 is not finished: unlock the phone, wait a minute, then install again. Nothing changed."
    if [ -e "$ADB/modules/cascade" ] || [ -e "$ADB/modules_update/cascade" ] || "$TOOLS/pm" list packages -u dev.cascade 2>/dev/null | grep -q .; then
        die "the BB10 build (cascade) is still on the phone: remove it with the tools/uninstall.sh of its tag bb10-final, then reboot. Nothing changed."
    fi
    # The module's Q25_keyboard.kl is the stock one with End as ENDCALL: over another
    # stock file it would hide what changed.
    [ "$(sed 's/^key 172   ENDCALL$/key 172   HOME/' "$1/layer/product/usr/keylayout/Q25_keyboard.kl")" = "$(cat "$KL" 2>/dev/null)" ] ||
        die "$KL is not the one the End key of Bold7 was made from: this BenOS version is not supported yet. Nothing changed."
}

# --- save ----------------------------------------------------------------------------------------
# refuse <item> <why>: nothing saved, so nothing will be set nor given back.
refuse() { rm -f "$S/$1-avant.txt.new"; touch "$S/$1.fait"; say "  $1: $2: left as it is"; }
keep() { mv -f "$S/$1-avant.txt.new" "$S/$1-avant.txt"; say "  $1: saved"; }
save_silence() {
    local k v
    : >"$S/silence-avant.txt.new"
    for k in $SILENCE; do
        v=$(get "${k%%:*}" "${k#*:}")
        num "$v" || { refuse silence "${k#*:}=$v is not a value that could be written back"; return; }
        [ "$v" = 0 ] || echo "${k#*:} $v" >>"$S/silence-avant.txt.new"
    done
    keep silence
}
save_touches() {
    local v
    v=$(get system end_button_behavior)
    num "$v" || { refuse touches "end_button_behavior=$v is not a value that could be written back"; return; }
    [ "$v" != 1 ] || { refuse touches "end_button_behavior is 1 already"; return; }
    echo "$v" >"$S/touches-avant.txt.new"
    keep touches
}
save_sons() {
    local k v f
    : >"$S/sons-avant.txt.new"
    for k in $SONS; do
        v=$(get system $k)
        sound_value "$v" || { refuse sons "$k=$v is not a value that could be written back"; return; }
        f=$(file_of "$v")
        if [ -n "$f" ] && [ -e "$1/layer$f" ]; then refuse sons "$k already plays $f, a sound of Bold7"; return; fi
        printf '%s\t%s\n' $k "$v" >>"$S/sons-avant.txt.new"
    done
    keep sons
}
save_trackpad() {
    local v
    v=$(get secure enabled_accessibility_services)
    has_service "$v" || { refuse trackpad "Q25TrackpadCustomizer's service is off already"; return; }
    echo "$v" >"$S/trackpad-avant.txt.new"
    keep trackpad
}
# save_role <item> <role> <our package>
save_role() {
    local v
    v=$(holder $2)
    pkg_name "$v" || { refuse $1 "$2 is held by '$v', no holder to give it back to"; return; }
    [ "$v" != $3 ] || { refuse $1 "$3 holds $2 already"; return; }
    echo "$v" >"$S/$1-avant.txt.new"
    keep $1
}
save_dialer() { save_role dialer DIALER $PHONE; }
save_home() { save_role home HOME $L; }
save_saisie() {
    local ime list
    ime=$(get secure default_input_method)
    list=$(get secure enabled_input_methods)
    case "$ime $list" in *$SAISIE*) refuse saisie "Typing is selected or enabled already"; return ;; esac
    printf 'default_input_method=%s\nenabled_input_methods=%s\n' "$ime" "$list" >"$S/saisie-avant.txt.new"
    keep saisie
}
do_save() {
    local i
    mkdir -p "$S" && chmod 700 "$S" || die "$S not created"
    say "- Values before Bold7, saved once in $S:"
    for i in $ITEMS; do
        if [ -e "$S/$i-avant.txt" ] || [ -e "$S/$i.fait" ]; then say "  $i: kept from before"; else save_$i "$1"; fi
    done
}

# --- install -------------------------------------------------------------------------------------
do_install() {
    local out rc p
    mkdir -p "$S" && chmod 700 "$S"
    # An `adb install -r` of the launcher lives in /data/app and, at the same versionCode, would hide
    # the priv-app of the module. pm exits 1 even when it prints Success (Q25).
    case $("$TOOLS/pm" path $L 2>/dev/null) in package:/data/*)
        out=$("$TOOLS/pm" uninstall-system-updates $L 2>&1)
        case $out in *Success*) say "- Removed the update of $L installed over its priv-app" ;; *) say "! Could not remove the update of $L: $out" ;; esac
        ;;
    esac
    # Vector (LSPosed's fork, GPL-3.0): installed or not marked for removal, it stays as it is.
    # Installed by ksud from this module's own folder: the nested installer empties /dev/tmp, ours
    # too (measured on the Q25), so nothing after it may count on $TMPDIR.
    if [ -d "$VECTOR" ] && [ ! -e "$VECTOR/remove" ]; then
        say "- Vector installed already"
    elif [ ! -f "$1/vector/vector.zip" ]; then
        say "! Vector is missing and not in this zip: SystemUI keeps Android's look"
    else
        say "- Installing Vector (active after the reboot)"
        out=$("$KSUD" module install "$1/vector/vector.zip" 2>&1)
        rc=$?
        echo "$out" | sed 's/^/  | /'
        if [ $rc = 0 ]; then touch "$S/vector.installe"; else say "! Vector not installed (ksud $rc): SystemUI keeps Android's look"; fi
    fi
    for p in $APPS; do
        if [ ! -f "$1/apps/$p.apk" ]; then say "! $p is not in this zip"; continue; fi
        out=$("$TOOLS/pm" install -r "$1/apps/$p.apk" 2>&1)
        case $out in *Success*) say "- $p installed" ;; *) say "! $p not installed: $out" ;; esac
    done
    rm -rf "$1/apps" "$1/vector"
    # A new install: the apps' access is given again at the next boot.
    rm -f "$S/acces.fait"
}

# --- apply ---------------------------------------------------------------------------------------
set_silence() {
    local r k
    r=0
    for k in $SILENCE; do [ "$(get "${k%%:*}" "${k#*:}")" = 0 ] || put "${k%%:*}" "${k#*:}" 0 || r=1; done
    return $r
}
set_touches() { put system end_button_behavior 1; }
set_sons() {
    local k cur u r
    # MediaStore may not have seen the module's files yet.
    for k in $SONS; do [ -n "$(uri_of "$(target $k)")" ] || { scan; break; }; done
    r=0
    for k in $SONS; do
        cur=$(get system $k)
        if personal "$cur" "$(file_of "$cur")"; then say "  $k kept: it plays $cur, a choice of the user's"; continue; fi
        u=$(uri_of "$(target $k)")
        if [ -z "$u" ]; then say "! $(target $k) is not in MediaStore"; r=1; continue; fi
        put system $k "$u" || r=1
    done
    return $r
}
set_trackpad() {
    local v
    v=$(get secure enabled_accessibility_services)
    has_service "$v" || return 0
    put secure enabled_accessibility_services "$(without_service "$v")"
}
set_dialer() { pkg_ok $PHONE || { say "! $PHONE is not installed"; return 1; }; give DIALER $PHONE; }
set_home() {
    pkg_ok $L || { say "! $L is not installed (module not mounted?)"; return 1; }
    give HOME $L || return 1
    "$TOOLS/am" start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
    return 0
}
# Typing: bold7_saisie, then saisie-start.sh (which selects it again after every boot) selects it now.
set_saisie() {
    pkg_ok $SAISIE || { say "! $SAISIE is not installed"; return 1; }
    put global bold7_saisie 1 || return 1
    sh "$1/saisie-start.sh" "$1"
    [ "$(get secure default_input_method)" = "$SAISIE_IME" ] || { say "! Typing not selected"; return 1; }
}

# grant <package> <permission>...: those of the runtime permissions (short names) the package
# requests, READ_SMS and READ_CALL_LOG exempted first, each read back (tools/install.sh).
grant() {
    local p x dump wanted missing
    p=$1
    shift
    pkg_ok $p || return 0
    dump=$("$TOOLS/dumpsys" package $p)
    wanted=
    for x in "$@"; do echo "$dump" | grep -qE "^ +android\.permission\.$x\$" && wanted="$wanted $x"; done
    [ -n "$wanted" ] || return 0
    for x in $wanted; do
        case $x in READ_SMS | READ_CALL_LOG) "$TOOLS/am" broadcast -n $EXEMPT --es package $p --es permission android.permission.$x >/dev/null 2>&1 ;; esac
        "$TOOLS/pm" grant $p android.permission.$x >/dev/null 2>&1
    done
    dump=$("$TOOLS/dumpsys" package $p)
    missing=
    for x in $wanted; do echo "$dump" | grep -q "android\.permission\.$x: granted=true" || missing="$missing $x"; done
    [ -z "$missing" ] || { say "! $p: not granted:$missing"; return 1; }
    say "  $p: granted$wanted"
}
appop() {
    pkg_ok $1 || return 0
    "$TOOLS/appops" set $1 $2 allow >/dev/null 2>&1
    case $("$TOOLS/appops" get $1 $2 2>/dev/null) in *allow*) return 0 ;; esac
    say "! $1: app-op $2 not allowed"
    return 1
}
# poll <command...>: true within 5 tries, $PAUSE apart (a service writes its setting a moment later).
poll() {
    for _ in 1 2 3 4 5; do
        "$@" && return 0
        sleep $PAUSE
    done
    return 1
}
listener_on() { case $(get secure enabled_notification_listeners) in *"$HUB_LISTENER"*) return 0 ;; esac; return 1; }
dozing_exempt() { "$TOOLS/dumpsys" deviceidle whitelist 2>/dev/null | grep -q ",$HUB,"; }
# Android 14 keeps the Do Not Disturb access in NotificationManagerService's approved condition
# providers: the primary line of "Allowed condition providers" in its dump (tools/install.sh).
dnd_on() {
    local a
    a=$("$TOOLS/dumpsys" notification 2>/dev/null | awk '/Allowed condition providers:/ { f = 1; next } f && /isPrimary: true/ { print $1; exit }')
    case ":$a:" in *":$L:"*) return 0 ;; esac
    return 1
}
# Bold7's Vector module on SystemUI and Google Messages (its texts bridge). Vector hooks a
# process when it starts: once newly scoped, SystemUI is restarted (one death, under sysui-guard.sh's
# three) and Google Messages killed if it waits in the background.
scope() {
    local c pid
    pkg_ok $SYSUI || return 0
    c=$VECTOR/cli
    [ -x "$c" ] || { say "! Vector is not installed: $SYSUI not scoped"; return 1; }
    if "$c" scope ls $SYSUI 2>/dev/null | grep -q '^com\.android\.systemui ' &&
        "$c" scope ls $SYSUI 2>/dev/null | grep -q "^$GM " &&
        "$c" modules ls 2>/dev/null | grep -q "^$SYSUI .*enabled"; then
        return 0
    fi
    if ! "$c" modules enable $SYSUI >/dev/null 2>&1 || ! "$c" scope set $SYSUI com.android.systemui/0 $GM/0 >/dev/null 2>&1; then
        say "! Vector refused to scope $SYSUI (not running yet?)"
        return 1
    fi
    say "  $SYSUI scoped to SystemUI and Google Messages: SystemUI restarted"
    pid=$("$TOOLS/pidof" com.android.systemui) && "$TOOLS/kill" $pid
    "$TOOLS/am" kill $GM >/dev/null 2>&1
    return 0
}
do_access() {
    local r
    r=0
    say "- Access of the apps:"
    grant $L $LAUNCHER_PERMISSIONS || r=1
    if pkg_ok $L; then
        "$TOOLS/cmd" notification allow_dnd $L >/dev/null 2>&1
        poll dnd_on || { say "! $L: Do Not Disturb access not recorded"; r=1; }
    fi
    if pkg_ok $HUB; then
        grant $HUB READ_SMS READ_CALL_LOG READ_CALENDAR READ_CONTACTS || r=1
        "$TOOLS/cmd" notification allow_listener $HUB_LISTENER >/dev/null 2>&1
        poll listener_on || { say "! $HUB: notification access not recorded"; r=1; }
        "$TOOLS/dumpsys" deviceidle whitelist +$HUB >/dev/null 2>&1
        dozing_exempt || { say "! $HUB: not exempted from Doze"; r=1; }
    fi
    if pkg_ok $MESSAGES; then
        grant $MESSAGES READ_SMS READ_CONTACTS READ_MEDIA_IMAGES READ_MEDIA_VIDEO READ_MEDIA_AUDIO || r=1
        "$TOOLS/pm" grant $MESSAGES $GM.EXTERNAL_MESSAGING_API >/dev/null 2>&1
        "$TOOLS/dumpsys" package $MESSAGES | grep -q "$GM.EXTERNAL_MESSAGING_API: granted=true" ||
            { say "! $MESSAGES: $GM.EXTERNAL_MESSAGING_API not granted (Google Messages missing?)"; r=1; }
    fi
    grant $PHONE CALL_PHONE READ_CALL_LOG READ_CONTACTS READ_PHONE_NUMBERS READ_PHONE_STATE POST_NOTIFICATIONS || r=1
    appop $PHONE USE_FULL_SCREEN_INTENT || r=1
    grant $CLOCK POST_NOTIFICATIONS || r=1
    appop $CLOCK USE_FULL_SCREEN_INTENT || r=1
    appop $CLOCK SYSTEM_ALERT_WINDOW || r=1
    grant $CALENDAR READ_CALENDAR WRITE_CALENDAR || r=1
    appop $CALENDAR SYSTEM_ALERT_WINDOW || r=1
    grant $TASKS POST_NOTIFICATIONS || r=1
    appop $TASKS SYSTEM_ALERT_WINDOW || r=1
    grant $CONTACTS READ_CONTACTS WRITE_CONTACTS CALL_PHONE || r=1
    grant $PICTURES READ_MEDIA_IMAGES READ_CONTACTS WRITE_CONTACTS || r=1
    appop $PICTURES MANAGE_MEDIA || r=1
    scope || r=1
    return $r
}
do_apply() {
    local todo fails i
    todo=
    for i in $ITEMS; do [ -e "$S/$i-avant.txt" ] && [ ! -e "$S/$i.fait" ] && todo="$todo $i"; done
    [ -n "$todo" ] || [ ! -e "$S/acces.fait" ] || return 0
    wait_ce || return 0
    fails=0
    for i in $todo; do
        if set_$i "$1"; then
            touch "$S/$i.fait"
            say "$i: set"
        else
            fails=$((fails + 1))
            say "! $i: not set, tried again at the next boot"
        fi
    done
    if [ ! -e "$S/acces.fait" ]; then
        if do_access; then touch "$S/acces.fait"; else fails=$((fails + 1)); say "! access: tried again at the next boot"; fi
    fi
    [ $fails = 0 ] && return 0
    # What the manager shows on the module's card until the next boot (override.description).
    KSU_MODULE=bold7 "$KSUD" module config set -t override.description \
        "Bold7: $fails step(s) not done yet, tried again at the next boot (bold7.log of the module)." >/dev/null 2>&1
    return 1
}

# --- restore -------------------------------------------------------------------------------------
broken() { say "! $1: not the save Bold7 writes, kept, nothing changed"; return 1; }
back_silence() {
    local f r key v extra k ns
    f=$S/silence-avant.txt
    [ -e "$f" ] || return 0
    while read -r key v extra; do
        [ -n "$key" ] || continue
        case " $SILENCE " in *":$key "*) ;; *) broken "$f"; return 1 ;; esac
        [ -z "$extra" ] && num "${v:-x}" || { broken "$f"; return 1; }
    done <"$f"
    r=0
    while read -r key v; do
        [ -n "$key" ] || continue
        for k in $SILENCE; do [ "${k#*:}" = "$key" ] && ns=${k%%:*}; done
        if [ "$(get $ns $key)" = 0 ]; then put $ns $key "$v" && say "  $key back to $v" || r=1; else say "  $key kept: $(get $ns $key), chosen later"; fi
    done <"$f"
    [ $r = 1 ] || rm -f "$f"
    return $r
}
back_touches() {
    local f v cur
    f=$S/touches-avant.txt
    [ -e "$f" ] || return 0
    v=$(cat "$f")
    [ "$(lines "$f")" = 1 ] && num "$v" || { broken "$f"; return 1; }
    cur=$(get system end_button_behavior)
    if [ "$cur" = 1 ]; then put system end_button_behavior "$v" || return 1; say "  end_button_behavior back to $v"; else say "  end_button_behavior kept: $cur, chosen later"; fi
    rm -f "$f"
}
# A setting goes back while it plays what Bold7 set, a file gone with the module, or a MediaStore
# row gone with it; then MediaStore forgets the module's sounds; fails if a setting plays nothing.
back_sons() {
    local f r k v cur fl gone
    f=$S/sons-avant.txt
    r=0
    if [ -e "$f" ]; then
        [ "$(lines "$f")" = 3 ] || { broken "$f"; return 1; }
        for k in $SONS; do [ "$(grep -c "^$k$TAB" "$f")" = 1 ] || { broken "$f"; return 1; }; done
        while IFS=$TAB read -r k v; do sound_value "$v" || { broken "$f"; return 1; }; done <"$f"
        while IFS=$TAB read -r k v; do
            cur=$(get system $k)
            fl=$(file_of "$cur")
            gone=
            case $cur in content://media/*) [ -n "$fl" ] && [ -e "$ROOT$fl" ] || gone=1 ;; esac
            if [ "$fl" = "$(target $k)" ] || [ -n "$gone" ]; then
                put system $k "$v" && say "  $k back to $v" || r=1
            else
                say "  $k kept: $cur, chosen later"
            fi
        done <"$f"
    fi
    if [ ! -e "$ROOT$PROBE" ] && rows internal _id "_data='$PROBE'" | grep -q '^Row:'; then
        scan
        say "  MediaStore rescanned: the sounds of Bold7 are no longer listed"
    fi
    for k in $SONS; do
        cur=$(get system $k)
        case $cur in content://media/*)
            fl=$(file_of "$cur")
            [ -n "$fl" ] && [ -e "$ROOT$fl" ] || { say "! $k=$cur plays nothing any more: choose another sound in Android's Settings"; r=1; }
            ;;
        esac
    done
    [ $r = 1 ] || rm -f "$f"
    return $r
}
back_trackpad() {
    local f saved cur w
    # bold7-trackpad left with the module; the driver back in arrow mode should a stop have left it raw.
    if [ -e "$MODE" ] && [ "$(cat "$MODE")" != 1 ]; then
        echo 1 >"$MODE"
        say "  trackpad back in arrow mode"
    fi
    f=$S/trackpad-avant.txt
    [ -e "$f" ] || return 0
    saved=$(cat "$f")
    [ "$(lines "$f")" = 1 ] && has_service "$saved" || { broken "$f"; return 1; }
    cur=$(get secure enabled_accessibility_services)
    w=$(without_service "$saved")
    if [ "$cur" = "$w" ] || { [ "$cur" = null ] && [ -z "$w" ]; }; then
        put secure enabled_accessibility_services "$saved" || return 1
        say "  Q25TrackpadCustomizer's service back on"
    elif has_service "$cur"; then
        say "  Q25TrackpadCustomizer's service on already"
    else
        [ "$cur" = null ] && cur=
        put secure enabled_accessibility_services "${cur:+$cur:}$SERVICE" || return 1
        say "  Q25TrackpadCustomizer's service added to a list changed later"
    fi
    rm -f "$f"
}
# back_role <item> <role> <our package> <fallback>: without a save, the role goes to the fallback
# while ours holds it. With one, it goes back to the saved holder (the fallback if that one is gone)
# while ours, nobody or, for HOME, the fallback Android put in place of the uninstalled launcher
# holds it; another holder is a later choice.
back_role() {
    local f cur saved
    f=$S/$1-avant.txt
    cur=$(holder $2)
    if [ ! -e "$f" ]; then
        [ "$cur" != $3 ] || give $2 $4
        return
    fi
    saved=$(cat "$f")
    [ "$(lines "$f")" = 1 ] && pkg_name "$saved" || { broken "$f"; return 1; }
    if [ "$cur" = $3 ] || [ -z "$cur" ] || { [ $2 = HOME ] && [ "$cur" = $4 ]; }; then
        pkg_ok "$saved" || saved=$4
        [ "$cur" = "$saved" ] || give $2 "$saved" || return 1
    else
        say "  $2 kept: $cur, chosen later"
    fi
    rm -f "$f"
}
back_dialer() { back_role dialer DIALER $PHONE $GOOGLE_DIALER; }
back_home() { back_role home HOME $L $LAUNCHER3; }
# Typing: bold7_saisie deleted, then the input method saved, unless the user chose another one than
# Typing and BBKB (which Android puts in Typing's place at each boot, before the storage opens).
back_saisie() {
    local f cur ime list
    "$TOOLS/settings" delete global bold7_saisie >/dev/null 2>&1
    f=$S/saisie-avant.txt
    cur=$(get secure default_input_method)
    if [ ! -e "$f" ]; then
        [ "$cur" != "$SAISIE_IME" ] || { "$TOOLS/ime" set $BBKB >/dev/null 2>&1; say "  input method $BBKB"; }
        return 0
    fi
    ime=$(sed -n 's/^default_input_method=//p' "$f")
    list=$(sed -n 's/^enabled_input_methods=//p' "$f")
    if [ "$(lines "$f")" != 2 ] || [ -z "$ime" ] || [ "$ime" = null ]; then
        broken "$f"
        [ "$cur" != "$SAISIE_IME" ] || "$TOOLS/ime" set $BBKB >/dev/null 2>&1
        return 1
    fi
    case $cur in
    "$SAISIE_IME" | "$BBKB" | null | '')
        [ "$list" = null ] || [ -z "$list" ] || put secure enabled_input_methods "$list" || return 1
        "$TOOLS/ime" set "$ime" >/dev/null 2>&1
        [ "$(get secure default_input_method)" = "$ime" ] || { say "! input method not back to $ime"; return 1; }
        say "  input method back to $ime"
        ;;
    *) say "  input method kept: $cur, chosen later" ;;
    esac
    rm -f "$f"
}
# The Clock's alarm leaves with it, said; an alarm volume a ring cut short left is given back.
clock() {
    local x rep v
    x=$USER_DE/$CLOCK/shared_prefs/clock.xml
    [ -e "$x" ] || return 0
    value() { sed -n "s/.*name=\"$1\"[^>]*>\\([^<]*\\)<.*/\\1/p; s/.*name=\"$1\" value=\"\\([^\"]*\\)\".*/\\1/p" "$x" | head -n 1; }
    rep=$(value repeat)
    [ -z "$rep" ] || [ "$rep" = OFF ] || say "  the alarm of Bold7 leaves with its Clock: $(value hour):$(value minute) $rep"
    v=$(value saved_volume)
    [ -z "$v" ] || "$TOOLS/cmd" media_session volume --stream 4 --set "$v" >/dev/null 2>&1
}
# A sound profile may have left its Do Not Disturb policy in place of the user's: only
# the launcher could give it back, and it is gone. Do Not Disturb is turned off, the policy kept in
# $S for the user to set by hand.
dnd() {
    local x p
    x=$S/profiles.xml
    [ -e "$x" ] || x=$USER_DE/$L/shared_prefs/profiles.xml
    p=$(sed -n 's/.*name="dnd_policy">\([^<]*\)<.*/\1/p' "$x" 2>/dev/null)
    rm -f "$S/profiles.xml"
    [ -n "$p" ] || return 0
    "$TOOLS/cmd" notification set_dnd off >/dev/null 2>&1
    echo "$p" >"$S/dnd-policy-avant.txt"
    say "! a profile's Do Not Disturb policy stood in for yours ($p, $S/dnd-policy-avant.txt): Do Not Disturb turned off; set its people and apps again in Android's Settings, Sound, Do Not Disturb"
    return 1
}
# SystemUI's palette overlays outlive the module: disabled while OS 7's colors (or a
# BB10 install's) are still in them.
palette() {
    local a g o
    a=$("$TOOLS/cmd" overlay lookup android android:color/system_accent1_600 2>/dev/null)
    g=$("$TOOLS/cmd" overlay lookup android android:color/system_neutral1_900 2>/dev/null)
    case "$a $g" in "#ff088eef #ff1e2125" | "#ff0092cc #ff282828") ;; *) return 0 ;; esac
    for o in neutral accent dynamic; do "$TOOLS/cmd" overlay disable com.android.systemui:$o >/dev/null 2>&1; done
    say "  SystemUI's palette overlays disabled"
}
uninstall_apps() {
    local r p
    r=0
    for p in $MESSAGES $APPS $L; do
        pkg_ok $p || continue
        case $p in
        "$HUB")
            "$TOOLS/cmd" notification disallow_listener $HUB_LISTENER >/dev/null 2>&1
            "$TOOLS/dumpsys" deviceidle whitelist -$HUB >/dev/null 2>&1
            ;;
        # Only an update left in /data: never the priv-app of a module still there.
        "$L") case $("$TOOLS/pm" path $L) in package:/data/*) ;; *) continue ;; esac ;;
        esac
        "$TOOLS/pm" uninstall $p >/dev/null 2>&1
        if pkg_ok $p; then say "! could not uninstall $p"; r=1; else say "  $p uninstalled"; fi
    done
    return $r
}
do_restore() {
    local r i
    wait_ce || return 0
    say "Bold7 removed: giving back what it changed ($(date))"
    r=0
    for i in $ITEMS; do
        back_$i || r=1
        rm -f "$S/$i.fait"
    done
    clock
    dnd || r=1
    "$TOOLS/settings" list global 2>/dev/null | sed -n 's/^\(bold7_[A-Za-z0-9_]*\)=.*/\1/p' | while read -r k; do
        "$TOOLS/settings" delete global "$k" >/dev/null 2>&1
    done
    palette
    uninstall_apps || r=1
    [ "$(holder DIALER)" != $PHONE ] || { say "! DIALER still held by $PHONE"; r=1; }
    [ "$(holder HOME)" != $L ] || { say "! HOME still held by $L"; r=1; }
    rm -f "$WRAPPER"
    rm -f "$S/acces.fait" "$S/vector.installe" "$S/setup.sh"
    if [ $r = 0 ]; then
        rm -rf "$S"
        say "Bold7 removed: everything given back"
    else
        say "! Bold7 removed, but not everything given back (above): what is left is in $S"
    fi
}

cmd=$1
shift
case $cmd in
check | save | install | apply | access | restore) "do_$cmd" "$@" ;;
*) die "usage: setup.sh check|save|install|apply|access <moddir> | restore" ;;
esac
