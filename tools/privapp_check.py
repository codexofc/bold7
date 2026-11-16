#!/usr/bin/env python3
"""Checks the module's privapp-permissions whitelist against the priv-app APKs it embeds.

  python3 -I tools/privapp_check.py <aapt2> <privapp-permissions.xml> <apk>...

The Q25 runs with ro.control_privapp_permissions=enforce: a priv-app that requests a privileged
permission missing from its whitelist stops system_server at boot. Every permission an APK
requests (`aapt2 dump permissions`) must therefore have its line, except the ones the APK
declares itself; and every line must match a request, so the whitelist follows the manifests.
Each of them also needs its <hidden-api-whitelisted-app>: all reach hidden APIs through
core/platform, and an exemption only exists for an embedded priv-app.
The launcher never requests INTERNET: its search reads messages, calls and files, and must
have no way to send them anywhere.
Exits non-zero, naming each mismatch, otherwise prints nothing.

Deliberately simple: every request needs a line, privileged or not (the build has no protection levels of
the phone's framework); a line for a normal permission is ignored by Android, so this only errs
on the safe side.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def parse_dump(text):
    """`aapt2 dump permissions` output -> (package, declared permissions, requested permissions)."""
    pkg = re.search(r'(?m)^package: (\S+)$', text)
    if not pkg:
        raise ValueError('no package line in the aapt2 dump')
    declared = set(re.findall(r'(?m)^permission: (\S+)$', text))
    # <uses-permission-sdk-23> requests the permission all the same on SDK 34.
    used = set(re.findall(r"(?m)^uses-permission(?:-sdk-23)?: name='([^']+)'", text))
    return pkg[1], declared, used


# Priv-apps that must never reach the network.
NO_NETWORK = {'dev.bold7.launcher'}


def mismatches(whitelist_xml, dumps):
    """Messages for each requested permission without its line, and each line without its request."""
    root = ET.fromstring(whitelist_xml.encode())
    listed = {}
    for entry in root.iter('privapp-permissions'):
        names = {p.get('name') for p in entry.iter('permission')}
        listed.setdefault(entry.get('package'), set()).update(names)
    hidden = {e.get('package') for e in root.iter('hidden-api-whitelisted-app')}
    errors = []
    for pkg, declared, used in dumps:
        if pkg in NO_NETWORK and 'android.permission.INTERNET' in used:
            errors.append(f'{pkg}: requests android.permission.INTERNET, which it must never have')
        need, have = used - declared, listed.pop(pkg, set())
        errors += [f'{pkg}: requests {p}, missing from the whitelist (bootloop)' for p in sorted(need - have)]
        errors += [f'{pkg}: whitelists {p}, which it does not request' for p in sorted(have - need)]
        # Every Bold7 priv-app reaches hidden APIs through core/platform.
        if pkg not in hidden:
            errors.append(f'{pkg}: no <hidden-api-whitelisted-app>, its hidden API calls would fail')
        hidden.discard(pkg)
    errors += [f'{pkg}: whitelisted, but no such priv-app in the module' for pkg in sorted(listed)]
    errors += [f'{pkg}: hidden API exemption, but no such priv-app in the module' for pkg in sorted(hidden)]
    return errors


def main(aapt2, whitelist, *apks):
    dumps = [parse_dump(subprocess.run([aapt2, 'dump', 'permissions', apk], check=True,
                                       capture_output=True, text=True).stdout) for apk in apks]
    with open(whitelist, encoding='utf-8') as f:
        errors = mismatches(f.read(), dumps)
    if errors:
        sys.exit(f'{whitelist}:\n  ' + '\n  '.join(errors))


if __name__ == '__main__':
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
