"""tools/dialer.sh against a fake adb: who holds DIALER after poser and retour, and when they refuse.

The fake phone is a JSON file: the installed packages, the DIALER role's holder, and the role
changes asked, so that a refusal can be checked to have changed nothing.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
OURS = "dev.bold7.phone"
GOOGLE = "com.google.android.dialer"
ROLE = "android.app.role.DIALER"

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
    if cmd[:3] == ["cmd", "role", "get-role-holders"] and cmd[3] == "android.app.role.DIALER":
        out = phone["holder"]
    elif cmd[:3] == ["cmd", "role", "add-role-holder"] and cmd[3] == "android.app.role.DIALER":
        phone["asked"].append(cmd[4])
        if phone.get("timeouts", 0) > 0:
            # Right after boot the role service can time out (tools/uninstall.sh, HOME).
            phone["timeouts"] -= 1
            out = "java.util.concurrent.TimeoutException"
        elif cmd[4] in phone["installed"] and not os.environ.get("FAKE_IGNORE_WRITES"):
            phone["holder"] = cmd[4]
    elif cmd[:2] == ["pm", "path"]:
        if cmd[2] in phone["installed"]:
            out = "package:/data/app/" + cmd[2] + "/base.apk"
    elif cmd[:2] == ["telecom", "get-default-dialer"]:
        out = phone["holder"]
    elif cmd[:2] == ["telecom", "get-system-dialer"]:
        out = "com.google.android.dialer"
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
    json.dump(phone, open(path, "w"))
    if out:
        print(out)
''').lstrip()


class DialerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "dialer-avant.txt")
        # The Q25 as read: Google's Phone holds DIALER, ours installed.
        self.write_phone({"installed": [GOOGLE, OURS], "holder": GOOGLE, "asked": []})

    def write_phone(self, phone):
        with open(self.phone, "w") as f:
            json.dump(phone, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def change(self, **kw):
        p = self.read_phone()
        p.update(kw)
        p["asked"] = []
        self.write_phone(p)

    def holder(self):
        return self.read_phone()["holder"]

    def run_dialer(self, *args, ignore_writes=False):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, DIALER_SAVE=self.save, DIALER_WAIT="0")
        if ignore_writes:
            env["FAKE_IGNORE_WRITES"] = "1"
        return subprocess.run(["bash", os.path.join(HERE, "dialer.sh"), *args], env=env, capture_output=True, text=True)

    def test_without_argument_only_reads(self):
        r = self.run_dialer()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(f"DIALER={GOOGLE}", r.stdout)
        self.assertIn(f"system dialer={GOOGLE}", r.stdout)
        self.assertEqual(self.read_phone()["asked"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_gives_the_role_to_bold7_and_saves_googles(self):
        r = self.run_dialer("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), OURS)
        with open(self.save) as f:
            self.assertEqual(f.read(), GOOGLE + "\n")

    def test_poser_then_retour_gives_it_back(self):
        self.run_dialer("poser")
        r = self.run_dialer("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), GOOGLE)
        self.assertFalse(os.path.exists(self.save))

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_dialer("poser")
        self.assertEqual(self.run_dialer("poser").returncode, 0)
        with open(self.save) as f:
            self.assertEqual(f.read(), GOOGLE + "\n")
        self.assertEqual(self.holder(), OURS)

    def test_poser_refuses_without_the_app(self):
        self.change(installed=[GOOGLE])
        r = self.run_dialer("poser")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["asked"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_refuses_when_bold7_holds_it_and_nothing_is_saved(self):
        self.change(holder=OURS)
        r = self.run_dialer("poser")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["asked"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_poser_refuses_an_unexpected_holder(self):
        for holder in ["", "com.example'x", "not a package"]:
            with self.subTest(holder):
                self.setUp()
                self.change(holder=holder)
                self.assertNotEqual(self.run_dialer("poser").returncode, 0)
                self.assertEqual(self.read_phone()["asked"], [])
                self.assertFalse(os.path.exists(self.save))

    def test_poser_fails_when_the_role_does_not_move(self):
        r = self.run_dialer("poser", ignore_writes=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.holder(), GOOGLE)

    def test_a_role_service_that_times_out_is_asked_again(self):
        self.change(timeouts=2)
        r = self.run_dialer("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), OURS)
        self.assertEqual(self.read_phone()["asked"], [OURS] * 3)

    def test_retour_keeps_a_later_choice(self):
        self.run_dialer("poser")
        self.change(holder="com.example.dialer")
        r = self.run_dialer("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), "com.example.dialer")
        self.assertEqual(self.read_phone()["asked"], [])
        self.assertFalse(os.path.exists(self.save))

    def test_retour_fails_and_keeps_the_save_when_the_role_does_not_move(self):
        self.run_dialer("poser")
        r = self.run_dialer("retour", ignore_writes=True)
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertEqual(self.holder(), OURS)
        self.assertTrue(os.path.exists(self.save))

    def test_retour_refuses_a_broken_save_without_changing_anything(self):
        self.run_dialer("poser")
        for name, content in {"empty": "", "two lines": f"{GOOGLE}\n{GOOGLE}\n", "not a package": "x'y\n"}.items():
            with self.subTest(name):
                with open(self.save, "w") as f:
                    f.write(content)
                self.change(holder=OURS)
                r = self.run_dialer("retour")
                self.assertEqual(r.returncode, 2, r.stderr)
                self.assertEqual(self.read_phone()["asked"], [])
                self.assertTrue(os.path.exists(self.save))

    def test_retour_without_save_gives_bold7s_role_to_google(self):
        # The save lost: never leave the phone without Google's Phone behind it.
        self.change(holder=OURS)
        r = self.run_dialer("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), GOOGLE)

    def test_retour_without_save_leaves_another_holder(self):
        r = self.run_dialer("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["asked"], [])

    def test_retour_to_an_app_gone_falls_back_to_google(self):
        self.change(installed=["com.example.dialer", GOOGLE, OURS], holder="com.example.dialer")
        self.run_dialer("poser")
        self.change(installed=[GOOGLE, OURS], holder=OURS)
        r = self.run_dialer("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.holder(), GOOGLE)

    def test_unknown_argument_fails(self):
        self.assertNotEqual(self.run_dialer("prendre").returncode, 0)
        self.assertEqual(self.read_phone()["asked"], [])


if __name__ == "__main__":
    unittest.main()
