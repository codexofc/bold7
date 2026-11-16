#!/system/bin/sh
# Bold7, removal: KernelSU runs this at the boot that follows "Remove", in its post-fs-data, before
# zygote and system_server (measured on the Q25): no pm, settings, cmd nor ime answers here,
# so it does files only. What goes through Android (sounds, settings, roles, input method, apps) is
# given back once Android has started, by the one script it leaves in /data/adb/boot-completed.d
# (setup.sh restore, copied beside the saves), which waits for the user's storage, then deletes
# itself. In KernelSU's safe mode, the removal waits for the next normal boot.
MODDIR=${0%/*}
ADB=${ADB_DIR:-/data/adb}
S=$ADB/bold7-state
mkdir -p "$S" && chmod 700 "$S"
cp -f "$MODDIR/setup.sh" "$S/setup.sh"
# The sound profiles' preferences, which may hold the user's Do Not Disturb policy: the
# launcher's data may go with its package before Android has started.
cp -f "${USER_DE:-/data/user_de/0}/dev.bold7.launcher/shared_prefs/profiles.xml" "$S/profiles.xml" 2>/dev/null
# Vector leaves with Bold7 only if Bold7 installed it.
if [ -e "$S/vector.installe" ] && [ -d "$ADB/modules/zygisk_vector" ]; then touch "$ADB/modules/zygisk_vector/remove"; fi
mkdir -p "$ADB/boot-completed.d"
chmod 755 "$ADB/boot-completed.d"
# ksud runs every executable file of the folder at boot completed, and skips the others.
cat >"$ADB/boot-completed.d/bold7-retour.sh" <<END
#!/system/bin/sh
# Bold7 was removed: gives back what it changed, then deletes this file (setup.sh restore).
setsid sh $S/setup.sh restore </dev/null >>$S/restore.log 2>&1 &
END
chmod 755 "$ADB/boot-completed.d/bold7-retour.sh"
