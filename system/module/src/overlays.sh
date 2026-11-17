#!/system/bin/sh
# Bold7: OS 7's dialog, menu and list images on the framework drawables that the RROs
# (Bold7Android, Bold7PermissionController) name, which carry no image: one fabricated overlay of
# "android", dev.bold7.launcher:images, owned by the launcher, filled from the images the module
# extracted (/product/media/bold7). ImageOverlays of the launcher's APK does the work, by app_process,
# as root (only root or the shell may fabricate an overlay).
#
#   sh overlays.sh apply <moddir>    at boot completed (setup.sh apply): registers and enables the
#                                    overlay unless it is already on with these images and this
#                                    launcher; nothing to do otherwise. Exit status: the failures
#                                    (images not put, overlay not on), 0 when all is in place.
#   sh overlays.sh remove            Android running: unregisters it, then purge
#   sh overlays.sh purge             files only, for uninstall.sh before system_server: the .frro and
#                                    its idmap, which Android would otherwise register again at the
#                                    next boot (unregistering leaves them in /data/resource-cache)
#
# Without the overlay the dialogs keep their layout, with Android's legacy frames for images.
TOOLS=${TOOLS:-/system/bin}
CACHE=${CACHE:-/data/resource-cache}
IMAGES=${IMAGES:-/product/media/bold7}
L=dev.bold7.launcher
ID=$L:images
MAIN=dev.bold7.launcher.ImageOverlays

say() { echo "overlays: $*"; }
launcher_apk() { [ -n "$APK" ] && echo "$APK" || "$TOOLS/pm" path $L 2>/dev/null | sed -n 's/^package://p' | head -n 1; }
enabled() { "$TOOLS/cmd" overlay list android 2>/dev/null | grep -qxF "[x] $ID"; }
listed() { "$TOOLS/cmd" overlay list android 2>/dev/null | grep -qF "] $ID"; }
# What the overlay was made from: the images (names, sizes, dates) and the launcher's APK.
sources() { { ls -lL "$IMAGES"/*/res/drawable-nodpi/ 2>/dev/null; echo "$1"; } | md5sum | cut -d' ' -f1; }

case $1 in
apply)
    MODDIR=$2
    STAMP=$MODDIR/overlays.stamp
    apk=$(launcher_apk)
    [ -n "$apk" ] || { say "! no $L, nothing put"; exit 1; }
    now=$(sources "$apk")
    if enabled && [ "$(cat "$STAMP" 2>/dev/null)" = "$now" ]; then exit 0; fi
    rm -f "$STAMP"
    # Registering again writes a new .frro beside the old one, which Android would register too at
    # the next boot (seen on the Q25): the old overlay goes first, files and all.
    sh "$0" remove
    CLASSPATH=$apk "$TOOLS/app_process" "$TOOLS" $MAIN register "$IMAGES" "$MODDIR/overlays.staging"
    failures=$?
    "$TOOLS/cmd" overlay enable --user 0 $ID >/dev/null 2>&1
    enabled || { failures=$((failures + 1)); say "! $ID not on"; }
    [ $failures = 0 ] && echo "$now" >"$STAMP"
    say "$ID put, $failures failures"
    exit $failures
    ;;
remove)
    apk=$(launcher_apk)
    if listed && [ -n "$apk" ]; then CLASSPATH=$apk "$TOOLS/app_process" "$TOOLS" $MAIN unregister; fi
    sh "$0" purge
    listed && { say "! $ID still listed"; exit 1; }
    exit 0
    ;;
purge)
    rm -f "$CACHE/$L-images-"*.frro "$CACHE/data@resource-cache@$L-images-"*.frro@idmap
    ;;
*)
    echo "usage: overlays.sh apply <moddir> | remove | purge" >&2
    exit 2
    ;;
esac
