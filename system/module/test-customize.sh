#!/usr/bin/env bash
# Runs the module's install (customize.sh, with the real setup.sh check, save and install, and the
# real extract.sh and extractor) as KernelSU does: sourced by KernelSU's busybox sh, in root, with
# ui_print and abort of the installer, in a scratch folder of /data/local/tmp. Fakes: getprop, pm,
# settings, cmd, content, log, ksud, and build-image.sh (which only lists layer/: test-mount.sh
# tests the real one). The phone's own stock keylayout and fonts.xml are read; on a phone where
# Bold7 is mounted, fonts.xml is read beneath it through a bind mount of / in the scratch folder,
# undone at once. The official OS Loader of the 9900 is pushed from assets-private/ for the
# extraction, then deleted with the folder. Nothing else is touched. Exits non-zero on failure.
#   system/module/test-customize.sh      (after ./gradlew :core:firmware:extractorDex)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$(cd ../.. && pwd)
T=/data/local/tmp/bold7-customize-test
JAR=$ROOT/core/firmware/build/dex/bold7-firmware.jar
LOADER=$ROOT/assets-private/firmware/bbos7-9900/9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe
[ -f "$JAR" ] || { echo "FAIL $JAR missing: ./gradlew :core:firmware:extractorDex"; exit 1; }
[ -f "$LOADER" ] || { echo "FAIL $LOADER missing"; exit 1; }
adb shell "rm -rf $T && mkdir -p $T/src/firmware $T/src/bin $T/dl $T/f/bin"
adb push src/. $T/src/ >/dev/null
adb push "$JAR" $T/src/firmware/ >/dev/null
adb push "$ROOT/core/icons/src/main/res/raw/bbos7_icons.tsv" sounds.tsv $T/src/firmware/ >/dev/null
adb shell "mkdir -p $T/src/firmware/dictionary" && adb push "$ROOT/apps/saisie/src/main/assets/dictionary/fr.tsv" $T/src/firmware/dictionary/ >/dev/null
# tools/fonts_xml.py's output from the copy of the phone's stock fonts.xml (assets-gen/device), which
# the development build writes: the phone's derivation must give the same bytes.
XML=build/fonts-xml/fonts.xml
[ -f $XML ] || { echo "FAIL $XML missing: ./gradlew :system:module:moduleZip"; exit 1; }
adb push $XML $T/expected-fonts.xml >/dev/null
adb shell "mkdir -p $T/keep" && adb push "$LOADER" $T/keep/ >/dev/null
adb shell "cat > $T/run.sh" <<'EOF'
T=$1
F=$T/f
export FAKE=$F TOOLS=$F/bin ADB_DIR=$T/adb STATE=$T/adb/bold7-state KSUD=$F/bin/ksud KL=$F/stock.kl DOWNLOAD=$T/dl
export PATH=$F/bin:$PATH
bad=0
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2', expected '$3'"; bad=1; fi; }
has() { if grep -qF -- "$2" $T/out; then echo "ok   $1"; else echo "FAIL $1: no '$2' in:"; sed 's/^/     | /' $T/out; bad=1; fi; }

