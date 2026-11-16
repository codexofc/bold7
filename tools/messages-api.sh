# Sourced by tools/install.sh and tools/uninstall.sh, after they define sh() (adb shell, CR stripped).
# Google Messages' ExternalMessagingApiService for Messages of Bold7:
# the bridge of dev.bold7.sysui opens it, these functions give the rest.
#
# CHANGES THE PHONE'S STATE (each function says what): only from those two scripts, with the phone
# owner's go-ahead for them.
GM=com.google.android.apps.messaging
GM_API_PERMISSION=$GM.EXTERNAL_MESSAGING_API
GM_CONSENT=$GM/.samsung.settings.CmcUserConsentSettingsActivity
# Seconds for Google Messages' screens to show; the tests set 0.
GM_WAIT=${GM_WAIT:-2}

# grant_api_permission <package>: pm grant of EXTERNAL_MESSAGING_API (dangerous, SMS group), read
# back in dumpsys; fails saying so.
grant_api_permission() {
  local pkg=$1
  sh "pm grant $pkg $GM_API_PERMISSION" || true
  if [[ $(sh "dumpsys package $pkg" || true) == *"$GM_API_PERMISSION: granted=true"* ]]; then
    echo "$pkg: granted $GM_API_PERMISSION."
    return 0
  fi
  echo "$pkg: $GM_API_PERMISSION not granted (is Google Messages installed?)." >&2
  return 1
}

# consent_switch: "checked x y" of the switch on Google Messages' consent screen (uiautomator,
# that screen only: id/switchWidget), the centre of its bounds; nothing when it is not there.
consent_switch() {
  sh "uiautomator dump /data/local/tmp/bold7-consent.xml" >/dev/null || true
  sh "cat /data/local/tmp/bold7-consent.xml" | tr '>' '\n' | grep 'id/switchWidget' |
    sed -E 's/.*checked="([a-z]+)".*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4 \5/' |
    awk '{print $1, int(($2+$4)/2), int(($3+$5)/2)}' | head -1 || true
  sh "rm -f /data/local/tmp/bold7-consent.xml" >/dev/null || true
}

# set_consent true|false: "Allow Bixby and text on Galaxy devices", the consent the API asks
# (CMC_USER_CONSENT_SETTINGS), on Google Messages' own screen: there is no call to write it. The
# switch is found by uiautomator, tapped only when it differs, read back, then the page is closed
# (close_consent). It opens in a task of its own, cleared first, so that Back leaves nothing of
# Google Messages behind it. The phone must be unlocked, screen on: refused otherwise, nothing
# touched.
set_consent() {
  local want=$1 checked="" x="" y=""
  if [[ $(sh "dumpsys window" || true) == *isKeyguardShowing=true* ]]; then
    echo "Google Messages: the phone is locked, consent not set to $want (unlock it, then run this again)." >&2
    return 1
  fi
  sh "su -c 'am start --activity-clear-task -n $GM_CONSENT'" >/dev/null
  sleep "$GM_WAIT"
  read -r checked x y <<<"$(consent_switch)" || true
  if [ -n "$checked" ] && [ "$checked" != "$want" ]; then
    sh "input tap $x $y" >/dev/null
    sleep "$GM_WAIT"
    read -r checked x y <<<"$(consent_switch)" || true
  fi
  close_consent
  if [ "$checked" != "$want" ]; then
    echo "Google Messages: consent switch ${checked:-not found}, wanted $want: set it by hand (adb shell su -c 'am start -n $GM_CONSENT')." >&2
    return 1
  fi
  echo "Google Messages: API consent $want."
}

# close_consent: Back on the consent page, read back in the activity stack, once more if it is still
# there: left in Google Messages' task, it showed over the conversation Messages opened there (seen
# on the Q25). Still there after that, it is said, not fatal: Messages
# clears that task when it opens Google Messages.
close_consent() {
  local try
  for try in 1 2; do
    sh "input keyevent KEYCODE_BACK" >/dev/null
    sleep "$GM_WAIT"
    [[ $(sh "dumpsys activity activities" || true) == *CmcUserConsentSettingsActivity* ]] || return 0
  done
  echo "Google Messages: its consent page is still open, close it by hand (Back)." >&2
}

# restart_messages: Google Messages stopped then opened again, so that its main process starts with
# the bridge (Vector hooks a process when it starts), then the home: the way spike S-rcs took, the
# app stopped for the second the two commands take.
restart_messages() {
  sh "am force-stop $GM" >/dev/null
  sh "monkey -p $GM -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 || true
  sleep "$GM_WAIT"
  sh "input keyevent KEYCODE_HOME" >/dev/null
  echo "Google Messages: restarted with the bridge."
}
