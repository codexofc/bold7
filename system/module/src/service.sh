#!/system/bin/sh
# Bold7, late_start service stage: runs in the background, does not hold the boot.
#
# SystemUI crash-loop guard (sysui-guard.sh) over the first 10 minutes of each boot: a loop
# caused by our files starts with the first SystemUI, before or right after boot completes.
# Event-driven (logcat blocks between events), and nothing of it stays after 10 minutes.
# Deliberately simple: a loop that starts later (a resource only read when the shade opens) is not caught;
# safe mode or `ksud module disable bold7` then.
MODDIR=${0%/*}
# Android's reboot by its path: a busybox reboot applet (KernelSU runs this script with its busybox)
# would not stop Android properly.
timeout 600 logcat -b events -v monotonic | sh "$MODDIR/sysui-guard.sh" "$MODDIR" /system/bin/reboot
