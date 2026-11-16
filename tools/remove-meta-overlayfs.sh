#!/usr/bin/env bash
# Removes the meta-overlayfs metamodule (docs/DEVICE.md), in two runs around a reboot. Bold7
# mounts its files itself and refuses to run while a metamodule is installed.
#
#   1st run: marks meta-overlayfs for removal. Until the reboot, KernelSU refuses to install any
#            other module (a metamodule change is pending).
#   2nd run, after the reboot: checks that the module, the /data/adb/metamodule link, its 2 GB
#            image and its mounts are gone. Deletes nothing itself: it prints what is left and
#            the exact command that removes it.
#
# CHANGES THE PHONE'S STATE: ask the phone's owner first.
set -euo pipefail
cd "$(dirname "$0")/.."
su_() { adb shell "su -c '$*'" | tr -d '\r'; }
M=/data/adb/modules/meta-overlayfs

adb get-state >/dev/null
if [ -n "$(su_ "[ -d $M ] && [ ! -e $M/remove ] && echo present; true")" ]; then
  su_ ksud module uninstall meta-overlayfs
  echo "Marked for removal. Reboot, then run tools/remove-meta-overlayfs.sh again."
  exit 0
fi

# What it leaves, if anything: its folder (holding modules.img, the 2 GB sparse image, and mnt/),
# the /data/adb/metamodule link to it, the image's loop mount, overlays with the source KSU.
left=$(su_ "ls -lad $M /data/adb/metamodule 2>/dev/null; grep -E \"meta-overlayfs|/data/adb/metamodule| - overlay KSU \" /proc/1/mountinfo; true")
if [ -n "$left" ]; then
  echo "Left after the removal:" >&2
  echo "$left" >&2
  if [ -n "$(su_ "[ -e $M/remove ] && echo pending; true")" ]; then
    echo "Still marked for removal: reboot, then run this script again." >&2
  else
    echo "If no line above is a mount, remove what is left:" >&2
    echo "  adb shell su -c 'rm -rf $M /data/adb/metamodule'" >&2
    echo "A mount line means it is still active: do not delete, ask first." >&2
  fi
  exit 1
fi
echo "meta-overlayfs gone: no folder, no link, no image, no mount."
su_ ksud module list
