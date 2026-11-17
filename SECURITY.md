# Security policy

Bold7 runs as privileged apps, as code loaded into SystemUI and as a root module that runs at boot. A flaw in it can expose a user's messages, calls or lock screen, or leave a phone unable to start. Reports are taken seriously.

## Reporting a vulnerability

Report privately through GitHub: open the **Security** tab of this repository and choose **Report a vulnerability**. Do not open a public issue, pull request or discussion for a security problem.

Please include:

- the Bold7 module version, and the system and its version (LineageOS 24 build or BenOS);
- what an attacker needs (a malicious app installed, physical access to a locked phone, a crafted message or email, a network position);
- the steps to reproduce, and what they lead to;
- a proof of concept if you have one.

You will get an acknowledgement, then an assessment. A fix is released as a new module version, offered by the KernelSU manager, and the advisory is published once users have had time to update. Reporters are credited unless they ask not to be.

## Supported versions

Only the latest release receives security fixes.

## What counts as a vulnerability here

- **Lock screen bypass**: any way to reach the phone's content, an app, a notification action or a reply while the phone is locked, or to influence how the lock code is checked, counted or delayed.
- **Leak of the lock code** or of typed text: the code or keystrokes stored, logged or kept in memory longer than needed; anything learned or read from a password field.
- **Privilege escalation through Bold7**: an exported component, provider, receiver or service reachable by another app without the signature or system permission that should protect it; an unprotected dynamic receiver; a path from an ordinary app to a privileged permission held by Bold7.
- **Data exposure**: messages, call log, contacts, calendar or email reachable by an app that has not been granted them, through the unified inbox provider or any other Bold7 component.
- **Parsing of untrusted content**: a crafted email, MIME part, SMS, MMS or notification that crashes or controls a Bold7 process.
- **Installer and extractor**: a crafted file placed in `Download/` that the root extractor parses before its SHA-256 is checked, or that makes it write outside its target; anything that lets a module zip, update or extracted file run code it should not.
- **Device integrity**: anything that makes Bold7 write to a partition, prevents its removal by the KernelSU manager or safe mode, or survives uninstallation.
- **Unrequested network traffic** or any form of telemetry.

## What does not count

- Issues that need an already rooted attacker, or the user granting root to another app.
- Flaws in Android, LineageOS, BenOS, KernelSU, Vector or Google Messages that Bold7 does not make worse. Report those upstream; tell us if Bold7 should work around them.
- The absence of signature verification by the KernelSU manager itself (release zips are signed, and the manager does not check them).
- Visual differences from BlackBerry OS 7.1. Those are fidelity issues, welcome in the public tracker.
