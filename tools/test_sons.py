"""tools/sons.sh against a fake adb: what poser and retour write, and when they refuse.

The fake phone is a JSON file: settings, MediaStore rows (id -> path), files present. Every
settings write is logged, so a refusal can be checked to have written nothing.
"""
import json
import os
import stat
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SONS = os.path.join(HERE, "sons.sh")
A = "/product/media/audio"
SPIRIT, DREAM, SANGUINE = f"{A}/ringtones/Spirit.m4a", f"{A}/alarms/Dream.m4a", f"{A}/notifications/SanguineRemix.m4a"
BEAT, PIXIE, CESIUM, SHIMMER = f"{A}/ringtones/BeatPlucker.ogg", f"{A}/notifications/pixiedust.ogg", f"{A}/alarms/Cesium.ogg", f"{A}/notifications/Shimmer.m4a"
MINE = "/storage/emulated/0/Ringtones/spirit_bbos7.m4a"  # the user's own Spirit, outside the module

FAKE_ADB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    import json, os, re, shlex, sys
    path = os.environ["FAKE_PHONE"]
    phone = json.load(open(path))
    args = sys.argv[1:]
    if args == ["get-state"]:
        print("device"); sys.exit(0)
    assert args[:2] == ["shell", "-n"], args
    cmd = shlex.split(args[2])
    out = ""
    if cmd[:3] == ["settings", "get", "system"]:
        out = phone["settings"].get(cmd[3], "null")
    elif cmd[:3] == ["settings", "put", "system"]:
        phone["settings"][cmd[3]] = cmd[4]; phone["writes"].append(cmd[3])
    elif cmd[:3] == ["settings", "delete", "system"]:
        phone["settings"].pop(cmd[3], None); phone["writes"].append(cmd[3])
    elif cmd[:2] == ["content", "query"]:
        vol = cmd[3].split("/")[3]
        where = cmd[cmd.index("--where") + 1]
        rows = {int(k): v for k, v in phone["rows"].get(vol, {}).items()}
        m = re.fullmatch(r"_id=(\d+)", where)
        hits = [(i, p) for i, p in rows.items() if (m and i == int(m.group(1))) or where == f"_data='{p}'"]
        out = "\n".join(f"Row: {n} _id={i}, title={os.path.basename(p).split('.')[0]}" if "title" in cmd[cmd.index("--projection") + 1]
                        else f"Row: {n} _data={p}" if "_data" in cmd[cmd.index("--projection") + 1] else f"Row: {n} _id={i}"
                        for n, (i, p) in enumerate(hits)) or "No result found."
    elif cmd[:2] == ["content", "call"]:
        rows = phone["rows"]["internal"]
        for f in phone["files"]:
            if f not in rows.values():
                rows[str(max(map(int, rows), default=0) + 1)] = f
        for k in [k for k, v in rows.items() if v.startswith("/product/") and v not in phone["files"]]:
            del rows[k]
    elif cmd[0] == "ls":
        out = cmd[1] if cmd[1] in phone["files"] else ""
    else:
        raise SystemExit(f"fake adb: unknown {cmd}")
    json.dump(phone, open(path, "w"))
    if out:
        print(out)
