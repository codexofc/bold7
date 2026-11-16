"""tools/trackpad.sh against a fake adb: Q25TrackpadCustomizer's service off and back, the daemon
stopped, the driver's mode and the settings given back.

The fake phone is a JSON file: global and secure settings, the driver's mode, whether
bold7-trackpad runs (and whether it stops on SIGTERM). Every write is logged.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = "enabled_accessibility_services"
CUSTOMIZER = "tech.shroyer.q25trackpadcustomizer/tech.shroyer.q25trackpadcustomizer.AppSwitchService"
FIX = "benos.q25.keymapperbootfix/benos.q25.keymapperbootfix.LockscreenPinEntry"
KM = "io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService"
# The Q25 as read (docs/DEVICE.md).
Q25_LIST = f"{FIX}:{KM}:{CUSTOMIZER}"

FAKE_ADB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    import json, os, shlex, sys
    path = os.environ["FAKE_PHONE"]
    phone = json.load(open(path))
    args = sys.argv[1:]
    if args == ["get-state"]:
        print("device"); sys.exit(0)
    assert args[:2] == ["shell", "-n"], args
    cmd = shlex.split(args[2])
    out, code = "", 0
    if cmd[0] == "settings":
        space = phone[cmd[2]]
        if cmd[1] == "get":
            out = space.get(cmd[3], "null")
        elif cmd[1] == "put":
            space[cmd[3]] = cmd[4]
            phone["writes"].append(cmd[3])
        elif cmd[1] == "delete":
            space.pop(cmd[3], None)
            phone["writes"].append(cmd[3])
        else:
            raise SystemExit(f"fake adb: unknown {cmd}")
    elif cmd[:2] == ["su", "-c"]:
        c = cmd[2]
        if c == "cat /proc/q20_switch_key_mouse":
            out = phone["mode"]
        elif c == "echo 1 > /proc/q20_switch_key_mouse":
            phone["mode"] = "1"
            phone["writes"].append("mode")
        elif c == "pidof bold7-trackpad":
            if phone["running"]:
                out = "4242 4243"
            else:
                code = 1
        elif c == "pkill -f trackpad-start.sh":
            phone["writes"].append("pkill-start")
        elif c == "pkill -TERM -x bold7-trackpad":
            phone["writes"].append("pkill")
            if phone["stops"]:
                phone["running"] = False
                phone["mode"] = "1"  # what the daemon does on SIGTERM
        else:
            raise SystemExit(f"fake adb: unknown {cmd}")
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
    json.dump(phone, open(path, "w"))
    if out:
        print(out)
    sys.exit(code)
''').lstrip()


class TrackpadTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "trackpad-avant.txt")
        self.write_phone({"global": {}, "secure": {KEY: Q25_LIST}, "mode": "1", "running": False, "stops": True, "writes": []})

    def write_phone(self, phone):
        with open(self.phone, "w") as f:
            json.dump(phone, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def change(self, **kw):
        p = self.read_phone()
        p.update(kw)
        p["writes"] = []
        self.write_phone(p)

    def services(self):
        return self.read_phone()["secure"].get(KEY)

    def run_trackpad(self, *args):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, TRACKPAD_SAVE=self.save)
        return subprocess.run(["bash", os.path.join(HERE, "trackpad.sh"), *args], env=env, capture_output=True, text=True)

    def test_without_argument_only_reads(self):
        r = self.run_trackpad()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("mode=1", r.stdout)
        self.assertIn("bold7-trackpad=stopped", r.stdout)
        self.assertIn("bold7_trackpad_horizontal=null", r.stdout)
        self.assertIn("q25trackpadcustomizer-service=on", r.stdout)
        self.assertEqual(self.read_phone()["writes"], [])

    def test_poser_turns_only_the_customizer_off(self):
        r = self.run_trackpad("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.services(), f"{FIX}:{KM}")
        with open(self.save) as f:
            self.assertEqual(f.read(), Q25_LIST + "\n")

    def test_poser_then_retour_gives_back_the_exact_list(self):
        self.run_trackpad("poser")
        r = self.run_trackpad("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.services(), Q25_LIST)
        self.assertFalse(os.path.exists(self.save))

    def test_customizer_alone_is_deleted_then_put_back(self):
        self.change(secure={KEY: CUSTOMIZER})
        self.assertEqual(self.run_trackpad("poser").returncode, 0)
        self.assertIsNone(self.services())
        self.assertEqual(self.run_trackpad("retour").returncode, 0)
        self.assertEqual(self.services(), CUSTOMIZER)

    def test_poser_with_the_service_off_saves_nothing(self):
        self.change(secure={KEY: f"{FIX}:{KM}"})
        r = self.run_trackpad("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_trackpad("poser")
        self.change(secure={KEY: f"{FIX}:{CUSTOMIZER}"})  # turned on again by hand, Key Mapper off
        self.assertEqual(self.run_trackpad("poser").returncode, 0)
        with open(self.save) as f:
            self.assertEqual(f.read(), Q25_LIST + "\n")
        self.assertEqual(self.services(), FIX)

    def test_retour_keeps_later_changes_and_adds_the_service(self):
        self.run_trackpad("poser")
        self.change(secure={KEY: FIX})  # Key Mapper turned off after poser
        r = self.run_trackpad("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.services(), f"{FIX}:{CUSTOMIZER}")
        self.assertFalse(os.path.exists(self.save))

    def test_retour_leaves_a_service_turned_on_again(self):
        self.run_trackpad("poser")
        self.change(secure={KEY: f"{KM}:{CUSTOMIZER}"})
        self.assertEqual(self.run_trackpad("retour").returncode, 0)
        self.assertEqual(self.services(), f"{KM}:{CUSTOMIZER}")

    def test_retour_stops_the_daemon_and_clears_the_settings(self):
        self.change(running=True, mode="0", **{"global": {"bold7_trackpad_horizontal": "50", "bold7_trackpad_off": "0", "bold7_trackpad_palm_band": "120", "bold7_trackpad_palm_delay": "0", "other": "x"}})
        r = self.run_trackpad("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        p = self.read_phone()
        self.assertFalse(p["running"])
        self.assertEqual(p["mode"], "1")
        self.assertEqual(p["global"], {"other": "x"})
        self.assertIn("pkill", p["writes"])
        # A start still waiting for the SIM is stopped first.
        self.assertEqual(p["writes"][0], "pkill-start")
        self.assertIn("No save", r.stdout)

    def test_retour_puts_the_arrow_mode_back_without_a_daemon(self):
        self.change(mode="0")  # both processes killed at once: the driver left in raw mode
        r = self.run_trackpad("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["mode"], "1")

    def test_retour_fails_on_a_daemon_that_does_not_stop_but_goes_on(self):
        self.run_trackpad("poser")
        self.change(running=True, stops=False, mode="0", **{"global": {"bold7_trackpad_vertical": "90"}})
        r = self.run_trackpad("retour")
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("still running", r.stderr)
        p = self.read_phone()
        self.assertEqual(p["mode"], "1")
        self.assertEqual(p["global"], {})
        self.assertEqual(self.services(), Q25_LIST)

    def test_retour_refuses_a_broken_save(self):
        self.run_trackpad("poser")
        for name, content in {"empty": "", "two lines": f"{Q25_LIST}\n{Q25_LIST}\n", "no service": f"{FIX}\n", "an apostrophe": f"{Q25_LIST}'\n"}.items():
            with self.subTest(name):
                with open(self.save, "w") as f:
                    f.write(content)
                self.change(secure={KEY: f"{FIX}:{KM}"})
                r = self.run_trackpad("retour")
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertNotIn(KEY, self.read_phone()["writes"])
                self.assertTrue(os.path.exists(self.save))


if __name__ == "__main__":
    unittest.main()
