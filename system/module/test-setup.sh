#!/usr/bin/env bash
# Runs the module's installer and remover (setup.sh check, save, install, apply, access, restore,
# and uninstall.sh) with the phone's own sh, as the shell user, in a scratch folder of
# /data/local/tmp, against fakes: settings, pm, cmd, ime, content, appops, dumpsys, am, wm, getprop,
# resetprop, ksud and Vector's cli read and write files of that folder, a made-up MediaStore table
# included. Only the stock keylayout of the phone is read (as root), to make the module's from it.
# Nothing else is touched: the real settings that setup.sh writes, the roles and the input method
# are read before and after, and must not have moved. Exits non-zero on failure.
#   system/module/test-setup.sh             the phone's sh (mksh), as the shell user
#   BUSYBOX=1 system/module/test-setup.sh   KernelSU's busybox ash, as root, as the module runs
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-setup-test
# Every tool by its path ($TOOLS): a bare call would reach the real one.
if grep -vE '^[[:space:]]*#' src/setup.sh src/uninstall.sh | grep -E '(^|[;&|]|then|do|else)[[:space:]]*(settings|pm|cmd|ime|content|appops|dumpsys|am|wm|getprop|pidof|resetprop|ksud)[[:space:]]'; then
  echo "FAIL a tool called without \$TOOLS (above)"; exit 1
fi
real() {
  adb shell "for n in global secure system; do settings list \$n; done | grep -E '^(bold7_|end_button_behavior|charging_|lockscreen_sounds|sound_effects|accelerometer_rotation|clipboard_show|ringtone|notification_sound|alarm_alert|default_input_method|enabled_input_methods|enabled_accessibility|enabled_notification_listeners|display_density_forced)'; wm density; device_config get privacy camera_mic_icons_enabled; device_config get privacy location_indicators_enabled; cmd role get-role-holders android.app.role.HOME; cmd role get-role-holders android.app.role.DIALER; cmd locale get-app-locales com.android.permissioncontroller --user 0" | tr -d '\r'
}
before=$(real)
adb shell "rm -rf $T && mkdir -p $T/mod/layer/product/usr/keylayout"
adb push src/setup.sh src/uninstall.sh src/saisie-start.sh src/lift.sh $T/mod/ >/dev/null
adb push src/layer/product/usr/keylayout/Q25_keyboard.kl $T/mod/layer/product/usr/keylayout/ >/dev/null
adb shell "su -c 'cat /vendor/usr/keylayout/Q25_keyboard.kl > $T/stock.kl && chmod 644 $T/stock.kl'"
adb shell "cat > $T/run.sh" <<'EOF'
T=$1 M=$1/mod F=$1/f
export FAKE=$F TOOLS=$F/bin ADB_DIR=$T/adb KSUD=$F/bin/ksud RESETPROP=$F/bin/resetprop ROOT=$T/root SU=$F/bin/su
export USER_DE=$T/user_de TRACKPAD_MODE=$F/mode KL=$F/stock.kl MAX_WAIT=2 PAUSE=0
# saisie-start.sh's own fakes, and PATH for its bare log.
export GETPROP=$F/bin/getprop SETTINGS=$F/bin/settings IME=$F/bin/ime PM=$F/bin/pm TRIES=2 RETRY_SLEEP=0
export PATH=$F/bin:$PATH
S=$T/adb/bold7-state
LATIN=com.android.inputmethod.latin/.LatinIME
TTS=com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
A=/product/media/audio
bad=0
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2', expected '$3'"; bad=1; fi; }
setup() { sh $M/setup.sh "$@" >>$T/out 2>&1; echo $?; }
g() { cat $F/db/$1/$2 2>/dev/null || echo null; }
calls() { grep "$1" $F/calls 2>/dev/null | tr '\n' ' '; }

mkdir -p $F/bin
cd $F/bin
# Like system_server under SELinux: a file of the phone handed as a file descriptor (the log, a save
# read on stdin) makes the call fail, as does each count of $FAKE/sfail (the service not answering
# yet), printing "cmd: Failure calling service ..." on its output.
cat > fail <<'X'
n=$(cat $FAKE/sfail 2>/dev/null || echo 0)
if [ -f /proc/$PPID/fd/0 ] || [ -f /proc/$PPID/fd/1 ] || [ -f /proc/$PPID/fd/2 ] || [ "$n" -gt 0 ]; then
  [ "$n" -gt 0 ] && echo $((n - 1)) > $FAKE/sfail
  echo "cmd: Failure calling service $1: Failed transaction (2147483646)"; exit 0
fi
exit 1
X
cat > settings <<'X'
sh $FAKE/bin/fail settings && exit 255
d=$FAKE/db/$2
case $1 in
get) cat "$d/$3" 2>/dev/null || echo null ;;
put) # Android 17 ignores a sound's media URI from root (isValidMediaUri): only the shell's uid sets it.
  case "$3 $4" in ringtone\ content:* | notification_sound\ content:* | alarm_alert\ content:*) [ "$FAKE_UID" = 2000 ] || exit 0 ;; esac
  mkdir -p "$d"; echo "$4" > "$d/$3" ;;
