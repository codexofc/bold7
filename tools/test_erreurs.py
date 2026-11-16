"""tools/erreurs.sh against a fake adb: what poser and retour write, and when they refuse.

The fake phone is a JSON file of global and secure settings. Every settings write is logged, so a
refusal can be checked to have written nothing, and hide_error_dialogs never to be written.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
KEY = "show_first_crash_dialog"

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
    if cmd[0] == "settings" and cmd[2] in ("global", "secure"):
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


class ErreursTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "erreurs-avant.txt")
        # The Q25 as measured: 1 (AOSP: 0), hide_error_dialogs and the developer
        # option unset.
        self.write_phone({"global": {KEY: "1"}, "secure": {}, "writes": []})

    def write_phone(self, phone):
        with open(self.phone, "w") as f:
            json.dump(phone, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def value(self):
        return self.read_phone()["global"].get(KEY)

    def set_value(self, value):
        p = self.read_phone()
        if value is None:
            p["global"].pop(KEY, None)
        else:
            p["global"][KEY] = value
        p["writes"] = []
        self.write_phone(p)

    def run_erreurs(self, *args, ignore_writes=False):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, ERREURS_SAVE=self.save)
        if ignore_writes:
            env["FAKE_IGNORE_WRITES"] = "1"
        return subprocess.run(["bash", os.path.join(HERE, "erreurs.sh"), *args], env=env, capture_output=True, text=True)

    def test_without_argument_only_reads(self):
        r = self.run_erreurs()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(f"{KEY}=1", r.stdout)
        self.assertIn("hide_error_dialogs=null", r.stdout)
        self.assertIn("show_first_crash_dialog_dev_option=null", r.stdout)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_sets_aosps_zero_and_saves_the_original(self):
        r = self.run_erreurs("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "0")
        with open(self.save) as f:
            self.assertEqual(f.read(), "1\n")
        self.assertEqual(self.read_phone()["writes"], [KEY])

    def test_poser_then_retour_gives_back_the_exact_value(self):
        self.run_erreurs("poser")
        r = self.run_erreurs("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "1")
        self.assertFalse(os.path.exists(self.save))

    def test_unset_value_is_deleted_again(self):
        self.set_value(None)
        self.assertEqual(self.run_erreurs("poser").returncode, 0)
        self.assertEqual(self.value(), "0")
        self.assertEqual(self.run_erreurs("retour").returncode, 0)
        self.assertIsNone(self.value())

    def test_hide_error_dialogs_is_never_written(self):
        self.run_erreurs("poser")
        self.run_erreurs("retour")
        self.run_erreurs("retour")
        p = self.read_phone()
        self.assertNotIn("hide_error_dialogs", p["global"])
        self.assertEqual(p["secure"], {})

    def test_retour_keeps_a_later_choice(self):
        self.run_erreurs("poser")
        self.set_value("2")  # set again by hand after poser
        r = self.run_erreurs("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.value(), "2")
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertIn("kept", r.stdout)
        self.assertFalse(os.path.exists(self.save))

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_erreurs("poser")
        self.assertEqual(self.run_erreurs("poser").returncode, 0)
        with open(self.save) as f:
            self.assertEqual(f.read(), "1\n")
        self.assertEqual(self.value(), "0")
        self.assertEqual(self.run_erreurs("retour").returncode, 0)
        self.assertEqual(self.value(), "1")

    def test_poser_refuses_an_unexpected_value_without_writing(self):
        for value in ["true", "1'", "", "-1"]:
            with self.subTest(value):
                self.setUp()
                self.set_value(value)
                self.assertNotEqual(self.run_erreurs("poser").returncode, 0)
                self.assertEqual(self.read_phone()["writes"], [])
                self.assertFalse(os.path.exists(self.save))

    def test_poser_refuses_zero_without_a_save(self):
        # Already 0 and nothing saved: the original value is unknown, 0 would be saved as it.
        self.set_value("0")
        r = self.run_erreurs("poser")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_fails_when_the_phone_ignores_the_write(self):
        r = self.run_erreurs("poser", ignore_writes=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.value(), "1")
        with open(self.save) as f:
            self.assertEqual(f.read(), "1\n")

    def test_retour_fails_and_keeps_the_save_when_the_phone_ignores_the_write(self):
        self.run_erreurs("poser")
        r = self.run_erreurs("retour", ignore_writes=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.value(), "0")
        with open(self.save) as f:
            self.assertEqual(f.read(), "1\n")

    def test_retour_refuses_a_broken_save_without_writing(self):
        self.run_erreurs("poser")
        for name, content in {
            "empty": "",
            "two lines": "1\n1\n",
            "not a number": "true\n",
            "an apostrophe": "1'\n",
        }.items():
            with self.subTest(name):
                with open(self.save, "w") as f:
                    f.write(content)
                self.set_value("0")
                r = self.run_erreurs("retour")
                self.assertEqual(r.returncode, 2, r.stderr)  # a broken save: tools/uninstall.sh says so
                self.assertEqual(self.read_phone()["writes"], [])
                self.assertTrue(os.path.exists(self.save))

    def test_retour_without_save_writes_nothing(self):
        self.set_value("0")
        r = self.run_erreurs("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertEqual(self.value(), "0")

    def test_unknown_argument_fails(self):
        self.assertNotEqual(self.run_erreurs("hide").returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])


if __name__ == "__main__":
    unittest.main()
