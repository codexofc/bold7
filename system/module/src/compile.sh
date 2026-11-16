#!/system/bin/sh
# Bold7: compiles the launcher ahead of time, once after each install of the module.
#
#   sh compile.sh <moddir> <cmd> <apk>  (boot-completed.sh with /system/bin/cmd and the mounted
#                                         launcher APK; test-compile.sh)
#
# Android compiles a priv-app on /system at first boot and after an OTA only: once the module
# installs or updates the launcher, it runs interpreted (run-from-apk) until the daily idle job.
# customize.sh leaves <moddir>/compile behind. It stays until a boot where <apk> is there: when the
# mounts failed, Android started stock and the next boot tries again. Then it goes first, so a
# compile that fails is not tried again at every boot; then the launcher is compiled unless ART
# already holds compiled code for it.
# ART builds the profile from Bold7Launcher.apk.prof, beside the APK (docs/PERF.md).
MODDIR=$1 CMD=$2 APK=$3
PKG=dev.bold7.launcher
LOG=$MODDIR/bold7.log
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }

[ -f "$MODDIR/compile" ] || exit 0
if [ ! -f "$APK" ]; then
    say w "compile: $APK not mounted, kept for the next boot"
    exit 0
fi
rm -f "$MODDIR/compile"
# One line per ABI of the base APK: "arm64: [status=run-from-apk] [reason=unknown] ...".
case $("$CMD" package art dump "$PKG" 2>&1) in
*'[status=speed-profile]'* | *'[status=speed]'* | *'[status=everything]'*)
    say i "compile: $PKG already compiled"
    exit 0
    ;;
esac
if out=$("$CMD" package compile -m speed-profile -f "$PKG" 2>&1); then
    say i "compile: $PKG speed-profile: $out"
else
    say w "compile: $PKG failed, left to the idle job: $out"
fi