delete) rm -f "$d/$3" ;;
list) for x in "$d"/*; do [ -e "$x" ] && echo "${x##*/}=$(cat "$x")"; done; true ;;
esac
X
# device_config of the privacy namespace: get prints null when unset.
cat > device_config <<'X'
sh $FAKE/bin/fail device_config && exit 255
d=$FAKE/db/dc-$2
case $1 in
get) cat "$d/$3" 2>/dev/null || echo null ;;
put) mkdir -p "$d"; echo "$4" > "$d/$3" ;;
delete) rm -f "$d/$3" ;;
esac
X
# KernelSU's su: su <uid> -c <command>, the environment kept.
cat > su <<'X'
[ "$1" = 2000 ] && [ "$2" = -c ] || exit 1
FAKE_UID=2000 sh -c "$3"
X
cat > pm <<'X'
case $1 in
path) [ -e $FAKE/pkg/$2 ] && echo "package:$(cat $FAKE/pkg/$2)"; true ;;
install) p=${3##*/}; p=${p%.apk}; echo "install $p" >> $FAKE/calls
  if [ -e $FAKE/fail-$p ]; then echo "Failure [INSTALL_FAILED]"; exit 1; fi
  echo /data/app/$p/base.apk > $FAKE/pkg/$p; echo Success ;;
uninstall) echo "uninstall $2" >> $FAKE/calls; rm -f $FAKE/pkg/$2; echo Success ;;
uninstall-system-updates) echo "usu $2" >> $FAKE/calls
  [ -e $FAKE/system-$2 ] || { echo "java.lang.NullPointerException: Attempt to invoke virtual method 'boolean android.content.pm.ApplicationInfo.isUpdatedSystemApp()' on a null object reference"; exit 255; }
  echo /system/priv-app/Bold7Launcher/Bold7Launcher.apk > $FAKE/pkg/$2; echo Success; exit 1 ;;
grant) echo "$3" >> $FAKE/granted-$2 ;;
list) for x in $FAKE/pkg/*; do n=${x##*/}; [ "$3" != -s ] || [ -e $FAKE/system-$n ] || continue; case $n in $4*) echo package:$n ;; esac; done ;;
esac
X
cat > dumpsys <<'X'
case $1 in
package) echo "    requested permissions:"
  for x in $(cat $FAKE/req-$2 2>/dev/null); do echo "      android.permission.$x"; done
  echo "    runtime permissions:"
  for x in $(cat $FAKE/granted-$2 2>/dev/null); do [ -e $FAKE/refuse-$x ] || echo "      $x: granted=true"; done ;;
deviceidle) case $3 in +*) echo "${3#+}" >> $FAKE/doze ;; -*) echo "doze -${3#-}" >> $FAKE/calls ;;
  '') [ -e $FAKE/doze ] && while read x; do echo "user,$x,10123"; done < $FAKE/doze; true ;; esac ;;
notification) echo "  Allowed condition providers:"; [ -e $FAKE/dnd ] && echo "    $(cat $FAKE/dnd) (user: 0 isPrimary: true)"; true ;;
esac
X
cat > cmd <<'X'
sh $FAKE/bin/fail $1 && exit 255
case "$1 $2" in
"role get-role-holders") cat $FAKE/role-$3 2>/dev/null; true ;;
"role add-role-holder") if [ -e $FAKE/pkg/$4 ]; then echo $4 > $FAKE/role-$3; else echo "java.lang.IllegalArgumentException: $4"; fi ;;
"notification allow_listener") l=$(cat $FAKE/db/secure/enabled_notification_listeners 2>/dev/null); mkdir -p $FAKE/db/secure
  echo "${l:+$l:}$3" > $FAKE/db/secure/enabled_notification_listeners ;;
"notification allow_dnd") echo $3 > $FAKE/dnd ;;
"locale get-app-locales") echo "Locales for $3 for user 0 are [$(cat $FAKE/locale-$3 2>/dev/null)]" ;;
"locale set-app-locales") v=; [ "$6" = --locales ] && v=$7; echo "$v" > $FAKE/locale-$3 ;;
"overlay lookup") case $4 in *accent1_600) cat $FAKE/accent 2>/dev/null ;; *neutral1_900) cat $FAKE/grey 2>/dev/null ;; esac; true ;;
*) echo "cmd $*" >> $FAKE/calls ;;
esac
X
cat > ime <<'X'
echo "ime $*" >> $FAKE/calls
[ "$1" = set ] && { mkdir -p $FAKE/db/secure; echo "$2" > $FAKE/db/secure/default_input_method; }; true
X
cat > content <<'X'
case $1 in
query) vol=${3#content://media/}; vol=${vol%%/*}
  case $7 in
  _id=*) awk -F'|' -v v="$vol" -v i="${7#_id=}" '$1 == v && $2 == i { print "Row: 0 _data=" $3 }' $FAKE/media ;;
  _data=*) p=${7#_data=\'}; p=${p%\'}; awk -F'|' -v v="$vol" -v p="$p" '$1 == v && $3 == p { print "Row: 0 _id=" $2 ", title=" $4 }' $FAKE/media ;;
  esac ;;
call) echo scan >> $FAKE/calls
  # A scan lists the files there are: the rows of the table whose file exists, plus media-new's.
  cat $FAKE/media $FAKE/media-new 2>/dev/null | while IFS='|' read v i p t; do [ -e "$ROOT$p" ] && echo "$v|$i|$p|$t"; done | sort -u > $FAKE/media.n
  mv $FAKE/media.n $FAKE/media ;;
