#!/system/bin/sh
# Bold7: the text messages a Google backup restored at the phone's setup, readable by the Hub.
#
#   sh lift.sh <moddir>     setup.sh access, in root, once the user's storage is open
#   system/module/test-lift.sh   its test, from the Mac, against fakes and a throwaway base
#
# Android 17 marks the SMS and MMS rows that the SMS app did not write itself as restricted
# (read_restriction): only the SMS app reads them, and the Hub, with READ_SMS, saw none of a
# restored history (a user's 23,660 SMS and 8,903 MMS, all restricted; what Google Messages wrote
# since, not). The ids are read here, read-only (the provider refuses the column in a where); the
# Hub's RestrictedMessages lifts each row through the provider, as `content update` does, in one
# process. Nothing else of a row changes. Android 14 (BenOS) has no such column: nothing to do.
# Says one line when it lifted something; fails only when rows stay restricted.
MOD=$1
TOOLS=${TOOLS:-/system/bin}
DB=${MMSSMS:-/data/user/0/com.android.providers.telephony/databases/mmssms.db}
SQLITE=${SQLITE:-$TOOLS/sqlite3}
HUB=dev.bold7.hub

say() { echo "$*"; "$TOOLS/log" -p i -t Bold7 "$*" 2>/dev/null; }
sql() { "$SQLITE" -readonly "$DB" "$1" 2>/dev/null; }

[ -x "$SQLITE" ] && [ -f "$DB" ] || exit 0
sql "PRAGMA table_info(sms);" | grep -q '|read_restriction|' || exit 0
rows() { sql "SELECT 'content://sms ' || _id FROM sms WHERE read_restriction <> 0; SELECT 'content://mms ' || _id FROM pdu WHERE read_restriction <> 0;"; }
count() { sql "SELECT (SELECT COUNT(*) FROM sms WHERE read_restriction <> 0) || ' ' || (SELECT COUNT(*) FROM pdu WHERE read_restriction <> 0);"; }
before=$(count)
[ "$before" != "0 0" ] && [ -n "$before" ] || exit 0
apk=$("$TOOLS/pm" path $HUB 2>/dev/null | sed -n 's/^package://p' | head -1)
[ -n "$apk" ] || { say "! messages restricted ($before SMS MMS): $HUB missing, left as they are"; exit 1; }
# app_process needs Android's boot classpath. A shell has it; some KernelSU builds (3.3.1 on a user's
# Q25) start the boot scripts without it, and the lifter then did nothing at boot while it worked
# from adb: taken from Zygote's environment when it is missing.
if [ -z "$BOOTCLASSPATH" ]; then
    for p in $(pidof zygote64 zygote 2>/dev/null); do
        for v in $(tr '\0' '\n' <"/proc/$p/environ" | grep -E '^(BOOTCLASSPATH|DEX2OATBOOTCLASSPATH|SYSTEMSERVERCLASSPATH|ANDROID_(ART|I18N|TZDATA|DATA|ROOT|STORAGE|ASSETS)_?[A-Z]*)=[^ ]*$'); do export "$v"; done
        break
    done
fi
err=${TMPDIR:-/data/local/tmp}/bold7-lift.err
# In batches, one short process each, the rows read again each time (those lifted leave the list):
# one process for a whole history was killed (SIGKILL) on a user's phone before lifting anything,
# while one row lifted fine. A batch lifted is kept; the loop stops when a batch lifts nothing.
: >"$err"
left=$before
while :; do
    rows | head -n "${LIFT_BATCH:-300}" |
        CLASSPATH=$apk:/system/framework/content.jar "$TOOLS/app_process" "$TOOLS" dev.bold7.hub.RestrictedMessages >/dev/null 2>>"$err"
    now=$(count)
    [ "$now" != "$left" ] && [ "$now" != "0 0" ] || break
    left=$now
done
after=$(count)
set -- $before $after
msg="  Messages Android kept from the Hub: $(($1 - $3)) SMS and $(($2 - $4)) MMS made readable to the Hub"
[ "$3 $4" = "0 0" ] || msg="$msg, $3 SMS and $4 MMS still restricted"
say "$msg"
# What went wrong, in bold7.log: the first lines of the lifter's errors, never a message's content
# (it only prints exceptions).
if [ "$3 $4" != "0 0" ] && [ -s "$err" ]; then head -n 8 "$err" | while read -r l; do say "! lift: $l"; done; fi
rm -f "$err"
[ "$3 $4" = "0 0" ]
