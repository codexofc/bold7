import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import hook_targets as h  # noqa: E402

# Shaped like `dexdump` output (default mode), trimmed to what the parser reads.
DUMP = """\
Class #0            -
  Class descriptor  : 'Lcom/a/View;'
  Interfaces        -
    #0              : 'Lcom/a/Other;'
  Static fields     -
  Instance fields   -
    #0              : (in Lcom/a/View;)
      name          : 'mIcon'
      type          : 'Landroid/widget/ImageView;'
  Direct methods    -
    #0              : (in Lcom/a/View;)
      name          : 'show'
      type          : '(ZLjava/lang/String;Z)V'
  Virtual methods   -
    #0              : (in Lcom/a/View;)
      name          : 'onFinishInflate'
      type          : '()V'
    #1              : (in Lcom/a/View;)
      name          : 'bar'
      type          : '(II[Ljava/lang/String;ZIILjava/lang/String;[Lcom/a/X;)V'
    #2              : (in Lcom/a/View;)
      name          : 'drag$module_core'
      type          : '(Lcom/a/Row;)Z'
    #3              : (in Lcom/a/View;)
      name          : 'apply$1'
      type          : '()V'
    #4              : (in Lcom/a/View;)
      name          : 'apply$2'
      type          : '()V'
  source_file_idx   : 1 (View.java)

Class #1            -
  Class descriptor  : 'Lcom/b/View;'
  Virtual methods   -
    #0              : (in Lcom/b/View;)
      name          : 'moved'
      type          : '(I)V'
  source_file_idx   : 1 (View.java)
"""


class HookTargetsTest(unittest.TestCase):
    def setUp(self):
        self.index = h.parse_dexdump(DUMP.splitlines())

    def check(self, cls, member, sig):
        return h.check(self.index, ("g", "hook", cls, member, sig))[0]

    def test_present_method_field_arity_and_mangled_name(self):
        self.assertEqual(self.check("com.a.View", "onFinishInflate", "()"), "present")
        self.assertEqual(self.check("com.a.View", "mIcon", "-"), "present")
        self.assertEqual(self.check("com.a.View", "bar", "(II[Ljava/lang/String;ZIILjava/lang/String;[Lcom/a/X;)"), "present")
        self.assertEqual(self.check("com.a.View", "drag$*", "(Lcom/a/Row;)"), "present")
        self.assertEqual(self.check("com.a.View", "onFinishInflate$*", "()"), "present")

    # Same rules as the module's guard (TargetTable.kt): two suffixed methods, none taken.
    def test_two_r8_suffixes_are_ambiguous(self):
        self.assertEqual(h.check(self.index, ("g", "hook", "com.a.View", "apply$*", "()")), ("ambiguous", "apply$1, apply$2"))
        self.assertEqual(self.check("com.a.View", "apply", "()"), "member missing")

    def test_changes_are_named(self):
        self.assertEqual(self.check("com.a.View", "show", "(ZLjava/lang/String;)V"), "signature changed")
        self.assertEqual(self.check("com.a.View", "gone", "()"), "member missing")
        self.assertEqual(self.check("com.c.View", "moved", "(I)"), "class missing")
        self.assertEqual(h.check(self.index, ("g", "hook", "com.a.View", "moved", "(I)"))[1], "elsewhere: com.b.View")
        self.assertEqual(h.check(self.index, ("g", "hook", "com.c.View", "x", "()"))[1], "com.a.View, com.b.View")

    def test_interfaces_are_not_fields(self):
        self.assertNotIn("Other", " ".join(self.index["com.a.View"]["f"]))

    def test_exit_code_per_group(self):
        with tempfile.TemporaryDirectory() as tmp:
            dump, targets = os.path.join(tmp, "d.txt"), os.path.join(tmp, "t.tsv")
            open(dump, "w").write(DUMP)
            open(targets, "w").write("# c\nok\thook\tcom.a.View\tonFinishInflate\t()\n")
            with open(os.devnull, "w") as null:
                out, sys.stdout = sys.stdout, null
                try:
                    self.assertEqual(h.main(["--targets", targets, dump]), 0)
                    open(targets, "a").write("ko\thook\tcom.a.View\tgone\t()\n")
                    self.assertEqual(h.main(["--targets", targets, dump]), 1)
                    open(targets, "a").write("ko\thook\tcom.a.View\tbar\t#8\n")
                    with self.assertRaises(SystemExit):
                        h.main(["--targets", targets, dump])
                finally:
                    sys.stdout = out


if __name__ == "__main__":
    unittest.main()