esac
X
cat > appops <<'X'
case $1 in
set) [ -e $FAKE/refuse-$3 ] || echo $4 > $FAKE/op-$2-$3 ;;
get) if [ -e $FAKE/op-$2-$3 ]; then echo "$3: $(cat $FAKE/op-$2-$3)"; else echo "No operations."; fi ;;
esac
X
echo 'echo "am $*" >> $FAKE/calls' > am
# wm density writes display_density_forced, its reset an empty value (WindowManagerService).
cat > wm <<'X'
echo "wm $*" >> $FAKE/calls; mkdir -p $FAKE/db/secure
case $2 in reset) echo > $FAKE/db/secure/display_density_forced ;; *) echo $2 > $FAKE/db/secure/display_density_forced ;; esac
X
echo 'cat $FAKE/prop-$1 2>/dev/null; true' > getprop
echo 'sleep 1' > resetprop
echo 'echo 4242' > pidof
echo 'echo "kill $*" >> $FAKE/calls' > kill
echo 'true' > log
cat > ksud <<'X'
echo "ksud $*" >> $FAKE/calls
[ -e $FAKE/fail-ksud ] && { echo "! refused"; exit 3; }
echo "- Vector installed"
X
cat > vector-cli <<'X'
case "$1 $2" in
"scope ls") cat $FAKE/scope 2>/dev/null; true ;;
"scope set") echo "vector scope set $3 $4 $5" >> $FAKE/calls
  printf 'com.android.systemui               0\ncom.google.android.apps.messaging  0\n' > $FAKE/scope ;;
"modules enable") echo "$3  10205  enabled" > $FAKE/vmods; echo "vector enable $3" >> $FAKE/calls ;;
"modules ls") cat $FAKE/vmods 2>/dev/null; true ;;
esac
X
chmod 755 *
cd $T

# A stock Q25 under LineageOS 24 with NeoZygisk, before Bold7, as far as setup.sh looks.
stock() {
  rm -rf $F/sfail $F/system-* $F/db $F/pkg $F/calls $F/prop-* $F/role-* $F/req-* $F/granted-* $F/op-* $F/doze $F/dnd $F/scope $F/vmods $F/fail-* $F/refuse-* $F/media* $F/accent $F/grey $F/locale-* $T/adb $T/root $T/user_de $T/out
  mkdir -p $F/pkg $T/adb/modules/zygisksu $T/root$A/notifications $T/root$A/alarms $T/root$A/ringtones $T/root/storage/emulated/0/Ringtones
  echo Q25 > $F/prop-ro.product.device; echo CP2A.260605.016 > $F/prop-ro.build.display.id
  echo Q25 > $F/prop-ro.lineage.device; echo 24.0 > $F/prop-ro.lineage.build.version
  echo 24.0-20261115-NIGHTLY-Q25 > $F/prop-ro.lineage.version; echo true > $F/prop-sys.user.0.ce_available
  for p in com.android.launcher3 com.android.dialer com.google.android.apps.messaging; do echo /system/app/$p.apk > $F/pkg/$p; done
  echo /apex/com.android.permission/priv-app/PermissionController.apk > $F/pkg/com.android.permissioncontroller
  echo com.android.launcher3 > $F/role-android.app.role.HOME; echo com.android.dialer > $F/role-android.app.role.DIALER
  mkdir -p $F/db/global $F/db/secure $F/db/system
  echo 1 > $F/db/global/charging_sounds_enabled; echo 1 > $F/db/secure/charging_vibration_enabled
  echo 1 > $F/db/system/lockscreen_sounds_enabled; echo 0 > $F/db/system/sound_effects_enabled
  echo 1 > $F/db/system/accelerometer_rotation; echo 2 > $F/db/system/end_button_behavior
  mkdir -p $F/db/dc-privacy; echo true > $F/db/dc-privacy/location_indicators_enabled
  echo $LATIN > $F/db/secure/default_input_method; echo "$TTS:$LATIN" > $F/db/secure/enabled_input_methods
  # The ringtone is the user's own, on /sdcard; the notification and the alarm Android's.
  echo 'content://media/external_primary/audio/media/77' > $F/db/system/ringtone
  echo 'content://media/internal/audio/media/5?title=Pixie%20Dust&canonical=1' > $F/db/system/notification_sound
  echo 'content://media/internal/audio/media/6' > $F/db/system/alarm_alert
  printf '%s\n' "internal|5|$A/notifications/PixieDust.ogg|Pixie Dust" "internal|6|$A/alarms/Oxygen.ogg|Oxygen" \
    "external_primary|77|/storage/emulated/0/Ringtones/Spirit.m4a|Spirit (BBOS 7)" > $F/media
  printf '%s\n' "internal|101|$A/ringtones/Spirit.m4a|Spirit" "internal|102|$A/notifications/SanguineRemix.m4a|Sanguine Remix" \
    "internal|103|$A/alarms/Dream.m4a|Dream" > $F/media-new
  touch $T/root$A/notifications/PixieDust.ogg $T/root$A/alarms/Oxygen.ogg $T/root/storage/emulated/0/Ringtones/Spirit.m4a
  echo 1 > $F/mode
  echo "READ_CONTACTS READ_SMS BLUETOOTH_CONNECT" > $F/req-dev.bold7.launcher
  echo "READ_SMS READ_CALL_LOG" > $F/req-dev.bold7.hub
  echo "READ_SMS READ_MEDIA_IMAGES" > $F/req-dev.bold7.messages
  echo "CALL_PHONE POST_NOTIFICATIONS" > $F/req-dev.bold7.phone
  echo "POST_NOTIFICATIONS" > $F/req-dev.bold7.clock
  echo "CAMERA RECORD_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VIDEO" > $F/req-dev.bold7.camera.benos
  # The zip as the installer unpacked it.
  rm -rf $M/apps $M/vector $M/layer/product/media
  mkdir -p $M/apps $M/vector $M/layer$A/ringtones $M/layer$A/notifications $M/layer$A/alarms
  for p in hub phone messages saisie clock calendar contacts calculator flashlight memopad tasks pictures help sysui camera.benos; do touch $M/apps/dev.bold7.$p.apk; done
  touch $M/vector/vector.zip $M/layer$A/ringtones/Spirit.m4a $M/layer$A/notifications/SanguineRemix.m4a $M/layer$A/alarms/Dream.m4a
  cp $T/stock.kl $F/stock.kl
}
# The module mounted after the reboot: its sounds there, the launcher a priv-app, Vector running.
boot() {
  touch $T/root$A/ringtones/Spirit.m4a $T/root$A/notifications/SanguineRemix.m4a $T/root$A/alarms/Dream.m4a
  echo /system/priv-app/Bold7Launcher/Bold7Launcher.apk > $F/pkg/dev.bold7.launcher; touch $F/system-dev.bold7.launcher
  mkdir -p $T/adb/modules/zygisk_vector; cp $F/bin/vector-cli $T/adb/modules/zygisk_vector/cli
}

