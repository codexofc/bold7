# Bold7, install (sourced by KernelSU's installer, root, after the zip is unpacked in MODPATH).
# Each step says itself in the manager's log, one line each (setup.sh). The phone is checked first:
# a refusal aborts with nothing changed (abort deletes MODPATH).
sh "$MODPATH/setup.sh" check "$MODPATH" || abort "! Bold7: not installed, nothing changed"
# The files of layer/ go into the ext4 image post-fs-data.sh mounts (build-image.sh); the plain
# copy on /data is then useless, overlayfs refusing it as a lower layer (docs/DEVICE.md).
sh "$MODPATH/build-image.sh" "$MODPATH/layer" "$MODPATH/layer.img" || abort "! Bold7: layer image not built, nothing installed"
# The values Bold7 changes, saved once in /data/adb/bold7-state before anything is set (it reads
# layer/ to know the module's sounds, so before it goes).
sh "$MODPATH/setup.sh" save "$MODPATH"
rm -rf "$MODPATH/layer"
# The trackpad's program (bold7-trackpad): the installer's default permissions leave files 0644.
chmod 0755 "$MODPATH/bin/bold7-trackpad" || abort "! Bold7: bold7-trackpad not executable, nothing installed"
# The 9900's file (OS Loader or simulator), whose images, sounds and fonts Bold7 will extract here
# on the phone (core/firmware, core/theme): not read yet, this zip still carries them itself.
ui_print "- 9900 file: not read by this version (its resources are still in the zip)"
# Vector if missing, then the apps, by pm install: they are installed at once.
sh "$MODPATH/setup.sh" install "$MODPATH"
ui_print "- Reboot: Bold7 sets itself as home, Phone and input method at the first unlock"
# A new launcher APK: boot-completed.sh compiles it once (compile.sh).
touch "$MODPATH/compile"
# An update replaces the module folder: the eco flag carries over. It only serves the floor set before
# saver.sh reads the battery saver, which then wins (a manual eco holds until the next toggle or reboot).
# (an if, not &&: this file is sourced, and a false test must not be its last status)
if [ -f /data/adb/modules/bold7/eco ]; then touch "$MODPATH/eco"; fi
