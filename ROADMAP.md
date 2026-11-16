# Roadmap

Bold7 works today on a Q25 installed from source. The next milestone is the public installer, so that any Q25 owner can install Bold7 from the KernelSU manager with two downloads and a reboot. After that, the work continues on everything that still shows Android.

## Done

**Home and system**
- Home screen: OS 7 banner, wallpaper, rising drawer with Favourites, All, Frequent, Downloads and Media views, folders, Move, Hide, Show All, Delete, trackpad focus.
- Universal search by typing, app switcher on a long press of Menu.
- OS 7 icons and names, generated icons for unknown apps.
- Two-line OS 7 status bar in apps, indicators from the theme.
- Notification list from the banner; Android's floating cards removed except for calls, alarms and full-screen intents.
- Sound profiles, Manage Connections, volume window, power-off dialog and shutdown screen, status messages in place of toasts.
- OS 7 lock screen, password entry and SIM PIN box, with verification left to Android.
- Framework dialogs, menus, pickers and choice lists restyled for third-party apps; OS 7 palette; Android Settings and permission dialogs brought closer to Options.
- BBAlpha as system font with the 9900's fallback fonts, OS 7 sounds, silenced Android interface sounds, OS 7 boot animation, OS 7 transitions, OS 7 wallpapers.
- End key to home, trackpad at OS 7 sensitivity with no pointer and thumb rejection.

**Apps**
- Messages: unified inbox, SMS, MMS and RCS threads through Google Messages, email with push and browser sign-in, attachments, emoticons, options.
- Phone, Contacts, Calendar, Clock, Calculator, MemoPad, Tasks, Pictures, Options, Flashlight.
- Typing: Direct and Predictive styles, word substitution, word learning, Sym, keyboard selection, cut, copy and paste.

**Installer groundwork**
- Feasibility proven on the device: removal script run by KernelSU, nested module install, app install from the module, fabricated overlays carrying an extracted file, manager update flow.
- Logo and module banner.
- Extractor in pure Kotlin for the official OS Loader, byte-identical to the reference extraction for fonts, home screen, icons, interface and Messages resources.
- Runtime image store: OS 7 images read by name from the extracted resources, with a clean fallback; Calculator, Flashlight and the shared components already use it.
- Public build that fails if any release APK contains a BlackBerry file.
- Ed25519 signing key for release zips.

## Next: the public installer

1. **Extraction on the phone.** Finish `core/firmware`: the official simulator as a second accepted file, the remaining resource blocks, sounds. Run by `customize.sh` in root through `app_process`, so no app needs `su`. If a full extraction takes more than a minute on the Q25, the slow decompressor moves to Rust behind the same API.
2. **Everything on the runtime store.** Move the launcher, icons, SystemUI, Clock, Messages, Phone, Contacts, Pictures, Tasks, Calendar, MemoPad and the test app from compiled resources to runtime reads, then sounds, fonts and the typing dictionaries.
3. **Module scripts.** Port the developer install and uninstall scripts into the module: device and BenOS checks by file hashes, apps, Vector, permissions, roles, settings backups in `/data/adb`, and a two-step removal (files at the next boot, Android settings once boot completes).
4. **Overlays without images.** Replace the overlays that carry firmware images with image-free overlays plus fabricated overlays built from the extracted files; fonts and sounds placed from the store.
5. **First boot.** Home, phone app, input method, permissions, sounds, silences and End key set automatically; a single OS 7 screen at first unlock for the Google Messages consent.
6. **Setup wizard.** The OS 7 Setup Wizard, sourced from the firmware and the simulator.
7. **Signed publication.** Release page, `updateJson` with a changelog, signed zips, complete `NOTICE` (including Vector under GPL-3.0), and a full clean install and removal verified on a Q25.

## Next: what still shows Android

**Home and transitions**
- The app status bar slides in when an app resumes, and Android animates the status bar between home and an app.
- Delete on the home screen goes through Android's uninstall dialog.
- FM Radio, Google Drive and Meet keep their Android names.

**Framework dialogs and menus in third-party apps**
- Dialog titles, the app icon instead of the four OS 7 icons, radio buttons and check boxes, underlined shortcut letters.
- Position of overflow and context menus, menus of Material, Holo and AppCompat, cascading menus.
- The closed field of spinners, AppCompat and Material suggestions, remaining details of the date and time pickers.

**System**
- Android's screen when rebooting to recovery, and `system_server`'s shutdown box when SystemUI does not answer.
- The emergency dialer behind Emergency on the lock screen and in the power-off dialog.
- The sound dose warning (CSD) wording.
- Notification cards under the lock screen face, the exit animation after a correct code, the pattern unlock view.

**Apps**
- Calendar: the OS 7 reminder (vibration in Normal) in place of Google Calendar's sound; multiple alarms.
- Pictures: Rotate that saves the file, List view, Properties, Rename, Cut, Copy, Move to Folder, slide show options.
- Files: an OS 7 file explorer and document picker.
- Clock: Set Time in Options, Turn Off with wake-up for an alarm.
- Phone: call settings and the emergency dialer.
- Contacts: adding to an existing contact from another app.
- Android Settings pages still opened from Options: Sounds for Contacts, Call Management, Message on Lock Screen, custom dictionary, spell check.
- Camera and Music still open Android apps.

**Profiles and sounds**
- One ringtone, notifier and reminder tone per profile; personal profiles; per-contact sounds.
- Per-category notification sounds (text, email, calendar, browser) through the apps' channels.

**Typing**
- The Sym grid from a 9900 capture, OS 7 spelling marks instead of Android's, the remaining Advanced Style Options.

**Performance**
- Bring the drawer's 95th percentile under 16.6 ms.
- Measure tap to activity start on the OS 7 home screen.

## What Bold7 will not do, and why

- **Write to a partition.** The only safety net of the Q25 is KernelSU's safe mode, which disables modules. Anything written to a partition would survive it.
- **Replace the bootloader logo and the screen before Android** (Zinwa or BenOS). They live in partitions; see above.
- **Replace Chrome.** Chrome stays the browser, under the name Browser.
- **Distribute BlackBerry assets**, in any form, encrypted or not. Copyright follows the copy, not its packaging. Each user provides the official 9900 file.
- **Verify the lock code itself.** The code goes straight to Android's verification, which protects the encrypted storage and the keystore.
- **Show a sign-in page other than the provider's.** Gmail and Outlook passwords are typed only in the provider's page in the browser.
- **Remove Android's floating cards for calls and alarms of third-party apps.** They stay for safety.
- **Redraw the inside of third-party apps.** Their own dialogs, menus and screens keep their design; the system font and the framework overlays bring them closer.
- **Add gestures.** BlackBerry OS 7 had none.
- **Replace the finger selection bar.** The 9900 had no touchscreen; keyboard selection is OS 7.
- **Draw scripts the 9900 could not write.** Ethiopic, Armenian, Georgian, Khmer, Burmese and emoji fall back to Android's fonts.
- **Mark a text as unread in Google Messages.** Its API has no such method; RCS threads depend on that API staying open.
