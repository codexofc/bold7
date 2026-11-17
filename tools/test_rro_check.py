"""Tests for rro_check.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import unittest

import rro_check

# As `aapt2 dump resources` prints them (build-tools 37.0.0), cut down.
FRAMEWORK = """Binary APK
Package name=android id=01
  type attr id=01 entryCount=3
    resource 0x010100af attr/gravity PUBLIC
    resource 0x010100d0 attr/id PUBLIC
    resource 0x01120086 ^attr-private/listItemLayout
  type style id=03 entryCount=1
    resource 0x01030421 style/Theme.Material.BaseDialog
  type id id=02 entryCount=1
    resource 0x0102022d id/buttonPanel
"""

OVERLAY = """Binary APK
Package name=dev.bold7.overlay.android id=7f
  type style id=0a entryCount=2
    resource 0x7f0a0000 style/MenuList.Bold7
      () (style) size=1 parent=0x01030421
        0x01120086=@0x0102022d
    resource 0x7f0a0001 style/Theme.Material.BaseDialog
      () (style) size=1 parent=0x01030999
        0x010100d0=?0x01120086
"""

XML = """N: android=http://schemas.android.com/apk/res/android (line=20)
  E: LinearLayout (line=20)
    A: http://schemas.android.com/apk/res/android:id(0x010100d0)=@0x0102022d
    A: http://schemas.android.com/apk/res/android:gravity(0x010100af)=0x01000033
"""


class RroCheckTest(unittest.TestCase):
    def test_resources_name_private_attributes_plainly(self):
        self.assertEqual('attr/listItemLayout', rro_check.resources(FRAMEWORK)['0x01120086'])

    def test_framework_refs_skip_plain_integers(self):
        self.assertEqual({'0x010100af', '0x010100d0', '0x0102022d'}, rro_check.framework_refs(XML))
        self.assertEqual({'0x01030421', '0x01120086', '0x0102022d', '0x01030999', '0x010100d0'},
                         rro_check.framework_refs(OVERLAY))

    def test_an_unknown_framework_id_fails(self):
        fw = rro_check.resources(FRAMEWORK)
        errors = rro_check.mismatches('Bold7Android.apk', OVERLAY, XML, set(fw.values()), set(fw))
        self.assertEqual(1, len(errors), errors)
        self.assertIn('0x01030999', errors[0])

    def test_overlay_s_own_resources_and_kept_names_pass(self):
        fw = rro_check.resources(FRAMEWORK)
        ok = OVERLAY.replace('0x01030999', '0x01030421')
        self.assertEqual([], rro_check.mismatches('o', ok, XML, set(fw.values()), set(fw)))

    def test_a_dropped_target_name_fails(self):
        fw = rro_check.resources(FRAMEWORK)
        ok = OVERLAY.replace('0x01030999', '0x01030421')
        errors = rro_check.mismatches('o', ok, XML, {'attr/id', 'attr/gravity'}, set(fw))
        self.assertEqual(['o: style/Theme.Material.BaseDialog is not in its target any more, drop or replace it'], errors)


if __name__ == '__main__':
    unittest.main()
