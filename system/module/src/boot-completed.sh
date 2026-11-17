#!/system/bin/sh
# Bold7, boot-completed stage: Android has fully started, the boot-loop guard resets.
MODDIR=${0%/*}
umask 077
echo 0 >"$MODDIR/boot_count"
# The foreground app's CPU floor (energy.sh), in the background: it waits for init's own reset.
# Once set, the eco mode follows Android's battery saver until reboot (saver.sh): started only
# now, the settings provider answers, and nothing can write the floor before init has reset it.
# Both log to bold7.log; their output goes nowhere, so this script's caller is not held.
(sh "$MODDIR/energy.sh" "$MODDIR" /dev/cpuctl boot && exec sh "$MODDIR/saver.sh" "$MODDIR" /dev/cpuctl) >/dev/null 2>&1 &
# The trackpad and the Menu key of OS 7 in every app (bold7-trackpad),
# never while the SIM's PIN may be typed: boot completes with its screen still up, so
# trackpad-start.sh waits for gsm.sim.state to leave PIN_REQUIRED and the like (15 minutes at most,
# then not for this boot); until then the keyboard is Android's alone. It runs until reboot, gives
# the keyboard and the trackpad back to Android whenever it ends, and does not start while
# bold7_trackpad_off is 1. In its own session, its output to bold7.log.
setsid sh "$MODDIR/trackpad-start.sh" "$MODDIR" </dev/null >/dev/null 2>&1 &
# The LED of OS 7 (bold7-led): flashes the colour the launcher writes to bold7_led, until reboot;
# gives the LED back to Android when it ends. Its own session, its output to bold7.log.
setsid "$MODDIR/bin/bold7-led" "$MODDIR" </dev/null >/dev/null 2>&1 &
# Typing (dev.bold7.saisie), not directBootAware, is replaced by BBKB while the user's
# storage is still encrypted: saisie-start.sh waits for it to open (15 minutes at most), then
# selects Typing again if bold7_saisie is 1 and it is installed. Its output to bold7.log.
setsid sh "$MODDIR/saisie-start.sh" "$MODDIR" </dev/null >/dev/null 2>&1 &
# What tools/install.sh set from the Mac (setup.sh apply): once the user's storage is open,
# each value saved at install set once (sounds, silence, End key, trackpad, HOME, DIALER, Typing), then
# the apps' permissions, app-ops and accesses once per install. Nothing to do: it ends at once.
setsid sh "$MODDIR/setup.sh" apply "$MODDIR" </dev/null >>"$MODDIR/bold7.log" 2>&1 &
# Then, after an install only, the launcher's ahead-of-time compilation (compile.sh): the package
# manager answers only now, which service.sh, at late_start, cannot count on.
sh "$MODDIR/compile.sh" "$MODDIR" /system/bin/cmd /system/priv-app/Bold7Launcher/Bold7Launcher.apk