echo "--- check"
stock
expect "the stock keylayout is the one the module's copy was made from" "$(setup check $M)" 0
expect "said in one line" "$(tail -n 1 $T/out)" "OK, Zinwa Q25 on LineageOS 24.0-20261115-NIGHTLY-Q25"
echo Q20 > $F/prop-ro.product.device
expect "another phone is refused" "$(setup check $M)" 1
stock; echo BenOS-Valarie_stablePublicRel > $F/prop-ro.build.display.id; rm $F/prop-ro.lineage.*
expect "BenOS is refused, said" "$(setup check $M) $(grep -c 'Install Bold7 for BenOS' $T/out)" "1 1"
stock; echo 23.2 > $F/prop-ro.lineage.build.version
expect "another LineageOS is refused" "$(setup check $M)" 1
stock; rm $F/prop-ro.lineage.device
expect "a ROM that is not LineageOS is refused" "$(setup check $M)" 1
stock; touch $T/adb/metamodule
expect "a metamodule is refused" "$(setup check $M)" 1
stock; mkdir -p $T/adb/modules/hybrid_mount; echo "name=Hybrid Mount" > $T/adb/modules/hybrid_mount/module.prop; ln -s $T/adb/modules/hybrid_mount $T/adb/metamodule
expect "a metamodule is refused, named" "$(setup check $M) $(grep -c 'metamodule, Hybrid Mount' $T/out)" "1 1"
stock; mkdir -p $T/adb/modules/bb10launcher_quickstep
expect "JL10 QuickStep on is refused" "$(setup check $M) $(grep -c 'JL10 QuickStep is on' $T/out)" "1 1"
touch $T/adb/modules/bb10launcher_quickstep/disable
expect "JL10 QuickStep off is taken" "$(setup check $M)" 0
stock; mkdir -p $T/adb/modules_update/cascade
expect "the BB10 module waiting is refused" "$(setup check $M)" 1
stock; echo x > $F/pkg/dev.cascade.hub
expect "a BB10 package is refused" "$(setup check $M)" 1
stock; mkdir -p $T/adb/boot-completed.d; touch $T/adb/boot-completed.d/bold7-retour.sh
expect "a removal not finished is refused" "$(setup check $M)" 1
stock; echo "key 999 X" >> $F/stock.kl
expect "another stock keylayout is taken" "$(setup check $M)" 0
expect "the module's keylayout is it, End as ENDCALL" "$(sed 's/^key 172   ENDCALL$/key 172   HOME/' $M/layer/product/usr/keylayout/Q25_keyboard.kl | cmp -s - $F/stock.kl && grep -c '^key 172   ENDCALL$' $M/layer/product/usr/keylayout/Q25_keyboard.kl)" 1
expect "a refusal changes nothing" "$(ls $T/adb | tr '\n' ' ')" "modules "

