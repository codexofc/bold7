#!/system/bin/sh
# Bold7: the user's own icons pack, taken at install (customize.sh). The zip a user is given beside
# the 9900's file may hold a folder Bold7-icons/ of <package>.png (old icons of apps, never shipped
# by Bold7: their owners' works); found in Download/ (the folder, unpacked at the top or one level
# down, or still in its zip), its PNGs are copied to Bold7/icons/ of the shared storage, where the
# launcher reads them (core/icons UserIcons.kt).
#
#   sh icons.sh <moddir>
#
# Only a file named <package>.png, with a PNG signature, of at most 1 MB, is copied; a file already
# in Bold7/icons/ is never replaced (the user's own choice). The copies get the owner, group and
# label of Download/, as the files the user puts there himself. Prints one line for the manager
# when a pack was found, nothing otherwise; never fails the install. Removing Bold7 leaves
# Bold7/icons/ alone: the user's files.
MOD=$1
DOWNLOAD=${DOWNLOAD:-/data/media/0/Download}
DEST=${ICONS:-/data/media/0/Bold7/icons}
MAX=1048576
T=$MOD/icons-pack

named() { echo "${1%.png}" | grep -qE '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$' && [ "${1%.png}" != "$1" ]; }
signed() { [ "$(head -c 8 "$1" | od -An -tx1 | tr -d ' \n')" = 89504e470d0a1a0a ]; }

# The zips' PNGs of Bold7-icons/, by their listing: only names and sizes that would be copied are unpacked.
rm -rf "$T"
for z in "$DOWNLOAD"/*.zip; do
    [ -f "$z" ] || continue
    want=$(/system/bin/unzip -l "$z" 2>/dev/null | awk -v max=$MAX '$NF ~ /(^|\/)Bold7-icons\/[^\/]+\.png$/ && $1 > 8 && $1 <= max { print $NF }')
    [ -n "$want" ] || continue
    found=1
    mkdir -p "$T/zip" && /system/bin/unzip -o -j -q "$z" $want -d "$T/zip" 2>/dev/null
done

copied=0; kept=0; refused=0; seen=
owner=$(stat -c %u:%g "$DOWNLOAD" 2>/dev/null)
label=$(/system/bin/ls -Zd "$DOWNLOAD" 2>/dev/null | awk '{ print $1 }')
for f in "$DOWNLOAD/Bold7-icons"/* "$DOWNLOAD"/*/Bold7-icons/* "$T/zip"/*; do
    [ -f "$f" ] || continue
    found=1
    b=${f##*/}
    case $b in *.png) ;; *) continue ;; esac # SOURCES.txt and the like
    size=$(stat -c %s "$f")
    if ! named "$b" || [ "$size" -le 8 ] || [ "$size" -gt $MAX ] || ! signed "$f"; then refused=$((refused + 1)); continue; fi
    case " $seen " in *" $b "*) continue ;; esac # the same file in the folder and in the zip
    seen="$seen $b"
    if [ -e "$DEST/$b" ]; then kept=$((kept + 1)); continue; fi
    if [ ! -d "$DEST" ]; then
        mkdir -p "$DEST" && chmod 2770 "$DEST" "${DEST%/*}" && chown "$owner" "$DEST" "${DEST%/*}" && /system/bin/chcon "$label" "$DEST" "${DEST%/*}" ||
            { echo "      Icons: Bold7 could not make the folder ${DEST#/data/media/0/}, none copied"; rm -rf "$T"; exit 0; }
    fi
    if cp "$f" "$DEST/$b" && chmod 0660 "$DEST/$b" && chown "$owner" "$DEST/$b" && /system/bin/chcon "$label" "$DEST/$b"; then
        copied=$((copied + 1))
    else
        rm -f "$DEST/$b"; refused=$((refused + 1))
    fi
done
rm -rf "$T"
[ -n "$found" ] || exit 0
line="      Icons: $copied copied to ${DEST#/data/media/0/}"
[ $kept = 0 ] || line="$line, $kept already there kept"
[ $refused = 0 ] || line="$line, $refused not a PNG icon skipped"
echo "$line"
