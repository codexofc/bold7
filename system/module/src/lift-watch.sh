#!/system/bin/sh
# Bold7: the texts written restricted after the install, readable by the Hub as they come.
#
#   sh lift-watch.sh <moddir>     boot-completed.sh, in the background, until reboot
#
# Android 17 keeps restricted not only a history restored from a Google backup (lift.sh at the
# install): on a user's phone, Google Messages itself kept writing its new MMS (its RCS copies)
# restricted, sent and received, so the Hub never saw them. Once the user's storage is open (the
# database is in it), this lifts what came while the phone was off, then sleeps on inotifyd until
# the database is written, waits for two quiet seconds and runs lift.sh again, which does nothing when
# nothing is restricted. No polling: no write, no wake-up. Android 14 (BenOS) has no restriction:
# nothing is started. Stops at its next event once the module is disabled or marked for removal.
MODDIR=$1
DB=${MMSSMS:-/data/user/0/com.android.providers.telephony/databases/mmssms.db}
BUSYBOX=${BUSYBOX:-/data/adb/ksu/bin/busybox}
GETPROP=${GETPROP:-/system/bin/getprop} RESETPROP=${RESETPROP:-/data/adb/ksu/bin/resetprop}
SQLITE=${SQLITE:-/system/bin/sqlite3}
PROP=sys.user.0.ce_available
[ -d "$MODDIR" ] || exit 2
echo $$ >"$MODDIR/lift-watch.pid"

# The user's storage: resetprop -w sleeps until the property moves.
while :; do
    s=$("$GETPROP" $PROP)
    [ "$s" = true ] && break
    if [ -n "$s" ]; then "$RESETPROP" -w $PROP "$s" >/dev/null 2>&1; else "$RESETPROP" -w $PROP >/dev/null 2>&1; fi
    [ "$("$GETPROP" $PROP)" != "$s" ] || sleep 1
done
[ -x "$SQLITE" ] && [ -f "$DB" ] || exit 0
"$SQLITE" -readonly "$DB" "PRAGMA table_info(sms);" 2>/dev/null | grep -q '|read_restriction|' || exit 0

sh "$MODDIR/lift.sh" "$MODDIR"
# c: modified. Not w (closed after a write): the provider keeps the database open, that never comes.
"$BUSYBOX" inotifyd - "$DB:c" | while read -r _; do
    # A burst of writes (a message, its parts, its thread) is one run.
    while read -r -t 2 _; do :; done
    [ "$(cat "$MODDIR/lift-watch.pid" 2>/dev/null)" = $$ ] || exit 0
    if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then exit 0; fi
    sh "$MODDIR/lift.sh" "$MODDIR"
done
