# Contributing to Bold7

Thank you for your interest. Bold7 aims at one thing: a Q25 on which the user forgets Android and finds BlackBerry OS 7.1, without ever putting the phone at risk. The rules below follow from that. They are strict because the project runs inside system processes on a phone that has no flashable recovery image.

Before starting on something large, open an issue so the approach can be agreed first.

## What makes a good contribution

- A fix for something that does not behave as on the Bold 9900, with the reference that shows how it should look or behave.
- Something that still shows Android, replaced by its OS 7 equivalent.
- A performance improvement, with its measurement on a Q25.
- A test that catches a real bug.

The size of a change, the effort it takes or "Android already does it" are never reasons to keep an Android behaviour.

## Fidelity: every element has a source

Nothing visible or audible is invented. Every image, colour, dimension, label, sound, menu entry and animation comes from a source, graded next to the value in the code (a short comment in `core/design`, a column in the tables of `core/icons`):

| Grade | Source |
|---|---|
| **F** | the 9900 OS 7.1 firmware: images, `.pme` layouts, fonts, sounds, theme values |
| **C** | a native capture of a real 9900 or of the official BlackBerry simulator, measured to the pixel |
| **M** | a manual or guide published by RIM |
| **S** | recollection or press; must be cross-checked before anything is drawn |
| **D** | deduced, in particular every layout recomputed for the square screen |

An element with no source is marked as eyeballed and is never presented as OS 7. An Android default is never a design choice.

The firmware files in circulation are often carrier builds. A file taken from one is compared with a generic build before it is treated as the original.

**The square screen.** A 9900 pixel and a Q25 pixel have the same physical size within 2.7%. Firmware images are never scaled up: they are drawn pixel for pixel. Layouts are recomputed for 720 x 720 (more columns, margins or rows) and graded D.

**Visual pull requests** include a capture from the Q25 next to the reference capture (simulator or real 9900), at native size.

**What still shows Android.** If your change meets an Android leftover it does not fix, say so in the pull request so it can be added to the list of remaining items. That list only shrinks by replacing items with OS 7, never by giving up on them.

## Architecture rules

- **No Compose.** Classic views everywhere. OS 7 components live once in `core/ui` and are shared by the launcher and the apps.
- **The main thread only renders.** I/O, PackageManager, database, heavy binder calls and image decoding go to injected dispatchers. StrictMode crashes debug builds on any violation.
- **No animation depends on a busy thread.** What follows the finger ends on a spring that keeps the release velocity. Windows move with `SurfaceControl` transactions on a dedicated thread.
- **No gestures.** OS 7 had none.
- **No accessibility service.**
- **Resource overlays before hooks.** A SystemUI hook is accepted only when an overlay cannot do it. Hooks target the exact SystemUI of BenOS, are checked against its fingerprint, and switch off cleanly when it changes. No silent `catch (Throwable)`: a hook that fails is counted and visible in the diagnostics.
- **`core/platform` is the only module that touches hidden APIs**, through functions that return a typed result or count the failure.
- **Smallest diff that works.** No abstraction for a single implementation, except the interface in front of a system service, whose second implementation is the test fake.
- **No periodic screenshot, polling or background wake-up** without a measurement that justifies it.
- Kotlin, `minSdk = targetSdk = compileSdk = 34`, no compatibility branches. Code identifiers and comments in English. Root package `dev.bold7`.

## Tests first

Non-trivial logic (parsing, state, focus or scroll computation, MIME, anything that decides) is written test first: a JVM test that fails, then the code that makes it pass.

- The logic imports nothing from `android.*`. Android stays at the edge and translates events into calls to the pure core.
- Each screen has an immutable state, events and a pure reducer, tested on the JVM.
- System services are reached through a narrow interface with a hand-written fake. No mocking library.
- Dispatchers are injected, never hard-coded, so tests control time.
- A test must fail when the logic breaks. Accessor tests are not useful.
- Boot scripts are POSIX `sh`, reviewed like security code; every decision they make has a test that runs with the phone's `sh` in `/data/local/tmp`, without the module and without rebooting.

Run before opening a pull request:

```sh
./gradlew test
python3 -m unittest discover -s tools -p 'test_*.py'
```

## Performance is measured on the device

Budgets are measured on a Q25, never on an emulator. A change that touches a hot path (home screen, drawer, transitions, lists, typing, the unified inbox) gives its `tools/perf.sh` measurement before and after, in the pull request and in the commit message. The current budgets: fewer than 1% janky frames and a 95th percentile under 16.6 ms while scrolling or switching views, zero frames drawn by the home screen at rest, no background activity of the launcher while another app is used.

## Device safety

These are not negotiable, and a pull request that breaks one is not merged:

- Nothing writes to a partition. No `dd`, no `fastboot flash`, no write to `/system`, `/vendor`, `/product` or `/system_ext` outside the module's mounts.
- The module must always be removable, from the KernelSU manager and from KernelSU's safe mode. A change to the module is not merged without a full install and uninstall round trip, with the device state compared before and after (`tools/state.sh`).
- No `su` at runtime in any APK. Privileges come from the privileged-app status and the fixed permission list; any permission added to that list is justified in the commit.
- Verifying the lock code stays with Android, without exception. Bold7 draws the screen and passes the code on, with no copy, log or storage.
- Nothing interactive while the phone is locked.
- Every exported component is protected by a `signature` or system permission, or is not exported. No dynamic `RECEIVER_EXPORTED` receiver without a sender permission.
- Never change the global `hidden_api_policy`.
- No telemetry and no network request the user did not ask for.
- Never touch the user's other system modules, key mapping tools or keybox.

## No proprietary asset in the repository

No BlackBerry asset (image, icon, sound, font, wallpaper, boot animation, dictionary) is ever committed, in any form, encrypted or not. Resources are extracted from the user's own official 9900 file, on their phone or, for development, into `assets-private/` and `assets-gen/`, which are outside git. The public build (`-Pbold7.public=true`) fails if any release APK contains a file from the firmware.

Do not link to unofficial copies of BlackBerry files in issues or pull requests.

## Dependencies and licence

- Bold7 is licensed under Apache 2.0. By contributing, you agree that your contribution is licensed under the same terms.
- Dependencies shipped in an APK must be under Apache 2.0, MIT or BSD. Each new dependency is justified in its commit.
- Third-party code added to the repository keeps its original header and is listed in `NOTICE` with its licence.

## Commits and branches

- Branch names: `<type>/<short-summary>`, for example `feat/clock-bedside`, `fix/drawer-scroll-p95`.
- One commit per functional change. Subject in the conventional form, verb in the imperative: `feat(launcher): add the Media view`, `fix(sysui): restore the banner after an OTA`.
- The body explains why, and gives the performance measurement when a hot path changed.
- Keep commit messages and pull request descriptions plain and factual.

## Reporting a bug

Use the bug report template and give the BenOS version, the Bold7 module version, the steps and what you expected. Logs from `adb logcat -s Bold7` help. Remove phone numbers, names and message contents before attaching anything.

Security issues are reported privately: see [SECURITY.md](SECURITY.md).

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
