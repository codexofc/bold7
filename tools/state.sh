#!/usr/bin/env bash
# Read-only snapshot of the Q25 state that Bold7 could affect.
# Output is stable (no timestamps, sorted) so two snapshots can be diffed:
#   tools/state.sh > before.txt; ...; tools/state.sh > after.txt; diff before.txt after.txt
set -euo pipefail

sh() { adb shell "$@" | tr -d '\r'; }
su_() { adb shell "su -c '$*'" | tr -d '\r'; }
section() { printf '\n## %s\n' "$1"; }

adb get-state >/dev/null

section identity
sh 'getprop ro.build.fingerprint; getprop ro.build.display.id; getprop ro.lineage.version; getprop ro.build.version.sdk; getprop ro.boot.slot_suffix; getenforce'

section modules
su_ 'for m in /data/adb/modules/*; do n=${m##*/}; f=""; for x in disable remove update skip_mount; do [ -e $m/$x ] && f="$f $x"; done; v=$(grep -m1 ^version= $m/module.prop 2>/dev/null | cut -d= -f2-); echo "$n version=${v:-none}${f}"; done; ls /data/adb/modules_update 2>/dev/null | sed "s/^/pending-update /"; ls /data/adb/metamodule/mnt 2>/dev/null | grep -v lost+found | sed "s/^/metamodule-image /"; [ -d /data/adb/lspd ] && echo lspd-present || echo lspd-absent'

section roles
for r in HOME DIALER SMS ASSISTANT BROWSER; do printf '%s ' "$r"; sh "cmd role get-role-holders android.app.role.$r"; done

section system-files
sh 'for f in /system_ext/priv-app/SystemUI/SystemUI.apk /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk /system/etc/fonts.xml /product/media/bootanimation.zip; do sha256sum $f; done'

section disabled-packages
sh 'pm list packages -d' | sort

section packages
sh 'pm list packages -f' | sort

section overlays
sh 'cmd overlay list' | grep -E '^\s*\[' | sed 's/^ *//' | sort -k2
printf 'config_recentsComponentName '; sh 'cmd overlay lookup android android:string/config_recentsComponentName'

# The app names of system/overlays/names/names.tsv as Android resolves them: the BBOS 7 name with the
# module, the app's own without it; a name that stays the app's own with the module is an update
# that renamed its string (the overlay is silent then). The lookup exits 1 when the app or the
# resource is missing: one "absent" line then, never an end of the snapshot (set -e).
section app-names
grep -v -e '^#' -e '^$' "$(dirname "$0")/../system/overlays/names/names.tsv" | while IFS=$'\t' read -r _ pkg res _; do
  printf '%s %s ' "$pkg" "$res"; { sh "cmd overlay lookup $pkg $pkg:string/$res" </dev/null || echo absent; }
done

# The palette SystemUI computes and registers as fabricated overlays (Bold7 replaces its
# values): it outlives the module until SystemUI recomputes it at the next boot without Bold7.
section palette
for n in accent1_600 neutral1_50 neutral1_900 primary_light surface_container_high_dark; do printf 'system_%s ' "$n"; sh "cmd overlay lookup android android:color/system_$n"; done
printf 'theme_customization_overlay_packages='; sh 'settings get secure theme_customization_overlay_packages'

section secure-settings
for k in enabled_accessibility_services enabled_notification_listeners default_input_method enabled_input_methods sysui_qs_tiles; do printf '%s=' "$k"; sh "settings get secure $k"; done

# Typing: the module selects it again after each boot while this is 1.
section saisie
printf 'bold7_saisie='; sh 'settings get global bold7_saisie'
printf 'bold7_saisie_style='; sh 'settings get global bold7_saisie_style'
# PermissionController's app language (British English while Bold7 is installed).
sh 'cmd locale get-app-locales com.google.android.permissioncontroller --user 0'

section sound-settings
for k in ringtone notification_sound alarm_alert; do printf '%s=' "$k"; sh "settings get system $k"; done

section props
sh 'getprop ro.config.ringtone; getprop ro.config.notification_sound; getprop ro.config.alarm_alert'

section quickstep
sh 'dumpsys activity service com.android.systemui/.SystemUIService' | grep -E 'isConnected=|mRecentsComponentName=|mBound=' | sed 's/^ *//'

section priv-app-dirs
su_ 'ls -d /system/priv-app/* /system_ext/priv-app/* /product/priv-app/* /system/product/overlay/* /system_ext/overlay/* /product/overlay/* 2>/dev/null' | sort

# The foreground app's CPU floor the module sets after each boot (energy.sh): the ROM's 0.00
# before the install and after the removal.
section sched
printf 'top-app cpu.uclamp.min '; su_ 'cat /dev/cpuctl/top-app/cpu.uclamp.min 2>/dev/null || echo absent'

# The trackpad: the driver's mode (1, arrows, before the install and after the
# removal), bold7-trackpad's processes, its settings, and the input devices: its keyboard twin is
# a second Q25_keyboard while it runs, its touchscreen twin a second s2716b_ts.
section trackpad
printf 'mode '; su_ 'cat /proc/q20_switch_key_mouse'
printf 'bold7-trackpad processes '; su_ 'pidof bold7-trackpad | wc -w'
for k in bold7_trackpad_off bold7_trackpad_horizontal bold7_trackpad_vertical bold7_trackpad_audible bold7_trackpad_palm_band bold7_trackpad_palm_delay; do printf '%s=' "$k"; sh "settings get global $k"; done
su_ 'grep ^N: /proc/bus/input/devices' | sort

# The End key: the layout Android loaded for each Q25_keyboard (the module's
# /product/usr/keylayout copy while it is mounted, /vendor/usr/keylayout's without it), what End
# (KEY_HOMEPAGE, 172) is in the stock and the module's file, and end_button_behavior (2 on the Q25).
section touches
sh 'dumpsys input' | grep -A12 -E '^ +[0-9]+: Q25_keyboard' | grep KeyLayoutFile | sed 's/^ *//' | sort || true
for f in /vendor/usr/keylayout/Q25_keyboard.kl /product/usr/keylayout/Q25_keyboard.kl; do
  printf '%s ' "$f"; su_ "grep '^key 172 ' $f 2>/dev/null || echo absent"
done
printf 'end_button_behavior='; sh 'settings get system end_button_behavior'
printf 'display_density_forced='; sh 'settings get secure display_density_forced'
# PermissionController's app language (British English while Bold7 is installed).
sh 'cmd locale get-app-locales com.android.permissioncontroller --user 0'
