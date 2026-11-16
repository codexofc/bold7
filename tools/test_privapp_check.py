"""Tests for privapp_check.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import unittest

import privapp_check

# As `aapt2 dump permissions` prints it (build-tools 36.1.0).
DUMP = """package: dev.bold7.launcher
permission: dev.bold7.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='android.permission.MONITOR_INPUT'
uses-permission: name='android.permission.START_TASKS_FROM_RECENTS'
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='32'
uses-permission: name='dev.bold7.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
"""


def whitelist(*entries, hidden=None):
    """entries: (package, [permission, ...]); hidden: packages exempted from the hidden API
    checks, by default every package of entries."""
    body = ''.join(
        f'    <privapp-permissions package="{pkg}">\n'
        + ''.join(f'        <permission name="{p}" />\n' for p in perms)
        + '    </privapp-permissions>\n'
        for pkg, perms in entries)
    if hidden is None:
        hidden = sorted({pkg for pkg, _ in entries})
    body += ''.join(f'    <hidden-api-whitelisted-app package="{pkg}" />\n' for pkg in hidden)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<!-- c -->\n<permissions>\n{body}</permissions>\n'


LAUNCHER_OK = ('dev.bold7.launcher', [
    'android.permission.MONITOR_INPUT',
    'android.permission.START_TASKS_FROM_RECENTS',
    'android.permission.READ_EXTERNAL_STORAGE',
])


class ParseDumpTest(unittest.TestCase):
    def test_package_declared_and_requested(self):
        pkg, declared, used = privapp_check.parse_dump(DUMP)
        self.assertEqual(pkg, 'dev.bold7.launcher')
        self.assertEqual(declared, {'dev.bold7.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'})
        self.assertIn('android.permission.READ_EXTERNAL_STORAGE', used)  # maxSdkVersion kept apart
        self.assertEqual(len(used), 4)

    def test_sdk_23_requests_count_too(self):
        # <uses-permission-sdk-23> asks for the permission all the same on SDK 34.
        dump = "package: p\nuses-permission-sdk-23: name='android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS'\n"
        self.assertEqual(privapp_check.parse_dump(dump)[2], {'android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS'})

    def test_dump_without_package_is_refused(self):
        with self.assertRaises(ValueError):
            privapp_check.parse_dump("uses-permission: name='android.permission.VIBRATE'\n")


class MismatchesTest(unittest.TestCase):
    def check(self, xml, dump=DUMP):
        return privapp_check.mismatches(xml, [privapp_check.parse_dump(dump)])

    def test_every_request_listed_passes(self):
        self.assertEqual(self.check(whitelist(LAUNCHER_OK)), [])

    def test_missing_permission_fails_and_is_named(self):
        pkg, perms = LAUNCHER_OK
        errors = self.check(whitelist((pkg, perms[1:])))
        self.assertEqual(len(errors), 1)
        self.assertIn('dev.bold7.launcher', errors[0])
        self.assertIn('android.permission.MONITOR_INPUT', errors[0])

    def test_package_absent_from_whitelist_fails(self):
        errors = ' '.join(self.check(whitelist(('dev.bold7.other', ['x']))))
        for p in LAUNCHER_OK[1]:
            self.assertIn(p, errors)
        self.assertIn('dev.bold7.other', errors)

    def test_own_permissions_need_no_line(self):
        # The app declares it itself (androidx adds one): never a privileged framework permission.
        self.assertNotIn('DYNAMIC_RECEIVER', ' '.join(self.check(whitelist(LAUNCHER_OK))))

    def test_line_for_a_permission_not_requested_fails(self):
        pkg, perms = LAUNCHER_OK
        errors = self.check(whitelist((pkg, perms + ['android.permission.READ_FRAME_BUFFER'])))
        self.assertEqual(len(errors), 1)
        self.assertIn('READ_FRAME_BUFFER', errors[0])

    def test_entry_for_a_package_not_embedded_fails(self):
        errors = self.check(whitelist(LAUNCHER_OK, ('dev.bold7.gone', []), hidden=['dev.bold7.launcher']))
        self.assertEqual(len(errors), 1)
        self.assertIn('dev.bold7.gone', errors[0])

    def test_app_requesting_nothing_needs_an_empty_entry_only(self):
        dump = 'package: dev.bold7.launcher\n'
        self.assertEqual(self.check(whitelist(('dev.bold7.launcher', [])), dump), [])
        self.assertEqual(len(self.check('<permissions />', dump)), 1)  # the hidden API exemption

    def test_missing_hidden_api_exemption_fails(self):
        # Every Bold7 priv-app reaches hidden APIs through core/platform.
        errors = self.check(whitelist(LAUNCHER_OK, hidden=[]))
        self.assertEqual(len(errors), 1)
        self.assertIn('hidden-api-whitelisted-app', errors[0])

    def test_exemption_for_a_package_not_embedded_fails(self):
        errors = self.check(whitelist(LAUNCHER_OK, hidden=['dev.bold7.launcher', 'dev.bold7.gone']))
        self.assertEqual(len(errors), 1)
        self.assertIn('dev.bold7.gone', errors[0])

    def test_launcher_asking_for_the_network_fails(self):
        # The launcher reads the user's messages, calls and files for its search: it never
        # gets the network, whitelisted or not (no network request the user did not ask for).
        pkg, perms = LAUNCHER_OK
        dump = DUMP + "uses-permission: name='android.permission.INTERNET'\n"
        errors = self.check(whitelist((pkg, perms + ['android.permission.INTERNET'])), dump)
        self.assertEqual(len(errors), 1)
        self.assertIn('INTERNET', errors[0])
        self.assertIn('dev.bold7.launcher', errors[0])

    def test_another_app_may_ask_for_the_network(self):
        dump = "package: dev.bold7.hub\nuses-permission: name='android.permission.INTERNET'\n"
        self.assertEqual(self.check(whitelist(('dev.bold7.hub', ['android.permission.INTERNET'])), dump), [])

    def test_entries_of_one_package_are_merged(self):
        # SystemConfig adds up every <privapp-permissions> of a package.
        pkg, perms = LAUNCHER_OK
        self.assertEqual(self.check(whitelist((pkg, perms[:1]), (pkg, perms[1:]))), [])


if __name__ == '__main__':
    unittest.main()
