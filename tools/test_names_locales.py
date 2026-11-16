import os, unittest, sys
sys.path.insert(0, os.path.dirname(__file__))
import names_locales as n

DUMP = """  resource 0x7f0a0001 string/settings_label
        () "Settings"
        (fr) "Parametres"
        (en-rGB) "Settings"
  resource 0x7f0a0002 string/other
        (de) "x"
"""
ROOT = os.path.join(os.path.dirname(__file__), "..", "system", "overlays", "names")


class T(unittest.TestCase):
    def test_locales_seules_de_la_ressource(self):
        self.assertEqual(n.locales(DUMP, "settings_label"), {"fr", "en-rGB"})

    def test_toujours_fr(self):
        self.assertEqual(n.table([("p", "other")], {"p": [DUMP]}), ["p\tother\tde,fr,fr-rFR"])

    def test_table_versionnee_couvre_names_tsv(self):
        rows = [tuple(l.split("\t")[1:3]) for l in open(f"{ROOT}/names.tsv") if l.strip() and not l.startswith("#")]
        have = {tuple(l.split("\t")[:2]): set(l.rstrip("\n").split("\t")[2].split(","))
                for l in open(f"{ROOT}/names-locales.tsv")}
        for r in rows:
            self.assertIn(r, have)
            self.assertLessEqual(set(n.ALWAYS), have[r])


if __name__ == "__main__":
    unittest.main()
