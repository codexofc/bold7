# Bold7, install (sourced by KernelSU's installer, root, after the zip is unpacked in MODPATH).
# What the manager shows is the user's first screen: five numbered steps in plain English, each with
# its result; the details go to install.log in the module's folder. A refusal says what to do and
# that nothing was changed: abort deletes MODPATH, and nothing else is written before step 4 but
# the extraction Bold7 keeps for its next install (extract.sh).
LOG=$MODPATH/install.log
stop() {
    ui_print " "
    ui_print "Nothing was changed on your phone."
    ui_print "$1"
    abort " "
}
r=$(sh "$MODPATH/setup.sh" check "$MODPATH" 2>&1) || { ui_print "[1/5] Checking your phone... no"; stop "${r#! }"; }
ui_print "[1/5] Checking your phone... $r"
# The programs (bold7-trackpad, bold7-led): the installer's default permissions leave files 0644.
chmod 0755 "$MODPATH/bin/bold7-trackpad" "$MODPATH/bin/bold7-led" || stop "The Bold7 file you downloaded looks damaged: download it again, then install Bold7 again."
# Steps 2 and 3: the BlackBerry OS 7 look from the user's own file of the 9900, under layer/.
sh "$MODPATH/extract.sh" "$MODPATH" || stop "$(cat "$MODPATH/why" 2>/dev/null)"
# The files of layer/ go into the ext4 image post-fs-data.sh mounts (build-image.sh); the plain
# copy on /data is then useless, overlayfs refusing it as a lower layer (docs/DEVICE.md).
sh "$MODPATH/build-image.sh" "$MODPATH/layer" "$MODPATH/layer.img" >>"$LOG" 2>&1 ||
    stop "Bold7 could not prepare its files ($(tail -n 1 "$LOG")). If your phone is almost full, make room, then install Bold7 again."
# The values Bold7 changes, saved once in /data/adb/bold7-state before anything is set (it reads
# layer/ to know the module's sounds, so before it goes).
sh "$MODPATH/setup.sh" save "$MODPATH" >>"$LOG" 2>&1
rm -rf "$MODPATH/layer"
# Vector if missing, then the apps, by pm install: they are installed at once. A line of the log
# that starts with ! is a step that failed: shown to the user.
before=$(grep -c '^!' "$LOG")
sh "$MODPATH/setup.sh" install "$MODPATH" >>"$LOG" 2>&1
if [ "$(grep -c '^!' "$LOG")" = "$before" ]; then
    ui_print "[4/5] Installing the Bold7 apps... done"
else
    ui_print "[4/5] Installing the Bold7 apps... done, except:"
    grep '^!' "$LOG" | tail -n +$((before + 1)) | sed 's/^! //' | while read -r l; do ui_print "      $l"; done
fi
# The old icons pack a user may have been given beside the 9900's file (icons.sh): one line if found.
icons=$(sh "$MODPATH/icons.sh" "$MODPATH" 2>>"$LOG")
if [ -n "$icons" ]; then ui_print "$icons"; fi
ui_print "[5/5] Ready."
ui_print " "
ui_print "Restart your phone to start Bold7. To undo everything at any time: Modules > Bold7 > Remove, then restart."
# A new launcher APK: boot-completed.sh compiles it once (compile.sh).
touch "$MODPATH/compile"
# An update replaces the module folder: the eco flag carries over. It only serves the floor set before
# saver.sh reads the battery saver, which then wins (a manual eco holds until the next toggle or reboot).
# (an if, not &&: this file is sourced, and a false test must not be its last status)
if [ -f /data/adb/modules/bold7/eco ]; then touch "$MODPATH/eco"; fi