echo "--- save"
stock
expect "save" "$(setup save $M)" 0
expect "silence: the settings not at 0" "$(cat $S/silence-avant.txt | tr '\n' ';')" "charging_sounds_enabled 1;charging_vibration_enabled 1;lockscreen_sounds_enabled 1;accelerometer_rotation 1;clipboard_show_access_notifications null;"
expect "touches" "$(cat $S/touches-avant.txt)" 2
expect "sons" "$(cut -f1 $S/sons-avant.txt | tr '\n' ' ')" "ringtone notification_sound alarm_alert "
expect "sons: the value as is" "$(grep notification_sound $S/sons-avant.txt | cut -f2)" 'content://media/internal/audio/media/5?title=Pixie%20Dust&canonical=1'
expect "dialer, home" "$(cat $S/dialer-avant.txt) $(cat $S/home-avant.txt)" "com.android.dialer com.android.launcher3"
expect "saisie" "$(cat $S/saisie-avant.txt | tr '\n' ';')" "default_input_method=$LATIN;enabled_input_methods=$TTS:$LATIN;"
expect "densite: no forced density" "$(cat $S/densite-avant.txt)" null
expect "prive: unset and true kept as they are" "$(cat $S/prive-avant.txt | tr '\n' ';')" "camera_mic_icons_enabled null;location_indicators_enabled true;"
expect "langue: PermissionController in the phone's language" "$(cat $S/langue-avant.txt)" ""
expect "nothing set yet" "$(ls $S | grep -c fait)" 0
echo 5 > $F/db/system/end_button_behavior
setup save $M >/dev/null
expect "a save is never overwritten" "$(cat $S/touches-avant.txt)" 2
stock; echo 1 > $F/db/system/end_button_behavior; echo dev.bold7.phone > $F/role-android.app.role.DIALER
echo 208 > $F/db/secure/display_density_forced; echo en-GB > $F/locale-com.android.permissioncontroller
echo 'content://media/internal/audio/media/102' > $F/db/system/notification_sound; echo "internal|102|$A/notifications/SanguineRemix.m4a|Sanguine Remix" >> $F/media
setup save $M >/dev/null
expect "refused: End already home, DIALER already ours, density 208 already, PermissionController in English already, a sound of Bold7: nothing saved, marked" \
  "$(ls $S | tr '\n' ' ')" "densite.fait dialer.fait home-avant.txt langue.fait prive-avant.txt saisie-avant.txt silence-avant.txt sons.fait touches.fait "

echo "--- install"
stock; setup save $M >/dev/null
echo /data/app/dev.bold7.launcher/base.apk > $F/pkg/dev.bold7.launcher; touch $F/system-dev.bold7.launcher
touch $S/acces.fait
expect "install" "$(setup install $M)" 0
expect "the launcher's update over its priv-app removed" "$(calls usu)" "usu dev.bold7.launcher "
expect "Vector installed from the module's folder" "$(calls ksud)" "ksud module install $M/vector/vector.zip "
expect "and marked as Bold7's" "$([ -e $S/vector.installe ] && echo yes)" yes
expect "the apps, Messages after the Hub and the Phone" "$(calls '^install' | sed 's/install //g')" \
  "dev.bold7.hub dev.bold7.phone dev.bold7.messages dev.bold7.saisie dev.bold7.clock dev.bold7.calendar dev.bold7.contacts dev.bold7.calculator dev.bold7.flashlight dev.bold7.memopad dev.bold7.tasks dev.bold7.pictures dev.bold7.help dev.bold7.sysui dev.bold7.camera.benos "
expect "Camera marked as Bold7's" "$([ -e $S/camera.installe ] && echo yes)" yes
expect "the zip's copies removed, the access to give again" "$(ls $M | tr '\n' ' ')$([ -e $S/acces.fait ] || echo again)" "layer lift.sh saisie-start.sh setup.sh uninstall.sh again"
expect "NeoZygisk there: nothing said" "$(grep -c 'No Zygisk' $T/out)" 0
stock; setup save $M >/dev/null; rm -r $T/adb/modules/zygisksu
expect "no Zygisk: installed all the same, said as a step not done" "$(setup install $M) $(grep -c '^! No Zygisk module (NeoZygisk)' $T/out)" "0 1"
stock; setup save $M >/dev/null; echo /data/app/dev.bold7.launcher/base.apk > $F/pkg/dev.bold7.launcher
expect "a launcher of /data that is not a system app: left as it is, said" "$(setup install $M) $(calls usu)$(calls 'uninstall dev.bold7.launcher') $(grep -c 'installed as an ordinary app' $T/out) $(cat $F/pkg/dev.bold7.launcher)" "0  1 /data/app/dev.bold7.launcher/base.apk"
# The next boot: the priv-app mounted, Android takes the copy of /data for its update.
boot; echo /data/app/dev.bold7.launcher/base.apk > $F/pkg/dev.bold7.launcher; : > $F/calls
expect "apply: that update removed, its data kept" "$(setup apply $M) $(calls usu)$(calls 'uninstall dev.bold7.launcher') $(cat $F/pkg/dev.bold7.launcher)" "0 usu dev.bold7.launcher  /system/priv-app/Bold7Launcher/Bold7Launcher.apk"
rm -f $F/system-dev.bold7.launcher; echo /data/app/dev.bold7.launcher/base.apk > $F/pkg/dev.bold7.launcher; : > $F/calls
expect "apply: a launcher of /data that is not a system app never uninstalled" "$(setup apply $M) $(calls usu)$(calls 'uninstall dev.bold7.launcher') $(cat $F/pkg/dev.bold7.launcher)" "0  /data/app/dev.bold7.launcher/base.apk"
stock; setup save $M >/dev/null; mkdir -p $T/adb/modules/zygisk_vector; touch $F/fail-dev.bold7.tasks
setup install $M >/dev/null
expect "Vector there: left alone, not Bold7's" "$(calls ksud)$([ -e $S/vector.installe ] && echo marked)" ""
expect "an app that fails does not stop the others" "$(grep -c '^install' $F/calls) $(ls $F/pkg | grep -c dev.bold7)" "15 14"
stock; setup save $M >/dev/null; setup install $M >/dev/null; rm -f $F/calls; mkdir -p $M/apps; touch $M/apps/dev.bold7.camera.benos.apk
setup install $M >/dev/null
expect "Bold7's Camera updated by the next install" "$(calls 'install dev.bold7.camera.benos')" "install dev.bold7.camera.benos "
stock; setup save $M >/dev/null; echo /data/app/user-camera/base.apk > $F/pkg/dev.bold7.camera.benos
setup install $M >/dev/null
expect "a Camera the user installed: left as it is, not Bold7's, said" "$(calls 'install dev.bold7.camera.benos')$([ -e $S/camera.installe ] && echo marked) $(grep -c 'not by Bold7' $T/out)" " 1"
boot; setup apply $M >/dev/null; ADB_DIR=$T/adb sh $M/uninstall.sh >>$T/out 2>&1; rm -f $F/pkg/dev.bold7.launcher
sh $S/setup.sh restore >>$T/out 2>&1
expect "and kept at removal, the other apps gone" "$(cat $F/pkg/dev.bold7.camera.benos) $(ls $F/pkg | grep -c dev.bold7)" "/data/app/user-camera/base.apk 1"
stock; setup save $M >/dev/null; mkdir -p $T/adb/modules/zygisk_vector; touch $T/adb/modules/zygisk_vector/remove
setup install $M >/dev/null
expect "Vector marked for removal is installed again" "$(calls ksud)" "ksud module install $M/vector/vector.zip "

