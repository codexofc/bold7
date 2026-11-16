"""tools/touches.sh against a fake adb: what poser and retour write, and when they refuse.

The fake phone is a JSON file of system settings. Every settings write is logged, so a refusal can be
checked to have written nothing.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = "end_button_behavior"

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
    if cmd[0] == "settings" and cmd[2] == "system":
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


class TouchesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "touches-avant.txt")
        # The Q25 as read: 2, sleep.
        self.write_phone({"system": {KEY: "2"}, "writes": []})

    def write_phone(self, phone):
        with open(self.phone, "w") as f:
            json.dump(phone, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def value(self):
        return self.read_phone()["system"].get(KEY)

    def set_value(self, value):
        p = self.read_phone()
        if value is None:
            p["system"].pop(KEY, None)
        else:
            p["system"][KEY] = value
        p["writes"] = []
        self.write_phone(p)

    def run_touches(self, *args, ignore_writes=False):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, TOUCHES_SAVE=self.save)
        if ignore_writes:
            env["FAKE_IGNORE_WRITES"] = "1"
        return subprocess.run(["bash", os.path.join(HERE, "touches.sh"), *args], env=env, capture_output=True, text=True)

    def saved(self):
        with open(self.save) as f:
            return f.read()

    def test_without_argument_only_reads(self):
        r = self.run_touches()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(f"{KEY}=2", r.stdout)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_sets_home_and_saves_the_original(self):
        r = self.run_touches("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "1")
        self.assertEqual(self.saved(), "2\n")
        self.assertEqual(self.read_phone()["writes"], [KEY])

    def test_poser_then_retour_gives_back_the_exact_value(self):
        self.run_touches("poser")
        r = self.run_touches("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "2")
        self.assertFalse(os.path.exists(self.save))

    def test_unset_value_is_deleted_again(self):
        self.set_value(None)
        self.assertEqual(self.run_touches("poser").returncode, 0)
        self.assertEqual(self.value(), "1")
        self.assertEqual(self.run_touches("retour").returncode, 0)
        self.assertIsNone(self.value())

    def test_retour_keeps_a_later_choice(self):
        self.run_touches("poser")
        self.set_value("3")  # set again by hand after poser
        r = self.run_touches("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "3")
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertIn("kept", r.stdout)
        self.assertFalse(os.path.exists(self.save))

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_touches("poser")
        self.assertEqual(self.run_touches("poser").returncode, 0)
        self.assertEqual(self.saved(), "2\n")
        self.assertEqual(self.value(), "1")
        self.assertEqual(self.run_touches("retour").returncode, 0)
        self.assertEqual(self.value(), "2")

    def test_poser_refuses_an_unexpected_value_without_writing(self):
        for value in ["true", "2'", "", "-1"]:
            with self.subTest(value):
                self.setUp()
                self.set_value(value)
                self.assertNotEqual(self.run_touches("poser").returncode, 0)
                self.assertEqual(self.read_phone()["writes"], [])
                self.assertFalse(os.path.exists(self.save))

    def test_poser_refuses_home_without_a_save(self):
        # Already 1 and nothing saved: the original value is unknown, 1 would be saved as it.
        self.set_value("1")
        r = self.run_touches("poser")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_fails_when_the_phone_ignores_the_write(self):
        r = self.run_touches("poser", ignore_writes=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.value(), "2")
        self.assertEqual(self.saved(), "2\n")

    def test_retour_fails_and_keeps_the_save_when_the_phone_ignores_the_write(self):
        self.run_touches("poser")
        r = self.run_touches("retour", ignore_writes=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.value(), "1")
        self.assertEqual(self.saved(), "2\n")

    def test_retour_refuses_a_broken_save_without_writing(self):
        self.run_touches("poser")
        for name, content in {
            "empty": "",
            "two lines": "2\n2\n",
            "not a number": "true\n",
            "an apostrophe": "2'\n",
        }.items():
            with self.subTest(name):
                with open(self.save, "w") as f:
                    f.write(content)
                self.set_value("1")
                r = self.run_touches("retour")
                self.assertEqual(r.returncode, 2, r.stderr)  # a broken save: tools/uninstall.sh says so
                self.assertEqual(self.read_phone()["writes"], [])
                self.assertTrue(os.path.exists(self.save))

    def test_retour_without_save_writes_nothing(self):
        self.set_value("1")
        r = self.run_touches("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertEqual(self.value(), "1")

    def test_unknown_argument_fails(self):
        self.assertNotEqual(self.run_touches("hide").returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])


if __name__ == "__main__":
    unittest.main()
