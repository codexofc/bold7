#!/usr/bin/env bash
# Runs the lifter of the restored messages (lift.sh) with the phone's own sh, in a scratch folder of
# /data/local/tmp, against fakes: a throwaway mmssms.db made there by the phone's sqlite3 (sms and
# pdu, some rows restricted), a pm that knows the Hub or not, an app_process that lifts the rows it
# is given in that base (all, the SMS only, or none), a log that does nothing. The real base of the
# messages and the real provider are never read nor written. As the shell user, except the last
# case: lift.sh without BOOTCLASSPATH, in root, which takes it from Zygote. Exits non-zero on failure.
#   system/module/test-lift.sh
set -euo pipefail
cd "$(dirname "$0")"
T=/data/local/tmp/bold7-lift-test
trap 'adb shell "su -c \"rm -rf $T\""' EXIT
adb shell "su -c 'rm -rf $T' && mkdir -p $T" && adb push src/lift.sh $T/ >/dev/null

# Three bases to copy before each case: restricted rows (SMS 2, 4, 5 and MMS 1, 3), none
# restricted, and Android 14's without the column.
adb shell "cd $T && /system/bin/sh -s" <<'EOF'
S=/system/bin/sqlite3
$S restricted.db "CREATE TABLE sms(_id INTEGER PRIMARY KEY, body TEXT, read_restriction INTEGER DEFAULT 0);
  CREATE TABLE pdu(_id INTEGER PRIMARY KEY, read_restriction INTEGER DEFAULT 0);
  INSERT INTO sms VALUES(1,'a',0),(2,'b',65536),(3,'c',0),(4,'d',65536),(5,'e',65536);
  INSERT INTO pdu VALUES(1,65536),(2,0),(3,65536);"
cp restricted.db clear.db && $S clear.db "UPDATE sms SET read_restriction=0; UPDATE pdu SET read_restriction=0;"
$S a14.db "CREATE TABLE sms(_id INTEGER PRIMARY KEY, body TEXT); CREATE TABLE pdu(_id INTEGER PRIMARY KEY);
  INSERT INTO sms VALUES(1,'a'); INSERT INTO pdu VALUES(1);"