echo "--- apply"
stock; setup save $M >/dev/null; setup install $M >/dev/null; rm -f $F/calls
echo false > $F/prop-sys.user.0.ce_available
expect "storage closed: nothing set" "$(setup apply $M) $(ls $S | grep -c fait)" "0 0"
echo true > $F/prop-sys.user.0.ce_available; boot
expect "apply" "$(setup apply $M)" 0
expect "silence: all at 0" "$(g global charging_sounds_enabled)$(g secure charging_vibration_enabled)$(g system lockscreen_sounds_enabled)$(g system sound_effects_enabled)$(g system accelerometer_rotation)$(g secure clipboard_show_access_notifications)" 000000
expect "End goes home" "$(g system end_button_behavior)" 1
expect "MediaStore scanned for the module's sounds" "$(calls scan)" "scan "
expect "the user's ringtone kept" "$(g system ringtone)" 'content://media/external_primary/audio/media/77'
expect "notification: Sanguine Remix" "$(g system notification_sound)" 'content://media/internal/audio/media/102?title=Sanguine%20Remix&canonical=1'
expect "alarm: Dream" "$(g system alarm_alert)" 'content://media/internal/audio/media/103?title=Dream&canonical=1'
expect "density 208, by wm" "$(g secure display_density_forced) $(calls 'wm density')" "208 wm density 208 "
expect "privacy indicators off" "$(g dc-privacy camera_mic_icons_enabled) $(g dc-privacy location_indicators_enabled)" "false false"
expect "HOME and DIALER Bold7's" "$(cat $F/role-android.app.role.HOME) $(cat $F/role-android.app.role.DIALER)" "dev.bold7.launcher dev.bold7.phone"
expect "home shown" "$(calls 'category.HOME' | cut -c1-8)" "am start"
expect "Typing selected, kept after each boot" "$(g secure default_input_method) $(g global bold7_saisie)" "dev.bold7.saisie/.SaisieService 1"
expect "permissions asked and granted, SMS exempted first" "$(cat $F/granted-dev.bold7.hub | tr '\n' ' ')$(calls ExemptRestricted | grep -c dev.bold7.hub)" "android.permission.READ_SMS android.permission.READ_CALL_LOG 1"
expect "Camera: its permissions" "$(cat $F/granted-dev.bold7.camera.benos | tr '\n' ' ')" "android.permission.CAMERA android.permission.RECORD_AUDIO android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO "
expect "Messages: Google Messages' API" "$(grep -c EXTERNAL_MESSAGING_API $F/granted-dev.bold7.messages)" 1
expect "Hub: notification access and Doze" "$(g secure enabled_notification_listeners) $(cat $F/doze)" "dev.bold7.hub/dev.bold7.hub.HubListener dev.bold7.hub"
expect "launcher: Do Not Disturb access" "$(cat $F/dnd)" dev.bold7.launcher
expect "app-ops" "$(cat $F/op-dev.bold7.phone-USE_FULL_SCREEN_INTENT $F/op-dev.bold7.clock-SYSTEM_ALERT_WINDOW $F/op-dev.bold7.pictures-MANAGE_MEDIA $F/op-dev.bold7.launcher-MANAGE_EXTERNAL_STORAGE | tr '\n' ' ')" "allow allow allow allow "
expect "Vector: scoped, SystemUI restarted" "$(calls vector) $(calls kill)" "vector enable dev.bold7.sysui vector scope set dev.bold7.sysui com.android.systemui/0 com.google.android.apps.messaging/0  kill 4242 am kill com.google.android.apps.messaging "
expect "PermissionController in British English" "$(cat $F/locale-com.android.permissioncontroller)" en-GB
expect "everything marked" "$(ls $S | grep -c fait)" 10
# The user's later choices stand: nothing is set twice.
echo 3 > $F/db/system/end_button_behavior; echo 2 > $F/db/system/lockscreen_sounds_enabled; echo dev.bold7.essai > $F/pkg/dev.bold7.essai
echo dev.bold7.essai > $F/role-android.app.role.HOME; : > $F/calls
expect "next boot: nothing to do" "$(setup apply $M) $(g system end_button_behavior) $(cat $F/role-android.app.role.HOME) $(cat $F/calls)" "0 3 dev.bold7.essai "
echo dev.bold7.launcher > $F/role-android.app.role.HOME
# access again (tools/install.sh --permissions): Vector scoped already, no restart.
expect "access again, nothing restarted" "$(setup access $M) $(calls kill)" "0 "
stock; setup save $M >/dev/null; setup install $M >/dev/null; boot; rm -rf $T/adb/modules/zygisk_vector; touch $F/refuse-android.permission.CALL_PHONE
expect "a failure: retried at the next boot, said on the module's card" "$(setup apply $M) $([ -e $S/acces.fait ] || echo again) $(calls override.description | cut -c1-46)" "1 again ksud module config set -t override.description"

