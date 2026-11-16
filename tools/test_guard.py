"""tools/guard-old.sh against a fake adb: when the previous build (cascade) is refused, and what is said.

The fake phone is a JSON file: the KernelSU modules, the ones waiting in /data/adb/modules_update
and the installed packages. The guard only reads, so every case also checks that nothing else was
asked of the phone.
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
    phone = json.load(open(os.environ["FAKE_PHONE"]))
    args = sys.argv[1:]
    assert args[0] == "shell" and len(args) == 2, args
    cmd = args[1]
    with open(os.environ["FAKE_LOG"], "a") as log:
        log.write(cmd + "\n")
    if cmd == "su -c 'ls /data/adb/modules 2>/dev/null; true'":
        print("\n".join(phone["modules"]))
    elif cmd == "su -c 'ls /data/adb/modules_update 2>/dev/null; true'":
        print("\n".join(phone["modules_update"]))
    elif cmd == "pm list packages -u dev.cascade":
        print("\n".join("package:" + p for p in phone["packages"] if p.startswith("dev.cascade")))
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
''').lstrip()


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.log = os.path.join(self.dir, "log.txt")

    def run_guard(self, modules=(), modules_update=(), packages=()):
        with open(self.phone, "w") as f:
            json.dump({"modules": list(modules), "modules_update": list(modules_update), "packages": list(packages)}, f)
        script = 'set -euo pipefail; sh() { adb shell "$@" | tr -d "\\r"; }; . tools/guard-old.sh; refuse_if_old_build "LAST LINE"'
        env = dict(os.environ, PATH=self.dir + os.pathsep + os.environ["PATH"], FAKE_PHONE=self.phone, FAKE_LOG=self.log)
        r = subprocess.run(["bash", "-c", script], cwd=os.path.dirname(HERE), env=env, capture_output=True, text=True)
        with open(self.log) as f:
            asked = f.read().splitlines()
        self.assertTrue(all(c.startswith(("su -c 'ls ", "pm list packages -u")) for c in asked), asked)
        return r

    def test_nothing_of_the_old_build_passes(self):
        r = self.run_guard(
            modules=["bold7", "zygisk_vector"],
            packages=["dev.bold7.launcher", "dev.bold7.hub", "com.android.launcher3"],
        )
        self.assertEqual((r.returncode, r.stdout, r.stderr), (0, "", ""))

    def test_an_empty_phone_passes(self):
        self.assertEqual(self.run_guard().returncode, 0)

    def test_the_old_module_is_refused_with_the_way_out(self):
        r = self.run_guard(modules=["cascade", "zygisk_vector"])
        self.assertEqual(r.returncode, 1)
        self.assertIn("/data/adb/modules/cascade", r.stderr)
        self.assertIn("bb10-final", r.stderr)
        self.assertTrue(r.stderr.rstrip().endswith("LAST LINE"), r.stderr)
        self.assertEqual(r.stdout, "")

    def test_an_old_package_is_refused(self):
        r = self.run_guard(modules=["zygisk_vector"], packages=["dev.cascade.hub", "dev.bold7.launcher"])
        self.assertEqual(r.returncode, 1)
        self.assertIn("package:dev.cascade.hub", r.stderr)
        self.assertNotIn("dev.bold7", r.stderr)
        self.assertIn("bb10-final", r.stderr)

    def test_a_module_waiting_for_the_next_boot_is_refused(self):
        # Installed and not yet activated: /data/adb/modules does not list it, modules_update does.
        r = self.run_guard(modules=["zygisk_vector"], modules_update=["cascade"])
        self.assertEqual(r.returncode, 1)
        self.assertIn("/data/adb/modules_update/cascade", r.stderr)
        self.assertIn("modules_update", r.stderr)
        self.assertIn("bb10-final", r.stderr)

    def test_the_test_app_alone_is_removed_by_hand(self):
        # The uninstall.sh of bb10-final does not remove it: the message says adb uninstall, not that script.
        r = self.run_guard(packages=["dev.cascade.essai"])
        self.assertEqual(r.returncode, 1)
        self.assertIn("adb uninstall dev.cascade.essai", r.stderr)
        self.assertNotIn("bb10-final", r.stderr)

    def test_the_test_app_with_the_module_gets_both_instructions(self):
        r = self.run_guard(modules=["cascade"], packages=["dev.cascade.essai"])
        self.assertIn("bb10-final", r.stderr)
        self.assertIn("adb uninstall dev.cascade.essai", r.stderr)


if __name__ == "__main__":
    unittest.main()
