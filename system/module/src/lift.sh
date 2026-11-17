#!/system/bin/sh
# Bold7: the text messages a Google backup restored at the phone's setup, readable by the Hub.
#
#   sh lift.sh <moddir>     setup.sh access, in root, once the user's storage is open
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
rows | CLASSPATH=$apk:/system/framework/content.jar "$TOOLS/app_process" "$TOOLS" dev.bold7.hub.RestrictedMessages >/dev/null 2>&1
after=$(count)
set -- $before $after
msg="  Messages restored before Bold7: $(($1 - $3)) SMS and $(($2 - $4)) MMS made readable to the Hub"
[ "$3 $4" = "0 0" ] || msg="$msg, $3 SMS and $4 MMS still restricted"
say "$msg"
[ "$3 $4" = "0 0" ]