echo "--- uninstall.sh and restore"
stock; setup save $M >/dev/null; setup install $M >/dev/null; boot; setup apply $M >/dev/null; rm -f $F/calls
mkdir -p $T/user_de/dev.bold7.clock/shared_prefs
printf '%s\n' '<map>' '<int name="hour" value="7" />' '<int name="minute" value="30" />' '<string name="repeat">WEEKDAYS</string>' '<int name="saved_volume" value="4" />' '</map>' > $T/user_de/dev.bold7.clock/shared_prefs/clock.xml
echo 1 > $F/db/global/bold7_hub_demo; echo 1 > $F/db/global/bold7_showcase; echo 0 > $F/mode
# Later choices: End and the lock sound chosen again, which stand.
echo 3 > $F/db/system/end_button_behavior; echo 2 > $F/db/system/lockscreen_sounds_enabled
expect "uninstall.sh" "$(ADB_DIR=$T/adb sh $M/uninstall.sh >>$T/out 2>&1; echo $?)" 0
expect "the restore script left, executable" "$([ -x $T/adb/boot-completed.d/bold7-retour.sh ] && echo yes) $(grep -c '^setsid sh .*setup.sh restore' $T/adb/boot-completed.d/bold7-retour.sh)" "yes 1"
expect "with its logic beside the saves" "$(cmp -s $M/setup.sh $S/setup.sh && echo same)" same
expect "Vector, installed by Bold7, marked for removal" "$([ -e $T/adb/modules/zygisk_vector/remove ] && echo yes)" yes
# The reboot: the module and its files gone, the launcher with them; Android gives HOME to Launcher3.
rm -f $T/root$A/ringtones/Spirit.m4a $T/root$A/notifications/SanguineRemix.m4a $T/root$A/alarms/Dream.m4a $F/pkg/dev.bold7.launcher
echo com.android.launcher3 > $F/role-android.app.role.HOME
echo false > $F/prop-sys.user.0.ce_available
expect "storage closed: nothing given back, the script kept" "$(sh $S/setup.sh restore >>$T/out 2>&1; echo $?) $(ls $T/adb/boot-completed.d)" "0 bold7-retour.sh"
echo true > $F/prop-sys.user.0.ce_available
expect "restore" "$(sh $S/setup.sh restore >>$T/out 2>&1; echo $?)" 0
expect "silence back, the later choice kept" "$(g global charging_sounds_enabled)$(g secure charging_vibration_enabled)$(g system lockscreen_sounds_enabled)$(g system sound_effects_enabled)$(g system accelerometer_rotation) $(g secure clipboard_show_access_notifications)" "11201 null"
expect "End: the later choice kept" "$(g system end_button_behavior)" 3
expect "sounds back, the user's ringtone kept" "$(g system ringtone) $(g system notification_sound) $(g system alarm_alert)" 'content://media/external_primary/audio/media/77 content://media/internal/audio/media/5?title=Pixie%20Dust&canonical=1 content://media/internal/audio/media/6'
expect "MediaStore forgot the module's sounds" "$(grep -c '|10[123]|' $F/media)" 0
expect "trackpad: arrow mode" "$(cat $F/mode)" 1
expect "density: Android's own again (reset)" "$(calls 'wm density')$(g secure display_density_forced)" "wm density reset "
expect "privacy indicators: unset again, true again" "$(g dc-privacy camera_mic_icons_enabled) $(g dc-privacy location_indicators_enabled)" "null true"
expect "PermissionController in the phone's language again" "$(cat $F/locale-com.android.permissioncontroller)" ""
expect "roles back" "$(cat $F/role-android.app.role.HOME) $(cat $F/role-android.app.role.DIALER)" "com.android.launcher3 com.android.dialer"
expect "input method back" "$(g secure default_input_method) $(g secure enabled_input_methods)" "$LATIN $TTS:$LATIN"
expect "bold7_ settings deleted" "$(ls $F/db/global | grep -c bold7_)" 0
expect "alarm volume given back" "$(calls volume)" "cmd media_session volume --stream 4 --set 4 "
expect "the Hub's access withdrawn" "$(calls disallow_listener | cut -c1-30)" "cmd notification disallow_list"
expect "the apps uninstalled" "$(ls $F/pkg | grep -c dev.bold7)" 0
expect "the script and the state gone" "$(ls $T/adb/boot-completed.d | wc -l | tr -d ' ') $([ -e $S ] && echo kept)" "0 "

