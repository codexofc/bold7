#!/system/bin/sh
# Bold7: builds the ext4 image that post-fs-data.sh mounts as the lower layer of our overlays.
#
#   sh build-image.sh <tree> <image>       (customize.sh at install; test-mount.sh)
#
# overlayfs refuses a lower layer on /data (f2fs with per-file encryption, docs/DEVICE.md): the
# layer lives in an ext4 image, as meta-overlayfs did. <tree>/<partition>/<dir> lands as
# <partition>/<dir> in the image, owned by root, 755/644, labelled like what it stands beside.
# The image is written to <image>.new then renamed: an image mounted from the same path (update
# of an active module) keeps its own inode and is never touched.
SRC=$1 IMG=$2
NEW=$IMG.new MNT=$IMG.mnt
fail() {
    echo "! layer image: $1"
    umount "$MNT" 2>/dev/null
    rmdir "$MNT" 2>/dev/null
    rm -f "$NEW"
    exit 1
}

# Content plus a quarter plus 8 MiB: ext4 metadata (superblock copies, bitmaps, inode tables,
# directories) of a small image, without journal and without reserved blocks. Too small shows
# as a failed copy below, which aborts the install.
kb=$(du -sk "$SRC" | cut -f1)
case $kb in '' | *[!0-9]*) fail "cannot size $SRC" ;; esac
rm -f "$NEW"
truncate -s $(((kb * 5 / 4 + 8192) * 1024)) "$NEW" || fail "truncate"
# No journal: mounted read-only at boot, it would only take space (and a jbd2 thread).
/system/bin/mke2fs -t ext4 -b 4096 -m 0 -O ^has_journal -F "$NEW" >/dev/null || fail "mke2fs"
# The context loop mounts of KernelSU modules use (meta-overlayfs does the same).
/system/bin/chcon u:object_r:ksu_file:s0 "$NEW" || fail "label of the image file"
mkdir -p "$MNT" && mount -t ext4 -o loop,rw,noatime "$NEW" "$MNT" || fail "loop mount"
cp -a "$SRC"/. "$MNT"/ || fail "copy (image too small?)"
chown -R 0:0 "$MNT" && chmod -R u=rwX,go=rX "$MNT" || fail "owner or mode"
/system/bin/chcon -R u:object_r:system_file:s0 "$MNT" || fail "label"
# /system_ext/overlay and the overlays already in it are vendor_overlay_file on this ROM.
if [ -d "$MNT/system_ext/overlay" ]; then
    /system/bin/chcon -R u:object_r:vendor_overlay_file:s0 "$MNT/system_ext/overlay" || fail "label of system_ext/overlay"
fi
umount "$MNT" || fail "umount"
rmdir "$MNT"
mv -f "$NEW" "$IMG" || fail "rename"
echo "- layer image: $IMG, $(((kb * 5 / 4 + 8192) / 1024)) MiB for $kb KiB of files"
