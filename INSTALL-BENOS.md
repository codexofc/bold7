# Installing Bold7 on BenOS

This guide takes a Zinwa Q25 running BenOS (Android 14) to Bold7, in about ten minutes and without a computer. If your Q25 runs the official LineageOS 24, follow the [LineageOS 24 guide](INSTALL-LINEAGEOS24.md) instead: each zip refuses the other system. LineageOS 24 is the line I develop first; BenOS keeps every fix that applies to both.

## What you need

- A Zinwa Q25 on BenOS, with the **KernelSU manager BenOS ships** and **its modules as they are** (ReZygisk, Play Integrity, TrickyStore...). Bold7 needs nothing more: no extra root, no extra Zygisk. Do not remove or change BenOS's modules for Bold7.
- No metamodule (meta-overlayfs, for example): Bold7 mounts its files itself and refuses to install next to one.
- About 600 MB free on the phone.
- **Google Messages**, installed and opened once, for your texts in Bold7's Messages.
- The official BlackBerry Bold 9900 OS 7.1 file (step 1). Bold7 contains no BlackBerry file and does not link to copies of it.

To check which system runs: Settings, About phone.

## 1. The two files of Bold7

Put both in the phone's `Download` folder, by USB from a computer or downloaded on the phone:

1. **`bold7-0.1.0-benos.zip`**, from https://github.com/codexofc/bold7/releases (the release "Bold7 0.1.0 for BenOS", below the LineageOS 24 one). Do not unzip it.
2. **The official Bold 9900 file**, one of these two, as is: not renamed, not unzipped. If you received it inside a zip, unzip that zip first and copy only the `.exe`.

| File | Size (bytes) | SHA-256 |
|---|---|---|
| `9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe` | 273797496 | `4f68dd201def6f3f5c648ce166e7e0e5ff713c79f55f97cb777b8aafa8b85adb` |
| `BlackBerry Simulator_7.1.0.318_9900.exe` | 240527751 | `79208dc1036487b05a1be007063bc377a649f4d9a973e58bade2ac06878474f1` |

Bold7 recognises the file by its size and SHA-256 and accepts nothing else.

**Optional, icons of your own.** Bold7 draws every app the 9900 never had with its own icon, framed like the 9900's. If you have older icons for some apps, put a folder `Bold7-icons/` of `<package name>.png` files (for example `com.whatsapp.png`, PNG, 1 MB at most each), or a zip holding that folder, in `Download` too. The installer copies them to `Bold7/icons/` on your storage, where you can add more later.

**Optional, check the download.** With OpenSSL 3 on a computer, the `.sig` file next to the zip on the releases page, and the key `bold7-release.pub` from the repository (`system/module/release/`):
```
openssl pkeyutl -verify -pubin -rawin -inkey bold7-release.pub -in bold7-0.1.0-benos.zip -sigfile bold7-0.1.0-benos.zip.sig
```
It prints `Signature Verified Successfully`.

## 2. Install

1. Open the KernelSU manager, **Modules**, **Install**, pick `bold7-0.1.0-benos.zip`.
2. Read the log as it scrolls. The installer checks that the phone is a Q25 on BenOS, that no metamodule is installed and that the 9900 file is there and recognised; then it extracts the 9900 resources and installs Vector (the hooking framework Bold7 relies on, if it is missing) and the Bold7 apps. If a check fails, it stops **before changing anything** and says why (for example which file to put where).
3. Tap **Reboot**.

## 3. First start

The first boot sets Bold7 as home screen, phone app and input method (Typing, in place of the BlackBerry keyboard app), grants its permissions and applies the OS 7 sounds and silences. Bold7 also takes over the trackpad and the notification LED: Q25TrackpadCustomizer's service and Q25Led are switched off while Bold7 is installed, and given back when it is removed.

At the first unlock, the OS 7 Setup Wizard opens:

1. **Welcome**: "Bold7 is installed."
2. **What Changed**: what Bold7 took over (home screen, Phone, Text Messages, Contacts, Calendar, Clock, Typing, sounds, the End key). If you would rather keep your previous home screen or phone app, this page opens Android's Default apps for you; Bold7 stays installed.
3. **Text Messages**: click **Open Google Messages**, turn on **"Allow Bixby and text on Galaxy devices"**, then press Back. This lets Bold7's Messages show and send your texts through Google Messages. If you skip it, texts open in Google Messages, and you can come back to it later from **Setup** on the home screen.
4. **Sound and Wallpaper**: keep your wallpaper, or pick one of the 9900's.
5. **All Set**: three keys to remember. **Menu** shows what you can do on any screen, **End** takes you back to the home screen, and on the home screen you **just type** to search your phone.

If the wizard says the 9900 file was not found, put it in `Download` as in step 1, install the zip again from the manager and reboot.

## Bold7 updates

The KernelSU manager offers each new version of Bold7 on the module's card: one tap, then reboot. It only offers BenOS releases to a BenOS install. The resources already extracted from the 9900 file are kept.

## Removing Bold7

KernelSU manager, **Modules**, Bold7, **Remove**, then reboot. Once the phone is unlocked, Bold7 gives back everything it changed: home screen, phone app, keyboard, trackpad service, LED, settings and sounds. Your `Bold7/icons/` folder stays.

## If something goes wrong

- **The phone does not start after installing Bold7**: press **Volume down** repeatedly while it boots. KernelSU starts in safe mode with every module turned off. Remove Bold7 from the manager, then reboot.
- **The installer stops**: the last lines of the log say why. Nothing was changed.
- **Texts do not appear in Messages**: open **Setup** on the home screen, Text Messages, and check that "Allow Bixby and text on Galaxy devices" is on in Google Messages.
- **Anything else**: open an issue on https://github.com/codexofc/bold7/issues with your BenOS version and what you saw, or send me a DM on Discord (`_codexofc`).
