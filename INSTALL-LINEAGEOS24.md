# Installing Bold7 on LineageOS 24

This guide takes a Zinwa Q25 running the official LineageOS 24 to Bold7. The first time takes about 30 minutes, most of it spent on root. If your Q25 runs BenOS, follow the [BenOS guide](INSTALL-BENOS.md) instead: each zip refuses the other system.

Steps 1 to 4 prepare the phone (LineageOS, Google apps, root, Zygisk). If they are already done, go straight to step 5. Bold7 Setup, a desktop installer for Windows, macOS and Linux that does all of this for you, is on the way: see the [roadmap](ROADMAP.md).

## What you need

- A Zinwa Q25.
- A computer with Android's `platform-tools` (`adb` and `fastboot`), and a USB cable. Only step 3 needs the computer: Bold7 itself installs from the phone.
- About 600 MB free on the phone.
- The official BlackBerry Bold 9900 OS 7.1 file (step 5). Bold7 contains no BlackBerry file and does not link to copies of it.

## 1. LineageOS 24

If your Q25 does not run LineageOS 24 yet, follow the official install guide for the Q25 on the LineageOS wiki: https://wiki.lineageos.org/devices/Q25/

Installing LineageOS wipes the phone. Back up your data first.

To check which system runs: Settings, About phone, LineageOS version. Write down the build date (for example `24.0-20261115-NIGHTLY-Q25`): step 3 needs the `boot.img` of exactly this build.

## 2. Google apps (MindTheGapps)

Bold7's Messages sends and receives texts through Google Messages, which needs Google apps. Install **MindTheGapps for Android 17 (arm64)** as the LineageOS wiki explains, from the LineageOS recovery right after flashing LineageOS and before the first boot. Google apps added after the first boot usually need a factory reset.

Then, once the phone is set up, install **Google Messages** from the Play Store and open it once.

## 3. Root with KernelSU Next

Bold7 is a KernelSU module, written for **KernelSU Next as a kernel module (LKM)**. LineageOS has no `init_boot` on the Q25: the patch goes on **`boot`**.

1. On the computer, download the **`boot.img` of your exact build** from https://download.lineageos.org/devices/Q25/builds (the build date from step 1). Keep an untouched copy: it is your way back.
2. Copy `boot.img` to the phone's `Download` folder.
3. Install the **KernelSU Next manager** APK from https://github.com/KernelSU-Next/KernelSU-Next/releases and open it. It says it is not installed: tap **Install**, then choose to **select and patch a file**, and pick `boot.img`.
4. When the manager asks for the KMI, choose **`android12-5.10`**. The kernel name of LineageOS does not contain it, so the manager cannot guess it.
5. The manager writes a patched image into `Download` (its name starts with `kernelsu_next_patched`). Copy it to the computer.
6. Turn on USB debugging (Settings, About phone, tap Build number seven times, then Settings, System, Developer options, USB debugging), plug the phone in, and run:
   ```
   adb reboot bootloader
   fastboot flash boot kernelsu_next_patched_XXXX.img
   fastboot reboot
   ```
7. Open the KernelSU Next manager: it now shows KernelSU as working.

If the phone does not start after flashing, go back to the bootloader and flash the untouched `boot.img` the same way.

## 4. Zygisk: NeoZygisk

Bold7 installs Vector, the hooking framework it relies on, by itself. Vector needs Zygisk:

1. Download the latest **NeoZygisk** zip from https://github.com/JingMatrix/NeoZygisk/releases into `Download`.
2. KernelSU Next manager, **Modules**, **Install**, pick the zip.
3. Tap **Reboot**.

Do not install a metamodule (meta-overlayfs, for example): Bold7 mounts its files itself and refuses to install next to one.

## 5. The two files of Bold7

Put both in the phone's `Download` folder, by USB from the computer or downloaded on the phone:

1. **`bold7-0.1.0-lineage24.zip`**, from https://github.com/codexofc/bold7/releases (the release "Bold7 0.1.0 for LineageOS 24"). Do not unzip it.
2. **The official Bold 9900 file**, one of these two, as is: not renamed, not unzipped. If you received it inside a zip, unzip that zip on the computer and copy only the `.exe`.

| File | Size (bytes) | SHA-256 |
|---|---|---|
| `9900M_PBr7.1.0_rel2807_PL5.1.0.692_A7.1.0.1033_Wind.exe` | 273797496 | `4f68dd201def6f3f5c648ce166e7e0e5ff713c79f55f97cb777b8aafa8b85adb` |
| `BlackBerry Simulator_7.1.0.318_9900.exe` | 240527751 | `79208dc1036487b05a1be007063bc377a649f4d9a973e58bade2ac06878474f1` |