EOF
# pm path dev.bold7.hub: a made-up APK when the file hub says yes.
adb shell "cat > $T/pm" <<'EOF'
[ "$1 $2" = "path dev.bold7.hub" ] && [ "$(cat ${0%/*}/hub)" = yes ] && echo package:/data/app/fake/base.apk; true
EOF
# app_process: says what it was run with (arguments, CLASSPATH, whether BOOTCLASSPATH is set), keeps
# its input, then lifts its rows by mode: all; sms (each MMS fails on stderr); fail (ten error
# lines, nothing lifted). The input is read whole first: lift.sh's sqlite3 has closed the base.
adb shell "cat > $T/app_process" <<'EOF'
T=${0%/*}
echo "$1 $2 $CLASSPATH bcp=${BOOTCLASSPATH:+set}" > $T/seen
cat > $T/in
mode=$(cat $T/mode)
if [ "$mode" = fail ]; then
  for i in 1 2 3 4 5 6 7 8 9 10; do echo "java.lang.SecurityException: refused $i" >&2; done; exit 1
fi
while read -r uri id; do
  case $uri in
    content://sms) t=sms ;;
    content://mms) [ "$mode" = sms ] && { echo "java.lang.IllegalArgumentException: mms $id" >&2; continue; }; t=pdu ;;
    *) echo "bad line: $uri $id" >&2; continue ;;
  esac
  /system/bin/sqlite3 $T/mmssms.db "UPDATE $t SET read_restriction=0 WHERE _id=$id;"
done < $T/in
EOF
# run.sh <base|none> <mode> <hub>: lift.sh on a fresh copy, then its exit code, what app_process
# saw and was given, the rows still restricted, and a leftover error file.
adb shell "cat > $T/run.sh" <<'EOF'
T=${0%/*}; cd $T || exit 1
rm -f mmssms.db* seen in bold7-lift.err
[ "$1" = none ] || cp "$1.db" mmssms.db
echo "$2" > mode; echo "$3" > hub
TOOLS=$T SQLITE=/system/bin/sqlite3 MMSSMS=$T/mmssms.db TMPDIR=$T sh lift.sh $T 2>&1; echo "rc=$?"
[ -f seen ] && echo "seen: $(cat seen)"
[ -f in ] && echo "in: $(tr '\n' ' ' < in)"
[ -f mmssms.db ] && /system/bin/sqlite3 mmssms.db "SELECT 'left sms=' || ifnull((SELECT group_concat(_id) FROM sms WHERE read_restriction <> 0), '-')
  || ' mms=' || ifnull((SELECT group_concat(_id) FROM pdu WHERE read_restriction <> 0), '-');" 2>/dev/null
[ -e bold7-lift.err ] && echo leftover; true
EOF
adb shell "echo 'exit 0' > $T/log; chmod 755 $T/pm $T/app_process $T/log"

bad=0
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else printf 'FAIL %s: got\n%s\nexpected\n%s\n' "$1" "$2" "$3"; bad=1; fi; }
run() { adb shell "sh $T/run.sh $*" | tr -d '\r'; }
lines() { printf '%s\n' "$@"; }
SEEN="seen: $T dev.bold7.hub.RestrictedMessages /data/app/fake/base.apk:/system/framework/content.jar bcp=set"
IN="in: content://sms 2 content://sms 4 content://sms 5 content://mms 1 content://mms 3 "

expect "no base: nothing done, nothing said" "$(run none all yes)" "rc=0"
expect "Android 14, no read_restriction: nothing done" "$(run a14 all yes)" "rc=0"
expect "nothing restricted: nothing done" "$(run clear all yes)" "$(lines rc=0 'left sms=- mms=-')"
expect "all lifted: the line, code 0" "$(run restricted all yes)" "$(lines \
  '  Messages Android kept from the Hub: 3 SMS and 2 MMS made readable to the Hub' rc=0 "$SEEN" "$IN" 'left sms=- mms=-')"
expect "MMS refused: what stays, the errors, code 1" "$(run restricted sms yes)" "$(lines \
  '  Messages Android kept from the Hub: 3 SMS and 0 MMS made readable to the Hub, 0 SMS and 2 MMS still restricted' \
  '! lift: java.lang.IllegalArgumentException: mms 1' '! lift: java.lang.IllegalArgumentException: mms 3' \
  rc=1 "$SEEN" "$IN" 'left sms=- mms=1,3')"
errs=(); for i in 1 2 3 4 5 6 7 8; do errs+=("! lift: java.lang.SecurityException: refused $i"); done
expect "lifter fails: its first eight error lines, code 1" "$(run restricted fail yes)" "$(lines \
  '  Messages Android kept from the Hub: 0 SMS and 0 MMS made readable to the Hub, 3 SMS and 2 MMS still restricted' \
  "${errs[@]}" rc=1 "$SEEN" "$IN" 'left sms=2,4,5 mms=1,3')"
expect "Hub missing: said, code 1, nothing lifted" "$(run restricted all no)" "$(lines \
  '! messages restricted (3 2 SMS MMS): dev.bold7.hub missing, left as they are' rc=1 'left sms=2,4,5 mms=1,3')"
expect "no BOOTCLASSPATH (KernelSU 3.3.1): taken from Zygote, all lifted" \
  "$(adb shell "su -c 'env -u BOOTCLASSPATH sh $T/run.sh restricted all yes'" | tr -d '\r')" "$(lines \
  '  Messages Android kept from the Hub: 3 SMS and 2 MMS made readable to the Hub' rc=0 "$SEEN" "$IN" 'left sms=- mms=-')"

exit $bad