''').lstrip()


def uri(i, vol="internal"):
    return f"content://media/{vol}/audio/media/{i}?title=x&canonical=1"


class SonsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.phone = os.path.join(self.dir, "phone.json")
        self.save = os.path.join(self.dir, "sons-avant.txt")
        # Stock settings, the module mounted and not yet scanned.
        self.write_phone({
            "settings": {"ringtone": uri(95), "notification_sound": uri(190), "alarm_alert": uri(108)},
            "rows": {"internal": {"95": BEAT, "190": PIXIE, "108": CESIUM, "228": SPIRIT, "241": DREAM, "242": SANGUINE}},
            "files": [BEAT, PIXIE, CESIUM, SPIRIT, DREAM, SANGUINE, SHIMMER],
            "writes": [],
        })

    def write_phone(self, phone):
        with open(self.phone, "w") as f:
            json.dump(phone, f)

    def read_phone(self):
        with open(self.phone) as f:
            return json.load(f)

    def run_sons(self, *args):
        env = dict(os.environ, PATH=f"{self.dir}:{os.environ['PATH']}", FAKE_PHONE=self.phone, SONS_SAVE=self.save)
        return subprocess.run(["bash", SONS, *args], env=env, capture_output=True, text=True)

    def files_of_settings(self):
        p = self.read_phone()
        rows = p["rows"]["internal"]
        return {k: rows.get(v.split("/")[6].split("?")[0]) for k, v in p["settings"].items()}

    def test_poser_then_retour_gives_back_the_exact_values(self):
        before = self.read_phone()["settings"]
        self.assertEqual(self.run_sons("poser").returncode, 0)
        self.assertEqual(self.files_of_settings(), {"ringtone": SPIRIT, "notification_sound": SANGUINE, "alarm_alert": DREAM})
        r = self.run_sons("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["settings"], before)
        self.assertFalse(os.path.exists(self.save))

    def test_a_second_poser_keeps_the_first_save(self):
        self.run_sons("poser")
        with open(self.save) as f:
            first = f.read()
        self.assertEqual(self.run_sons("poser").returncode, 0)
        with open(self.save) as f:
            self.assertEqual(f.read(), first)

    def test_poser_refuses_a_value_it_could_not_write_back(self):
        p = self.read_phone()
        p["settings"]["ringtone"] = "content://media/internal/audio/media/95?title=it's"
        self.write_phone(p)
        self.assertNotEqual(self.run_sons("poser").returncode, 0)
        self.assertFalse(os.path.exists(self.save))
        self.assertEqual(self.read_phone()["writes"], [])

    def test_retour_refuses_a_broken_save_without_writing(self):
        self.run_sons("poser")
        with open(self.save) as f:
            good = f.read()
        lines = good.splitlines(keepends=True)
        broken = {
            "empty": "",
            "truncated": "".join(lines[:2]),
            "unknown key": "".join(lines[:2]) + "volume_ring\tcontent://media/internal/audio/media/1\n",
            "twice the same key": "".join(lines[:2]) + lines[0],
            "not a media uri": "".join(lines[:2]) + "alarm_alert\tfile:///sdcard/a.mp3\n",
        }
        for name, content in broken.items():
            with self.subTest(name):
                with open(self.save, "w") as f:
                    f.write(content)
                p = self.read_phone()
                p["writes"] = []
                self.write_phone(p)
                self.assertNotEqual(self.run_sons("retour").returncode, 0)
                self.assertEqual(self.read_phone()["writes"], [])
                self.assertTrue(os.path.exists(self.save))

    def test_retour_keeps_a_sound_chosen_after_poser(self):
        self.run_sons("poser")
        p = self.read_phone()
        p["settings"]["ringtone"] = uri(95)  # the user picked Beat Plucker again
        p["rows"]["internal"]["300"] = f"{A}/ringtones/Atria.ogg"
        p["settings"]["alarm_alert"] = uri(300)
        self.write_phone(p)
        r = self.run_sons("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.read_phone()["settings"]["alarm_alert"], uri(300))
        self.assertIn("alarm_alert kept", r.stdout)
        self.assertEqual(self.files_of_settings()["notification_sound"], PIXIE)

    def test_retour_without_save_refuses_a_sound_of_the_module(self):
        p = self.read_phone()
        p["rows"]["internal"]["400"] = SHIMMER
        p["settings"]["notification_sound"] = uri(400)
        self.write_phone(p)
        self.assertNotEqual(self.run_sons("retour").returncode, 0)

    def test_retour_without_save_refuses_a_row_mediastore_forgot(self):
        p = self.read_phone()
        p["settings"]["notification_sound"] = uri(999)
        self.write_phone(p)
        self.assertNotEqual(self.run_sons("retour").returncode, 0)

    def own_ringtone(self):
        p = self.read_phone()
        p["rows"]["external_primary"] = {"1000000072": MINE}
        p["settings"]["ringtone"] = uri(1000000072, "external_primary")
        self.write_phone(p)

    def test_poser_keeps_a_ringtone_chosen_outside_the_module(self):
        self.own_ringtone()
        r = self.run_sons("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("ringtone kept", r.stdout)
        self.assertEqual(self.read_phone()["settings"]["ringtone"], uri(1000000072, "external_primary"))
        self.assertEqual(self.files_of_settings()["notification_sound"], SANGUINE)
        self.assertEqual(self.files_of_settings()["alarm_alert"], DREAM)
        # retour gives back the other two and leaves that ringtone as it is
        self.assertEqual(self.run_sons("retour").returncode, 0)
        self.assertEqual(self.read_phone()["settings"]["ringtone"], uri(1000000072, "external_primary"))
        self.assertEqual(self.files_of_settings()["notification_sound"], PIXIE)

    def test_poser_ecraser_replaces_it_and_retour_restores_it(self):
        self.own_ringtone()
        self.assertEqual(self.run_sons("poser", "--ecraser").returncode, 0)
        self.assertEqual(self.files_of_settings()["ringtone"], SPIRIT)
        self.assertEqual(self.run_sons("retour").returncode, 0)
        self.assertEqual(self.read_phone()["settings"]["ringtone"], uri(1000000072, "external_primary"))

    def test_poser_ecraser_refuses_to_lose_a_ringtone_the_save_does_not_hold(self):
        self.run_sons("poser")
        self.run_sons("retour")
        # an older save (BB10 days) survives, then the user picks their own ringtone
        with open(self.save, "w") as f:
            f.write(f"ringtone\t{uri(95)}\nnotification_sound\t{uri(190)}\nalarm_alert\t{uri(108)}\n")
        self.own_ringtone()
        p = self.read_phone()
        p["writes"] = []
        self.write_phone(p)
        r = self.run_sons("poser", "--ecraser")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("retour first", r.stderr)
        self.assertEqual(self.read_phone()["writes"], [])
        self.assertEqual(self.read_phone()["settings"]["ringtone"], uri(1000000072, "external_primary"))

    def test_an_external_uri_with_an_unreadable_row_is_still_his_own(self):
        p = self.read_phone()
        p["settings"]["ringtone"] = uri(777, "external_primary")  # no row for it
        self.write_phone(p)
        r = self.run_sons("poser")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("ringtone kept", r.stdout)
        self.assertEqual(self.read_phone()["settings"]["ringtone"], uri(777, "external_primary"))

    def test_poser_refuses_an_unknown_argument(self):
        r = self.run_sons("poser", "--force")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(self.read_phone()["writes"], [])

    def test_retour_after_removal_forgets_the_module_sounds(self):
        self.run_sons("poser")
        self.run_sons("retour")
        p = self.read_phone()
        p["files"] = [f for f in p["files"] if f not in (SPIRIT, DREAM, SANGUINE, SHIMMER)]
        self.write_phone(p)
        r = self.run_sons("retour")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn(SANGUINE, self.read_phone()["rows"]["internal"].values())


if __name__ == "__main__":
    unittest.main()
