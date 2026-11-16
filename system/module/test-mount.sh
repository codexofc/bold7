#!/usr/bin/env bash
# Tries, as root, in a scratch folder of /data/local/tmp, exactly the mounts of the module: the
# ext4 image built by build-image.sh, loop-mounted read-only, then an overlayfs "bold7" with its
# folder over a target. Run by KernelSU's busybox in standalone mode, the shell of the installer
# and of module scripts. The target is a tmpfs: overlayfs refuses /data as a layer, and /system
# must not be touched. Every mount is undone at the end. Exits non-zero on failure.
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-mount-test
BB="ASH_STANDALONE=1 /data/adb/ksu/bin/busybox sh"
# -M: the global mount namespace, so each su call sees the mounts of the previous ones.
su_() { adb shell "su -M -c '$*'" | tr -d '\r'; }
cleanup() { su_ "umount -l $T/target/dir 2>/dev/null; umount -l $T/img 2>/dev/null; umount -l $T/target 2>/dev/null; rm -rf $T"; }
expect() { [ "$2" = "$3" ] && echo "ok   $1" || { echo "FAIL $1: got '$2', expected '$3'"; cleanup; exit 1; }; }

cleanup
su_ "mkdir -p $T/tree/part/dir/sub $T/target $T/img && echo layer > $T/tree/part/dir/f && echo ours > $T/tree/part/dir/same && echo deep > $T/tree/part/dir/sub/g"
adb push src/build-image.sh /data/local/tmp/bold7-build-image.sh >/dev/null
su_ "mv /data/local/tmp/bold7-build-image.sh $T/build-image.sh"

su_ "$BB $T/build-image.sh $T/tree $T/layer.img"
expect "image built, nothing left beside it" "$(su_ "ls $T" | tr '\n' ' ')" "build-image.sh img layer.img target tree "

# The same two mounts as post-fs-data.sh, on a target that already holds a file of its own.
su_ "mount -t tmpfs tmpfs $T/target && mkdir $T/target/dir && echo stock > $T/target/dir/orig && echo stock > $T/target/dir/same"
su_ "$BB -c \"/system/bin/chcon u:object_r:ksu_file:s0 $T/layer.img && mount -t ext4 -o loop,ro,noatime $T/layer.img $T/img\""
expect "image mounted read-only" "$(su_ "grep \" $T/img \" /proc/self/mountinfo | grep -c \" ro[ ,]\"")" "1"
expect "labels and modes inside the image" "$(su_ "ls -lZ $T/img/part/dir/f" | awk '{print $1, $5}')" "-rw-r--r-- u:object_r:system_file:s0"
su_ "$BB -c \"mount -t overlay bold7 -o ro,lowerdir=$T/img/part/dir:$T/target/dir $T/target/dir\""
expect "overlay named bold7"        "$(su_ "grep -c \" - overlay bold7 \" /proc/self/mountinfo")" "1"
expect "our file seen through it"     "$(su_ "cat $T/target/dir/f")" "layer"
expect "our subfolder too"            "$(su_ "cat $T/target/dir/sub/g")" "deep"
expect "the target's own file stays"  "$(su_ "cat $T/target/dir/orig")" "stock"
expect "ours wins over a same name"   "$(su_ "cat $T/target/dir/same")" "ours"
expect "read-only"                    "$(su_ "touch $T/target/dir/x 2>/dev/null && echo writable || echo read-only")" "read-only"
# What ReZygisk does in apps: the image mount goes, the overlay reads on (pinned layers).
su_ "umount -l $T/img"
expect "overlay survives the image unmount" "$(su_ "cat $T/target/dir/f")" "layer"
cleanup
expect "nothing left" "$(su_ "grep -c -e \" - overlay bold7 \" -e $T /proc/self/mountinfo; true")" "0"