cd $F/bin
cat > getprop <<'X'
case $1 in ro.product.device) cat $FAKE/device ;; ro.build.display.id) echo BenOS-test ;; ro.benos_timestamp) echo 1 ;; esac
X
cat > pm <<'X'
case $1 in
install) p=${3##*/}; p=${p%.apk}; echo "install $p" >> $FAKE/calls
  if [ -e $FAKE/fail-$p ]; then echo "Failure [INSTALL_FAILED_TEST]"; exit 1; fi; echo Success ;;
esac
X
cat > ksud <<'X'
echo "ksud $*" >> $FAKE/calls
X
echo 'echo null' > settings
for x in cmd content log; do : > $x; done
chmod 755 *
cat /system/usr/keylayout/Q25_keyboard.kl > $F/stock.kl
cd $T

# A fresh module folder as the installer unpacks it, and a phone without Bold7's state.
fresh() {
    rm -rf $T/mod $T/adb $T/out $T/tree $F/calls $F/fail-* $T/dl/*
    mkdir -p $T/adb
    cp -r $T/src $T/mod
    mkdir -p $T/mod/apps $T/mod/vector
    for p in hub phone messages saisie clock calendar contacts calculator flashlight memopad tasks pictures sysui; do echo apk > $T/mod/apps/dev.bold7.$p.apk; done
    echo zip > $T/mod/vector/vector.zip
    echo bin > $T/mod/bin/bold7-trackpad; echo bin > $T/mod/bin/bold7-led
    cat > $T/mod/build-image.sh <<'X'
(cd $1 && find . -type f | sort) > ${1%/*}/../tree
cp $1/system/etc/fonts.xml ${1%/*}/../fonts.out 2>/dev/null
touch $2
X
    echo Q25 > $F/device
}
# inst: the installer's ui_print and abort, then customize.sh sourced. Prints its exit code.
inst() {
    ( MODPATH=$T/mod
      ui_print() { echo "$1"; }
      abort() { ui_print "$1"; rm -rf "$MODPATH"; exit 1; }
      . $T/mod/customize.sh ) > $T/out 2>&1
    echo $?
}

fresh; echo Q20 > $F/device
expect "another phone: refused" "$(inst)" 1
has "  step 1 says no" "[1/5] Checking your phone... no"
has "  and nothing changed" "Nothing was changed on your phone."
has "  and why" "Bold7 is made for the Zinwa Q25 only, and this phone is a 'Q20'."
expect "  module folder deleted, no state" "$([ -e $T/mod ] || [ -e $T/adb/bold7-state ] && echo left || echo none)" none

fresh
expect "no file in Download: refused" "$(inst)" 1
has "  step 1 passes" "[1/5] Checking your phone... OK, Zinwa Q25 on BenOS-test"
has "  step 2 says not found" "[2/5] Finding your BlackBerry 9900 file... not found"
has "  and nothing changed" "Nothing was changed on your phone."
has "  and which file to put where" "put 9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe (or the official BlackBerry Simulator 7.1.0.318 for the 9900) in your Download folder, then install Bold7 again."
expect "  nothing installed, no state" "$(cat $F/calls 2>/dev/null | wc -l | tr -d ' ') $([ -e $T/adb/bold7-state ] && echo state || echo none)" "0 none"

fresh; echo partial > $T/dl/9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe
expect "a partial download: refused" "$(inst)" 1
has "  named" "9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe in your Download folder is incomplete (8 bytes): download it again."

fresh; truncate -s 273797496 $T/dl/other.exe
expect "a file of the right size but another fingerprint: refused" "$(inst)" 1
has "  named and explained" "other.exe in your Download folder is not the official file: its fingerprint is not the one Bold7 knows."

fresh; rm -rf $T/mod/firmware
expect "a development zip: nothing to extract" "$(inst)" 0
has "  said" "[2/5] Finding your BlackBerry 9900 file... not needed, this build carries its own files"

fresh; mv $T/keep/*.exe $T/dl/; touch $F/fail-dev.bold7.phone
s=$(date +%s)
expect "the OS Loader in Download: installed" "$(inst)" 0
echo "     (install with extraction: $(($(date +%s) - s)) s)"
mv $T/dl/*.exe $T/keep/
has "  step 2 found it" "[2/5] Finding your BlackBerry 9900 file... found (9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe)"
has "  step 3" "[3/5] Extracting the BlackBerry OS 7 look..."
has "  what it laid" " sounds, 23 fonts... done"
has "  step 4 names what failed" "[4/5] Installing the Bold7 apps... done, except:"
has "  the app that failed" "      dev.bold7.phone not installed: Failure [INSTALL_FAILED_TEST]"
has "  step 5" "[5/5] Ready."
has "  how to start and to undo" "Restart your phone to start Bold7. To undo everything at any time: Modules > Bold7 > Remove, then restart."
expect "  at most one progress line every 3 s" "$(grep -c '^      [0-9]*%$' $T/out | awk '{print ($1 <= 12)}')" 1
expect "  no jargon shown" "$(grep -ciE 'overlay|layer|app_process|dex|ksud|sha256' $T/out)" 0
for f in system/etc/fonts.xml product/media/bootanimation.zip system/fonts/BBAlphaSans.ttf system/fonts/BBGlobalSansFallback.ttf \
    product/media/audio/ringtones/Spirit.m4a product/media/audio/notifications/Sonar.wav product/media/audio/ui/LowBattery.ogg \
    product/media/bold7/ui/res/drawable-nodpi/ui_menu.9.png product/usr/keylayout/Q25_keyboard.kl \
    product/media/bold7/saisie/assets/os7/dictionary/fr.tsv product/media/bold7/saisie/assets/os7/substitution/en.tsv; do
    expect "  laid: $f" "$(grep -cx "./$f" $T/tree)" 1
done
expect "  fonts.xml: tools/fonts_xml.py's bytes (else assets-gen/device/fonts.xml is not this phone's)" "$(cmp $T/fonts.out $T/expected-fonts.xml && echo same)" same
expect "  no bind mount left" "$(grep -c " $T/" /proc/self/mountinfo)" 0
expect "  extraction kept for updates, stamped" "$([ -d $STATE/extrait/ui/res ] && cat $STATE/extrait/.extractor)" "$(sha256sum $T/src/firmware/bold7-firmware.jar | cut -d' ' -f1)"
expect "  only the module's files left in its folder" "$(ls $T/mod | tr '\n' ' ')" "bin boot-completed.sh build-image.sh compile compile.sh customize.sh energy.sh extract.sh icons.sh install.log layer.img module.prop overlays.sh post-fs-data.sh saisie-start.sh saver.sh service.sh setup.sh sysui-guard.sh trackpad-start.sh uninstall.sh "

# An update without the file in Download/: what was extracted is kept.
rm -rf $T/mod $T/out $T/tree; cp -r $T/src $T/mod; echo bin > $T/mod/bin/bold7-trackpad; echo bin > $T/mod/bin/bold7-led
cat > $T/mod/build-image.sh <<'X'
(cd $1 && find . -type f | sort) > ${1%/*}/../tree
touch $2
X
expect "an update without the file: installed" "$(inst)" 0
has "  from what was kept" "[2/5] Finding your BlackBerry 9900 file... not needed, Bold7 kept what it took from it last time"
expect "  laid all the same" "$(grep -c '^./product/media/bold7/' $T/tree | awk '{print ($1 > 400)}') $(grep -cx ./system/etc/fonts.xml $T/tree)" "1 1"

echo other > $STATE/extrait/.extractor
rm -rf $T/mod; cp -r $T/src $T/mod; echo bin > $T/mod/bin/bold7-trackpad; echo bin > $T/mod/bin/bold7-led
expect "another extractor than the one that extracted: the file is needed again" "$(inst)" 1
has "  said" "[2/5] Finding your BlackBerry 9900 file... not found"

exit $bad
EOF
rc=0
adb shell "su -c '/data/adb/ksu/bin/busybox sh $T/run.sh $T'" | tr -d '\r' || rc=$?
adb shell "su -c 'rm -rf $T'"
exit $rc
