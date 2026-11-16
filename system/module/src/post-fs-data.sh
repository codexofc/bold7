#!/system/bin/sh
# Bold7, post-fs-data stage: blocking, before zygote starts.
#
# Boot-loop guard. Every boot that reaches this point adds one to boot_count, and
# boot-completed.sh sets it back to 0 once Android has fully started. After two boots in a
# row that never completed, the module disables itself and stops here, so the next boot is
# stock. KernelSU's safe mode (Volume down during boot) remains the last resort.

MODDIR=${0%/*}
umask 077
count=$(cat "$MODDIR/boot_count" 2>/dev/null)
case $count in '' | *[!0-9]*) count=0 ;; esac
if [ "$count" -ge 2 ]; then
    touch "$MODDIR/disable"
    echo 0 >"$MODDIR/boot_count"
    log -p w -t Bold7 "boot-loop guard: $count unfinished boots, module disabled"
    exit 0
fi
echo $((count + 1)) >"$MODDIR/boot_count"

# Mounts (docs/DEVICE.md, "ReZygisk" and "overlayfs"). layer.img, the ext4 image customize.sh built
# at install (overlayfs refuses a lower layer on /data), is loop-mounted read-only on img/. Each
# of its folders <partition>/<dir> is then laid read-only over /<partition>/<dir> by an overlayfs
# named "bold7". In app processes, ReZygisk v1.0.0 detaches only the mounts whose source is KSU,
# whose mount point is under /data/adb/modules or whose root is under /adb/modules/
# (zygiskd/src/utils.c, umount_root). Our overlays have "/" as their root and stay. img/ is under
# /data/adb/modules and is detached in apps, on purpose: one trace less, and each overlay keeps
# its own private clone of its layers from mount time (clone_private_mount), so it reads on.
# All or nothing: on any failure the mounts already made are undone, and Android starts stock.
IMG=$MODDIR/layer.img
MNT=$MODDIR/img
LOG=$MODDIR/bold7.log
: >"$LOG"
say() { log -p "$1" -t Bold7 "$2"; echo "$2" >>"$LOG"; }
mounted=
fail() {
    say e "mount: $1; undoing: ${mounted:-nothing}"
    for t in $mounted; do umount -l "$t" || say e "mount: umount $t failed"; done
    exit 0
}

# A metamodule mounts whole partitions with the source KSU: ReZygisk would detach them in apps,
# and ours with them: the system font would be gone from SystemUI again.
[ -e /data/adb/metamodule ] && fail "a metamodule is installed (/data/adb/metamodule)"

# Owners, modes and SELinux labels are inside the image, set at install (build-image.sh).
[ -f "$IMG" ] || fail "no $IMG"
/system/bin/chcon u:object_r:ksu_file:s0 "$IMG" || fail "label of $IMG"
mkdir -p "$MNT" && mount -t ext4 -o loop,ro,noatime "$IMG" "$MNT" || fail "loop mount of $IMG"
mounted=$MNT

for layer in "$MNT"/*/*; do
    [ -d "$layer" ] || continue
    target=/${layer#"$MNT"/}
    [ -d "$target" ] || fail "$target is not a folder"
    # A mount at or under the target would be hidden by ours, or would hide it.
    if grep -qF " $target " /proc/self/mountinfo || grep -qF " $target/" /proc/self/mountinfo; then
        fail "$target already has a mount"
    fi
    mount -t overlay bold7 -o "ro,lowerdir=$layer:$target" "$target" || fail "overlay on $target"
    mounted="$target $mounted"
done
say i "mounted: ${mounted:-nothing}"
