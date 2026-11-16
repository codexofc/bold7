"""tools/silence.sh against a fake adb: what poser and retour write, and when they refuse.

The fake phone is a JSON file of global and secure settings. Every settings write is logged, so a
refusal can be checked to have written nothing, and nothing else to be written.
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
    import json, os, shlex, sys
    path = os.environ["FAKE_PHONE"]
    phone = json.load(open(path))
    args = sys.argv[1:]
    if args == ["get-state"]:
        print("device"); sys.exit(0)
    assert args[:2] == ["shell", "-n"], args
    cmd = shlex.split(args[2])
    out = ""
    if cmd[0] == "settings" and cmd[2] in ("global", "secure", "system"):
        space = phone[cmd[2]]
        # FAKE_IGNORE_WRITES: a phone that says nothing and keeps its value.
        ignore = os.environ.get("FAKE_IGNORE_WRITES")
        if cmd[1] == "get":
            out = space.get(cmd[3], "null")
        elif cmd[1] == "put":
            if not ignore:
                space[cmd[3]] = cmd[4]
            phone["writes"].append(cmd[3])
        elif cmd[1] == "delete":
            if not ignore:
                space.pop(cmd[3], None)
            phone["writes"].append(cmd[3])
        else:
            raise SystemExit(f"fake adb: unknown {cmd}")
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
    json.dump(phone, open(path, "w"))
    if out:
        print(out)
''').lstrip()



G, S, Y = "global", "secure", "system"
ORIGINAL = {  # the Q25 as measured
    G: {},  # charging_sounds_enabled unset
    S: {"charging_vibration_enabled": "1"},  # clipboard_show_access_notifications unset (on by default)
    Y: {"lockscreen_sounds_enabled": "1", "sound_effects_enabled": "1", "accelerometer_rotation": "1"},
}
TARGET = [(G, "charging_sounds_enabled"), (S, "charging_vibration_enabled"),
          (Y, "lockscreen_sounds_enabled"), (Y, "sound_effects_enabled"), (Y, "accelerometer_rotation"),
          (S, "clipboard_show_access_notifications")]


class SilenceTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "silence-avant.txt")
        self.write_phone({**json.loads(json.dumps(ORIGINAL)), "writes": []})

    def write_phone(self, p):
        with open(self.phone, "w") as f:
            json.dump(p, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def val(self, space, key):
        return self.read_phone()[space].get(key)

    def put(self, space, key, v):
        p = self.read_phone()
        p[space][key] = v
        p["writes"] = []
        self.write_phone(p)

    def run_silence(self, *args, ignore_writes=False):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, SILENCE_SAVE=self.save)
        if ignore_writes:
            env["FAKE_IGNORE_WRITES"] = "1"
        return subprocess.run(["bash", os.path.join(HERE, "silence.sh"), *args], env=env, capture_output=True, text=True)

    def test_without_argument_only_reads(self):
        r = self.run_silence()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("charging_sounds_enabled=null", r.stdout)
        self.assertIn("sound_effects_enabled=1", r.stdout)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_zeroes_everything_and_saves_the_originals(self):
        r = self.run_silence("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        for s, k in TARGET:
            self.assertEqual(self.val(s, k), "0", k)
        with open(self.save) as f:
            self.assertEqual(sorted(f.read().split("\n")), sorted(["", "charging_sounds_enabled null",
                "charging_vibration_enabled 1", "lockscreen_sounds_enabled 1", "sound_effects_enabled 1",
                "accelerometer_rotation 1", "clipboard_show_access_notifications null"]))

    def test_poser_never_touches_other_settings(self):
        self.run_silence("poser")
        self.assertEqual(sorted(self.read_phone()["writes"]), sorted(k for _, k in TARGET))
        self.assertNotIn("low_battery_sound", self.read_phone()[G])

    def test_poser_then_retour_gives_back_the_exact_values(self):
        self.run_silence("poser")
        r = self.run_silence("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        p = self.read_phone()
        self.assertEqual({k: v for k, v in p.items() if k != "writes"}, ORIGINAL)
        self.assertFalse(os.path.exists(self.save))

    def test_a_setting_already_at_zero_is_neither_saved_nor_touched(self):
        self.put(Y, "sound_effects_enabled", "0")
        self.run_silence("poser")
        with open(self.save) as f:
            self.assertNotIn("sound_effects_enabled", f.read())
        self.assertNotIn("sound_effects_enabled", self.read_phone()["writes"])
        self.run_silence("retour")
        self.assertEqual(self.val(Y, "sound_effects_enabled"), "0")

    def test_retour_keeps_a_later_choice_and_restores_the_rest(self):
        self.run_silence("poser")
        self.put(Y, "sound_effects_enabled", "1")
        r = self.run_silence("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.val(Y, "sound_effects_enabled"), "1")
        self.assertEqual(self.val(S, "charging_vibration_enabled"), "1")
        self.assertNotIn("sound_effects_enabled", self.read_phone()["writes"])
        self.assertIn("kept", r.stdout)

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_silence("poser")
        with open(self.save) as f:
            first = f.read()
        self.assertEqual(self.run_silence("poser").returncode, 0)
        with open(self.save) as f:
            self.assertEqual(f.read(), first)
        self.run_silence("retour")
        self.assertEqual(self.val(Y, "lockscreen_sounds_enabled"), "1")

    def test_poser_refuses_an_unexpected_value_without_writing(self):
        self.put(S, "charging_vibration_enabled", "yes")
        r = self.run_silence("poser")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_fails_when_the_phone_ignores_the_write(self):
        r = self.run_silence("poser", ignore_writes=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("did not take", r.stderr)

    def test_retour_fails_and_keeps_the_save_when_the_phone_ignores_the_write(self):
        self.run_silence("poser")
        r = self.run_silence("retour", ignore_writes=True)
        self.assertEqual(r.returncode, 1)
        self.assertTrue(os.path.exists(self.save))

    def test_retour_refuses_a_broken_save_without_writing(self):
        self.run_silence("poser")
        for bad in ("sound_effects_enabled banana\n", "ringtone 1\n", "sound_effects_enabled 1 2\n"):
            with open(self.save, "w") as f:
                f.write(bad)
            r = self.run_silence("retour")
            self.assertEqual(r.returncode, 2, bad)
            self.assertTrue(os.path.exists(self.save))
        self.assertEqual(self.val(Y, "sound_effects_enabled"), "0")

    def test_retour_without_save_writes_nothing(self):
        r = self.run_silence("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["writes"], [])

    def test_unknown_argument_fails(self):
        self.assertNotEqual(self.run_silence("nope").returncode, 0)


if __name__ == "__main__":
    unittest.main()
