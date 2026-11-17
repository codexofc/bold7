#!/system/bin/sh
# Bold7: steps 2 and 3 of the install (customize.sh), the look of BlackBerry OS 7 taken from the
# user's own file of the Bold 9900 and laid under <moddir>/layer, which build-image.sh then puts in
# layer.img.
#
#   sh extract.sh <moddir>
#
# A public zip carries no file of BlackBerry, only the extractor (firmware/bold7-firmware.jar, built
# from core/firmware) and the two tables it reads. Run in root by app_process, it finds the official
# file in Download/ by its size then its sha256, hashes its own private copy of it again before any
# parser reads a byte, and writes the images, sounds, fonts and OS 7's part of Typing's lists (laid over
# Typing's AOSP French list, firmware/dictionary); it then lays them out: images and Typing's lists
# under product/media/bold7, sounds under product/media/audio, fonts under system/fonts, fonts.xml
# derived from the phone's own, the boot animation.
# The extraction is kept in $S/extrait, stamped with the extractor's sha256: an update with the
# same extractor needs no file. A development zip carries the files itself and no extractor.
# Prints the steps for the KernelSU manager; on a refusal, exits 1 with the reason in <moddir>/why.
# Nothing outside <moddir> (which abort deletes) is written before the extraction succeeds.
MOD=$1
JAR=$MOD/firmware/bold7-firmware.jar
DOWNLOAD=${DOWNLOAD:-/data/media/0/Download}
S=${STATE:-/data/adb/bold7-state}
APP_PROCESS=${APP_PROCESS:-/system/bin/app_process}
FONTS_XML=${FONTS_XML:-/system/etc/fonts.xml}
KEPT=$S/extrait
WANT=9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe
NEEDS="Bold7 needs the official BlackBerry Bold 9900 OS 7.1 file: put $WANT (or the official BlackBerry Simulator 7.1.0.318 for the 9900) in your Download folder, then install Bold7 again."
run() { CLASSPATH=$JAR "$APP_PROCESS" /system/bin dev.bold7.firmware.ExtractorKt "$@"; }
fail() {
    echo "$*" >"$MOD/why"
    rm -rf "$MOD/extract" "$MOD/stock"
    exit 1
}

if [ ! -f "$JAR" ]; then
    echo "[2/5] Finding your BlackBerry 9900 file... not needed, this build carries its own files"
    echo "[3/5] Extracting the BlackBerry OS 7 look... not needed"
    exit 0
fi
stamp=$(sha256sum "$JAR" | cut -d' ' -f1)

file=$(run find "$DOWNLOAD" 2>"$MOD/find.err")
rc=$?
if [ $rc = 0 ]; then
    echo "[2/5] Finding your BlackBerry 9900 file... found (${file##*/})"
elif [ $rc = 3 ] && [ -n "$stamp" ] && [ "$(cat "$KEPT/.extractor" 2>/dev/null)" = "$stamp" ]; then
    echo "[2/5] Finding your BlackBerry 9900 file... not needed, Bold7 kept what it took from it last time"
elif [ $rc = 3 ]; then
    echo "[2/5] Finding your BlackBerry 9900 file... not found"
    fail "$(cat "$MOD/find.err") $NEEDS"
else
    echo "[2/5] Finding your BlackBerry 9900 file... failed"
    fail "Bold7 could not look in your Download folder ($(cat "$MOD/find.err")). Restart your phone, then install Bold7 again."
fi

echo "[3/5] Extracting the BlackBerry OS 7 look..."
if [ $rc = 0 ]; then
    rm -rf "$MOD/extract"
    run extract "$file" "$MOD/extract" "$MOD/firmware/bbos7_icons.tsv" "$MOD/firmware/sounds.tsv" "$MOD/firmware/dictionary" 2>"$MOD/extract.err" ||
        fail "The extraction stopped: $(cat "$MOD/extract.err"). It needs about 600 MB free: if your phone is almost full, make room, then install Bold7 again."
    { mkdir -p "$S" && chmod 700 "$S" && rm -rf "$KEPT" && mv "$MOD/extract/gen" "$KEPT" && echo "$stamp" >"$KEPT/.extractor"; } ||
        fail "Bold7 could not keep the extracted files. Restart your phone, then install Bold7 again."
    rm -rf "$MOD/extract"
fi

# The phone's own fonts.xml. On an update, Bold7's is mounted over it: the stock one is read beneath,
# through a bind mount of the root file system without its submounts (ours among them), at once undone.
xml=$FONTS_XML
if grep -q BBAlpha "$xml"; then
    mkdir -p "$MOD/stock" && mount --bind "${ROOTFS:-/}" "$MOD/stock" ||
        fail "Bold7 could not read the fonts list of your phone. Restart your phone, then install Bold7 again."
    cp "$MOD/stock${xml}" "$MOD/fonts.xml"
    copied=$?
    umount "$MOD/stock" && rmdir "$MOD/stock"
    [ $copied = 0 ] || fail "Bold7 could not read the fonts list of your phone. Restart your phone, then install Bold7 again."
    xml=$MOD/fonts.xml
fi
laid=$(run layer "$KEPT" "$xml" "$MOD/layer" 2>"$MOD/extract.err") ||
    fail "Bold7 could not prepare the BlackBerry OS 7 look: $(cat "$MOD/extract.err")."
echo "      $laid... done"
rm -rf "$MOD/firmware" "$MOD/fonts.xml" "$MOD/find.err" "$MOD/extract.err"