stock; setup save $M >/dev/null; setup install $M >/dev/null; boot; setup apply $M >/dev/null; rm -f $F/calls
mkdir -p $T/user_de/dev.bold7.launcher/shared_prefs
echo '<map><string name="dnd_policy">33,1,1,0,1</string></map>' > $T/user_de/dev.bold7.launcher/shared_prefs/profiles.xml
echo "1 2" >> $S/touches-avant.txt; echo fr > $F/locale-com.android.permissioncontroller; echo 240 > $F/db/secure/display_density_forced; echo true > $F/db/dc-privacy/camera_mic_icons_enabled
echo '@17171416 -> #ff088eef' > $F/accent; echo '@17171541 -> #ff1e2125' > $F/grey
ADB_DIR=$T/adb sh $M/uninstall.sh; rm -f $F/pkg/dev.bold7.launcher
expect "a broken save and a Do Not Disturb policy left: not finished" "$(sh $S/setup.sh restore >>$T/out 2>&1; echo $?)" 0
expect "the broken save kept, End left as it is" "$(cat $S/touches-avant.txt | tr '\n' ';') $(g system end_button_behavior)" "2;1 2; 1"
expect "Do Not Disturb off, the policy kept for the user" "$(calls set_dnd) $(cat $S/dnd-policy-avant.txt)" "cmd notification set_dnd off  33,1,1,0,1"
expect "the rest given back all the same" "$(g system alarm_alert) $(ls $F/pkg | grep -c dev.bold7)" "content://media/internal/audio/media/6 0"
expect "a density chosen later kept" "$(g secure display_density_forced) $(calls 'wm density')" "240 "
expect "an indicator turned on later kept, the other given back" "$(g dc-privacy camera_mic_icons_enabled) $(g dc-privacy location_indicators_enabled)" "true true"
expect "PermissionController's language chosen later kept" "$(cat $F/locale-com.android.permissioncontroller)" fr
expect "SystemUI's palette overlays disabled" "$(calls 'overlay disable' | wc -w | tr -d ' ')" 12
expect "the script gone, the state kept with its log" "$(ls $T/adb/boot-completed.d | wc -l | tr -d ' ') $(ls $S | tr '\n' ' ')" "0 dnd-policy-avant.txt touches-avant.txt "

# The settings service not answering yet (the Q25 after the removal): never a value. A few failures
# are waited out; failing on, nothing is marked given back, the saves and the script stay for the
# next boot.
removed() {
  stock; setup save $M >/dev/null; setup install $M >/dev/null; boot; setup apply $M >/dev/null
  ADB_DIR=$T/adb sh $M/uninstall.sh >>$T/out 2>&1
  rm -f $T/root$A/ringtones/Spirit.m4a $T/root$A/notifications/SanguineRemix.m4a $T/root$A/alarms/Dream.m4a $F/pkg/dev.bold7.launcher
  echo com.android.launcher3 > $F/role-android.app.role.HOME
}
removed; echo 5 > $F/sfail
expect "settings fails 5 times, then answers: all given back" "$(sh $S/setup.sh restore >>$T/out 2>&1; echo $?) $(g system accelerometer_rotation) $(g system end_button_behavior) $(g system alarm_alert) $([ -e $S ] && echo kept)" "0 1 2 content://media/internal/audio/media/6 "
removed; echo 100000 > $F/sfail
expect "settings never answers: nothing marked given back" "$(ASK_TRIES=3 sh $S/setup.sh restore >>$T/out 2>&1; echo $?) $(ls $S | grep -c avant.txt) $(ls $T/adb/boot-completed.d)" "1 9 bold7-retour.sh"
expect "and no value of Bold7 taken for a later choice" "$(grep -c 'chosen later' $T/out)" 0
rm -f $F/sfail
expect "the next boot: given back" "$(sh $S/setup.sh restore >>$T/out 2>&1; echo $?) $(g system accelerometer_rotation)$(g global charging_sounds_enabled) $(g system end_button_behavior) $(g system alarm_alert) $(cat $F/role-android.app.role.DIALER) $(g secure default_input_method) $([ -e $S ] && echo kept) $(ls $T/adb/boot-completed.d | wc -l | tr -d ' ')" "0 11 2 content://media/internal/audio/media/6 com.android.dialer $LATIN  0"

# Installed over a phone where Bold7's Phone and launcher already held the roles (no save): given
# to LineageOS's Phone and Launcher3 all the same.
stock; echo dev.bold7.phone > $F/role-android.app.role.DIALER; echo dev.bold7.launcher > $F/role-android.app.role.HOME
setup save $M >/dev/null; setup install $M >/dev/null; boot; echo /data/app/l/base.apk > $F/pkg/dev.bold7.launcher
ADB_DIR=$T/adb sh $M/uninstall.sh; sh $S/setup.sh restore >/dev/null 2>&1
expect "no save, ours: LineageOS's Phone and Launcher3, the launcher's update uninstalled" "$(cat $F/role-android.app.role.DIALER) $(cat $F/role-android.app.role.HOME) $(calls 'uninstall dev.bold7.launcher')" "com.android.dialer com.android.launcher3 uninstall dev.bold7.launcher "
exit $bad
EOF
rc=0
# BUSYBOX=1: also as KernelSU runs the module's scripts, root with its busybox in standalone mode.
if [ "${BUSYBOX:-}" = 1 ]; then
  adb shell "su -c 'ASH_STANDALONE=1 /data/adb/ksu/bin/busybox sh $T/run.sh $T'" | tr -d '\r' || rc=1
else
  adb shell "sh $T/run.sh $T" | tr -d '\r' || rc=1
fi
[ "$(real)" = "$before" ] && echo "ok   the real settings, roles and input method did not move" || { echo "FAIL the real settings moved:"; diff <(echo "$before") <(real); rc=1; }
[ $rc = 0 ] || adb shell "cat $T/out" | tail -40
adb shell "su -c 'rm -rf $T'"
exit $rc
