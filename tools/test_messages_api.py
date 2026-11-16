"""tools/messages-api.sh against a fake adb: Google Messages' consent switch and the API permission.

The fake phone is a JSON file: keyguard shown or not, the consent switch (absent, true or false),
whether pm grant takes, whether the consent page is open and how many Backs it ignores. A tap on the switch's centre flips it; every command is logged, so
each case checks what was asked of the phone.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

FAKE_ADB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    import json, os, sys
    path = os.environ["FAKE_PHONE"]
    phone = json.load(open(path))
    args = sys.argv[1:]
    assert args[0] == "shell" and len(args) == 2, args
    cmd = args[1]
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(cmd + "\n")
    switch = phone["switch"]
    if cmd == "dumpsys window":
        print("  isKeyguardShowing=%s" % ("true" if phone["locked"] else "false"))
    elif cmd.startswith("su -c 'am start --activity-clear-task -n com.google.android.apps.messaging/"):
        phone["page"] = True
        json.dump(phone, open(path, "w"))
        print("Starting: Intent")
    elif cmd == "input keyevent KEYCODE_BACK":
        if phone["stuck"] > 0:
            phone["stuck"] -= 1
        else:
            phone["page"] = False
        json.dump(phone, open(path, "w"))
    elif cmd == "dumpsys activity activities":
        if phone["page"]:
            print("  * Hist  #0: ActivityRecord{1 u0 com.google.android.apps.messaging/.samsung.settings.CmcUserConsentSettingsActivity t9}")
    elif cmd == "uiautomator dump /data/local/tmp/bold7-consent.xml":
        print("UI hierchary dumped")
    elif cmd == "cat /data/local/tmp/bold7-consent.xml":
        node = '' if switch is None else '<node resource-id="android:id/switchWidget" checked="%s" bounds="[600,160][698,214]">' % switch
        print('<hierarchy><node text="Allow Bixby">' + node + '</hierarchy>')
    elif cmd == "input tap 649 187":
        phone["switch"] = "false" if switch == "true" else "true"
        json.dump(phone, open(path, "w"))
    elif cmd.startswith("rm -f "):
        pass
    elif cmd.startswith("pm grant "):
        phone["granted"] = phone["grantable"]
        json.dump(phone, open(path, "w"))
    elif cmd.startswith("dumpsys package "):
        print("      com.google.android.apps.messaging.EXTERNAL_MESSAGING_API: granted=%s" % ("true" if phone["granted"] else "false"))
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
''').lstrip()


class MessagesApiTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.log = os.path.join(self.dir, "log.txt")

    def run_sh(self, call, locked=False, switch="false", grantable=True, stuck=0):
        with open(self.phone, "w") as f:
            json.dump({"locked": locked, "switch": switch, "granted": False, "grantable": grantable, "page": False, "stuck": stuck}, f)
        script = 'set -euo pipefail; sh() { adb shell "$@" | tr -d "\\r"; }; . tools/messages-api.sh; ' + call
        env = dict(os.environ, PATH=self.dir + os.pathsep + os.environ["PATH"], FAKE_PHONE=self.phone, FAKE_LOG=self.log, GM_WAIT="0")
        r = subprocess.run(["bash", "-c", script], cwd=os.path.dirname(HERE), env=env, capture_output=True, text=True)
        asked = []
        if os.path.exists(self.log):
            with open(self.log) as f:
                asked = f.read().splitlines()
        with open(self.phone) as f:
            return r, asked, json.load(f)

    def test_consent_turned_on_and_read_back(self):
        r, asked, phone = self.run_sh("set_consent true")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(phone["switch"], "true")
        self.assertIn("input tap 649 187", asked)
        self.assertEqual(asked[-2:], ["input keyevent KEYCODE_BACK", "dumpsys activity activities"])
        self.assertFalse(phone["page"])
        self.assertIn("consent true", r.stdout)

    def test_consent_already_as_wanted_is_not_tapped(self):
        r, asked, phone = self.run_sh("set_consent true", switch="true")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("input tap 649 187", asked)
        self.assertEqual(phone["switch"], "true")

    def test_consent_turned_off(self):
        r, _, phone = self.run_sh("set_consent false", switch="true")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(phone["switch"], "false")

    def test_locked_phone_touches_nothing(self):
        r, asked, phone = self.run_sh("set_consent true", locked=True)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(asked, ["dumpsys window"])
        self.assertEqual(phone["switch"], "false")
        self.assertIn("locked", r.stderr)

    def test_no_switch_is_said(self):
        r, asked, _ = self.run_sh("set_consent true", switch=None)
        self.assertEqual(r.returncode, 1)
        self.assertNotIn("input tap 649 187", asked)
        self.assertIn("not found", r.stderr)
        self.assertEqual(asked[-2], "input keyevent KEYCODE_BACK")

    def test_consent_page_opens_in_a_cleared_task(self):
        # What Google Messages' task held before (its list, an old consent page) goes with it.
        _, asked, _ = self.run_sh("set_consent true", switch="true")
        self.assertIn("su -c 'am start --activity-clear-task -n com.google.android.apps.messaging/.samsung.settings.CmcUserConsentSettingsActivity'", asked)

    def test_consent_page_still_open_gets_a_second_back(self):
        r, asked, phone = self.run_sh("set_consent true", switch="true", stuck=1)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(asked.count("input keyevent KEYCODE_BACK"), 2)
        self.assertFalse(phone["page"])
        self.assertNotIn("still open", r.stderr)

    def test_consent_page_that_stays_is_said_not_fatal(self):
        r, asked, phone = self.run_sh("set_consent true", switch="true", stuck=5)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(asked.count("input keyevent KEYCODE_BACK"), 2)
        self.assertTrue(phone["page"])
        self.assertIn("still open", r.stderr)

    def test_permission_granted_and_checked(self):
        r, asked, _ = self.run_sh("grant_api_permission dev.bold7.messages")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(asked[0], "pm grant dev.bold7.messages com.google.android.apps.messaging.EXTERNAL_MESSAGING_API")

    def test_permission_refused_is_said(self):
        r, _, _ = self.run_sh("grant_api_permission dev.bold7.messages", grantable=False)
        self.assertEqual(r.returncode, 1)
        self.assertIn("not granted", r.stderr)


if __name__ == "__main__":
    unittest.main()
