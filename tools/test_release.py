"""tools/release.sh on a fake zip, with a throwaway Ed25519 key: what it writes, signs, and refuses."""
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "release.sh")
OPENSSL = shutil.which("openssl", path="/opt/homebrew/bin:/usr/local/bin:" + os.environ.get("PATH", ""))
PASSWORD = "throwaway-passphrase"
FIRMWARE = b"not really a 9900 sound"
UPDATE = "https://raw.githubusercontent.com/codexofc/bold7/main/update/benos.json"
PROP = f"id=bold7\nname=Bold7\nversion=0.1\nversionCode=1\nauthor=Someone\ndescription=x\nupdateJson={UPDATE}\n"
URL = "https://github.com/codexofc/bold7/releases"


def read(path):
    with open(path) as f:
        return f.read()


def openssl_ok():
    if not OPENSSL:
        return False
    out = subprocess.run([OPENSSL, "version"], capture_output=True, text=True).stdout
    return out.startswith("OpenSSL ") and int(out.split()[1].split(".")[0]) >= 3


@unittest.skipUnless(openssl_ok(), "needs OpenSSL 3 or later")
class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        for d in ("keys", "system/module/release", "system/module/src", "assets-private/bbos7-9900"):
            os.makedirs(os.path.join(self.root, d))
        self.write("system/module/src/module.prop", PROP)
        self.write("local.properties", f"sdk.dir=/nowhere\nbold7.release.password={PASSWORD}\n")
        self.write("assets-private/bbos7-9900/bbos7-9900.sha256",
                   hashlib.sha256(FIRMWARE).hexdigest() + "  sounds/x/Spirit.m4a\n")
        self.write("notes.md", "- First release.\n")
        key = self.path("keys/bold7-release.pem")
        subprocess.run([OPENSSL, "genpkey", "-algorithm", "ed25519", "-aes-256-cbc", "-pass", "pass:" + PASSWORD,
                        "-out", key], check=True, capture_output=True)
        subprocess.run([OPENSSL, "pkey", "-in", key, "-passin", "pass:" + PASSWORD, "-pubout",
                        "-out", self.path("system/module/release/bold7-release.pub")], check=True, capture_output=True)

    def tearDown(self):
        shutil.rmtree(self.root)

    def path(self, rel):
        return os.path.join(self.root, rel)

    def write(self, rel, text):
        with open(self.path(rel), "w") as f:
            f.write(text)

    def zip(self, version="1.2-benos", code=10200, extra=None):
        z = self.path("fake.zip")
        with zipfile.ZipFile(z, "w") as f:
            f.writestr("module.prop", PROP.replace("version=0.1", f"version={version}")
                       .replace("versionCode=1", f"versionCode={code}"))
            f.writestr("customize.sh", "ui_print hello\n")
            for name, data in (extra or {}).items():
                f.writestr(name, data)
        return z

    def release(self, *args, zip_path=None):
        env = dict(os.environ, BOLD7_ROOT=self.root, BOLD7_RELEASE_ZIP=zip_path or self.zip(), OPENSSL=OPENSSL)
        return subprocess.run(["bash", SCRIPT, *args], env=env, capture_output=True, text=True)

    def test_release_files_signed_and_checked(self):
        r = self.release("1.2", self.path("notes.md"))
        self.assertEqual(0, r.returncode, r.stderr)
        out = self.path("build/release/1.2-benos")
        zipname = "bold7-1.2-benos.zip"
        self.assertEqual({zipname, zipname + ".sig", "update.json", "update.json.sig", "CHANGELOG.md"}, set(os.listdir(out)))
        meta = json.loads(read(os.path.join(out, "update.json")))
        self.assertEqual({"version": "1.2-benos", "versionCode": 10200,
                          "zipUrl": f"{URL}/download/v1.2-benos/{zipname}",
                          "changelog": f"{URL}/download/v1.2-benos/CHANGELOG.md"}, meta)
        self.assertEqual(read(os.path.join(out, "update.json")), read(self.path("public/update/benos.json")))
        self.assertEqual("# Bold7 1.2-benos\n\n- First release.\n", read(os.path.join(out, "CHANGELOG.md")))
        for f in (zipname, "update.json"):
            v = subprocess.run([OPENSSL, "pkeyutl", "-verify", "-pubin", "-rawin", "-inkey",
                                self.path("system/module/release/bold7-release.pub"), "-in", os.path.join(out, f),
                                "-sigfile", os.path.join(out, f + ".sig")], capture_output=True)
            self.assertEqual(0, v.returncode, f)
        prop = read(self.path("system/module/src/module.prop"))
        self.assertIn("version=1.2-benos\nversionCode=10200\n", prop)
        self.assertTrue(prop.endswith(f"updateJson={UPDATE}\n"))
        self.assertIn("gh release create v1.2-benos --repo codexofc/bold7 --draft", r.stdout)

    def assertRefused(self, r, message):
        self.assertNotEqual(0, r.returncode)
        self.assertIn(message, r.stderr)
        self.assertFalse(os.path.exists(self.path("build/release/1.2-benos/bold7-1.2-benos.zip")))
        self.assertFalse(os.path.exists(self.path("public/update/benos.json")))
        self.assertEqual(PROP, read(self.path("system/module/src/module.prop")), "module.prop not put back")

    def test_empty_notes_refused(self):
        self.write("notes.md", " \n\n")
        self.assertRefused(self.release("1.2", self.path("notes.md")), "empty release notes")

    def test_firmware_file_in_the_zip_refused(self):
        z = self.zip(extra={"layer/product/media/x.m4a": FIRMWARE})
        self.assertRefused(self.release("1.2", self.path("notes.md"), zip_path=z), "BlackBerry file")

    def test_development_paths_refused(self):
        z = self.zip(extra={"layer/product/media/bold7/ui/res/drawable-nodpi/ui_menu.png": b"png"})
        self.assertRefused(self.release("1.2", self.path("notes.md"), zip_path=z), "where the development zip puts")

    def test_zip_of_another_version_refused(self):
        z = self.zip(version="1.1-benos", code=10100)
        self.assertRefused(self.release("1.2", self.path("notes.md"), zip_path=z), "version is '1.1-benos'")

    def test_wrong_passphrase_refused(self):
        self.write("local.properties", "bold7.release.password=wrong\n")
        self.assertRefused(self.release("1.2", self.path("notes.md")), "signing bold7-1.2-benos.zip failed")
        self.assertFalse(os.path.exists(self.path("build/release/1.2-benos")))

    def test_zip_of_another_line_refused(self):
        self.write("system/module/src/module.prop", PROP.replace("benos", "lineage24"))
        # A zip built on the BenOS line, released from the Lineage line: refused.
        r = self.release("1.2", self.path("notes.md"))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("updateJson is", r.stderr)
        self.assertFalse(os.path.exists(self.path("public/update/lineage24.json")))

    def test_missing_update_json_refused(self):
        self.write("system/module/src/module.prop", PROP.replace(f"updateJson={UPDATE}\n", ""))
        r = self.release("1.2", self.path("notes.md"))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("not https://raw.githubusercontent.com/codexofc/bold7/main/update/<line>.json", r.stderr)

    def test_bad_version_and_downgrade_refused(self):
        self.assertEqual(2, self.release("v1.2", self.path("notes.md")).returncode)
        self.write("system/module/src/module.prop", PROP.replace("versionCode=1", "versionCode=20000"))
        r = self.release("1.2", self.path("notes.md"))
        self.assertNotEqual(0, r.returncode)
        self.assertIn("lower than", r.stderr)


if __name__ == "__main__":
    unittest.main()