Bold7 recognises the file by its size and SHA-256 and accepts nothing else.

**Need help finding the official 9900 file?** Contact me for advice by email at arthur@sw3rm.com or on Discord (username `codexofc_`).

**Optional, icons of your own.** Bold7 draws every app the 9900 never had with its own icon, framed like the 9900's. If you have older icons for some apps, put a folder `Bold7-icons/` of `<package name>.png` files (for example `com.whatsapp.png`, PNG, 1 MB at most each), or a zip holding that folder, in `Download` too. The installer copies them to `Bold7/icons/` on your storage, where you can add more later.

**Optional, check the download.** With OpenSSL 3 on the computer, the `.sig` file next to the zip on the releases page, and the key `bold7-release.pub` from the repository (`system/module/release/`):
```
openssl pkeyutl -verify -pubin -rawin -inkey bold7-release.pub -in bold7-0.1.0-lineage24.zip -sigfile bold7-0.1.0-lineage24.zip.sig
```
It prints `Signature Verified Successfully`.

## 6. Install

1. KernelSU Next manager, **Modules**, **Install**, pick `bold7-0.1.0-lineage24.zip`.
2. Read the log as it scrolls. The installer checks that the phone is a Q25 on LineageOS 24, that no metamodule is installed and that the 9900 file is there and recognised; then it extracts the 9900 resources and installs Vector and the Bold7 apps. If a check fails, it stops **before changing anything** and says why (for example which file to put where).
3. Tap **Reboot**.

## 7. First start

The first boot sets Bold7 as home screen, phone app and input method (Typing), grants its permissions, applies the OS 7 sounds and silences, and sets the screen density to 208, so that one pixel of the 9900 is one pixel of the Q25.

At the first unlock, the OS 7 Setup Wizard opens:

1. **Welcome**: "Bold7 is installed."
2. **What Changed**: what Bold7 took over (home screen, Phone, Text Messages, Contacts, Calendar, Clock, Typing, sounds, the End key). If you would rather keep your previous home screen or phone app, this page opens Android's Default apps for you; Bold7 stays installed.
3. **Text Messages**: click **Open Google Messages**, turn on **"Allow Bixby and text on Galaxy devices"**, then press Back. This lets Bold7's Messages show and send your texts through Google Messages. If you skip it, texts open in Google Messages, and you can come back to it later from **Setup** on the home screen.
4. **Sound and Wallpaper**: keep your wallpaper, or pick one of the 9900's.
5. **All Set**: three keys to remember. **Menu** shows what you can do on any screen, **End** takes you back to the home screen, and on the home screen you **just type** to search your phone.

If the wizard says the 9900 file was not found, put it in `Download` as in step 5, install the zip again from the manager and reboot.

## Weekly LineageOS updates: keep root

Each LineageOS update rewrites `boot`, which removes root; without root, the Bold7 module is not loaded. Before rebooting into an update:

1. Settings, System, Updater: download and install the update, **but do not reboot yet**.
2. KernelSU Next manager, **Install**, **Install to inactive slot (after OTA)**.
3. Reboot from the manager.

If you rebooted too early, redo step 3 with the `boot.img` of the new build.

## Bold7 updates

The KernelSU Next manager offers each new version of Bold7 on the module's card: one tap, then reboot. The resources already extracted from the 9900 file are kept.

## Removing Bold7

KernelSU Next manager, **Modules**, Bold7, **Remove**, then reboot. Once the phone is unlocked, Bold7 gives back everything it changed: home screen, phone app, input method, settings, sounds and the screen density. Your `Bold7/icons/` folder stays.

## If something goes wrong

- **The phone does not start after installing Bold7**: press **Volume down** repeatedly while it boots. KernelSU starts in safe mode with every module turned off. Remove Bold7 from the manager, then reboot.
- **The installer stops**: the last lines of the log say why. Nothing was changed.
- **Texts do not appear in Messages**: open **Setup** on the home screen, Text Messages, and check that "Allow Bixby and text on Galaxy devices" is on in Google Messages.
- **Anything else**: open an issue on https://github.com/codexofc/bold7/issues with your LineageOS version and what you saw, or write to me by email (arthur@sw3rm.com) or on Discord (`codexofc_`).
