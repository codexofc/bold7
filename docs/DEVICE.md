# The Q25: measured facts

Read on the device over adb, read-only, then rechecked with `tools/state.sh` (a normalized snapshot, comparable with `diff`). To recheck after every BenOS OTA.

## Identity

| Item | Value |
|---|---|
| Lineage | **Descendant of the BlackBerry Classic (Q20, SQC100)**, not of the Q10: square 720x720 screen, trackpad, a row of Call, Menu, Back and End keys above the keyboard. An earlier BB10-style version of the launcher therefore took the Classic 10.3.x as its reference |
| Model | Zinwa Q25 (fingerprint `Xelex/Xelex10_Ultra/Xelex10_Ultra:14/20240427/UP1v:user/release-keys`) |
| ROM | BenOS `BenOS-Valarie_stablePublicRel` (`ro.fota.version` = `Q25-BenOS_06.06.2026`), Android 14 (SDK 34), `user` build, active slot `_a`, SELinux `Enforcing` |
| SoC | MediaTek Helio G99 (`mt6789`), arm64-v8a, 12 GB of RAM |
| Screen | 720x720, density 208 (factor 1.3), **60 Hz only**, cutout declared at the top (52 px inset), 31 px status bar |
| Updates | OTA through Custota (`ph.dgsd.Custota`). An OTA can replace SystemUI and Launcher3: recheck this file after every OTA |

## Root and modules

- KernelSU-Next (LKM, GKI2), `ksud 3.2.0`. Root granted to the adb shell (`adb shell su -c …`).
- Modules listed by `ksud module list`: `playintegrityfix-benos`, `rezygisk` (Zygisk available), `tricky_store` (actually TEESimulator-RS v6.0.1), `meta-overlayfs` (metamodule, see below).
- `/data/adb/modules/TA_enhanced` is a **folder without `module.prop`**: `ksud` does not list it as a module. It belongs to the `tricky_store` tooling. Do not touch it, and do not take it for a module to disable.
- **A module's `system/` folder is not mounted.** `ksud module mount` answers `Mount system: Meta`: this KernelSU Next delegates mounting to a metamodule, and none is installed. Proven with a test module whose file under `system/etc/` never appeared in `/system/etc` after the reboot. This is why a previous launcher's module did its own bind mounts in its `post-fs-data.sh`. A module's scripts run normally, `boot-completed.sh` included.
- **A module is removed, never disabled**: `ksud module uninstall` marks it `remove`. A previous launcher's module that was disabled by hand had its reinstallation erased at the next boot.
- **Metamodule installed: `meta-overlayfs` 1.3.1** (KernelSU project, sha256 `279d85a6…8811`). It stores the modules' `system/` in a 2 GB *sparse* ext4 image (`/data/adb/metamodule/modules.img`, mounted on `/data/adb/metamodule/mnt`) and mounts it as overlayfs at boot. Proven by a round trip: a marker file mounted with the `system_file` context, then gone on removal; the ReZygisk, TEESimulator and PIF daemons unchanged. Two flaws, compensated by Bold7's scripts: it does not clean its image when a module is removed (`tools/uninstall.sh` does it on its second run), and it copies an update over the previous one without emptying it (`tools/install.sh` deletes the files that disappeared). While it is waiting for activation, KernelSU refuses any other module installation. Only one metamodule can be installed at a time. **To be removed** (`tools/remove-meta-overlayfs.sh`): Bold7 mounts its files itself. Its `customize.sh` creates the image in the module's folder (`/data/adb/modules/meta-overlayfs/modules.img`); `/data/adb/metamodule` is the link KernelSU puts to it.
- **ReZygisk unmounts module mounts in app processes** (it handles KernelSU's `kernel_umount` feature, which the kernel leaves disabled): 2 `KSU` mounts in `init`, 0 in SystemUI. A file placed by a module is read correctly only by `system_server`, native processes and what zygote preloads (framework resources). An APK placed in `/system/priv-app` cannot be read by its own process, and a system font makes SystemUI crash in a loop.
- **What ReZygisk v1.0.0 unmounts, according to its code** (tag `v1.0.0`, commit `333d423`, the one on the phone):
  - *who*: the app processes KernelSU says must be unmounted (`ksu_uid_should_umount`, `zygiskd/src/root_impl/kernelsu.c` l. 191 to 214: the "unmount modules" setting of the app profile, on by default for every app without root); ReZygisk turns off the kernel's unmounting to do it itself (`kernel_umount` at 0, same file l. 145 to 151);
  - *when*: before specialization, before any app code (`rz_app_specialize_pre`, `loader/src/injector/hook.c` l. 1033 to 1143, the namespace switch l. 1108 to 1142; flag set by `zygiskd/src/zygiskd.c` l. 391): the process joins a "clean" mount namespace through `setns`;
  - *how*: that namespace is built **only once** and then kept (`save_mns_fd`, `zygiskd/src/utils.c` l. 732 to 861): a copy of zygote's namespace (`unshare(CLONE_NEWNS)`), then `umount_root`;
  - *what* (`umount_root`, `zygiskd/src/utils.c` l. 667 to 730): every mount in `/proc/self/mountinfo` whose **source is `KSU`**, **or whose mount point starts with `/data/adb/modules`**, **or whose mount root starts with `/adb/modules/`** (a bind mount of a file from `/data/adb/modules/...`), detached with `umount2(target, MNT_DETACH)`, newest first. Nothing looks at the options (`lowerdir`) of an overlayfs.
  - Consequences for Bold7: an overlayfs with source `bold7` placed on a system folder has `/` as its root and matches none of the criteria, wherever its lower layer is (overlayfs keeps a private clone of its layers taken at mount time, `clone_private_mount`). It must exist before the first app, hence in `post-fs-data.sh`. A bind mount of a file from the module would be removed (root under `/adb/modules/`). And a `KSU` mount placed on a parent folder of Bold7's (a metamodule that mounts `/system`) would take it along in apps: hence the refusal to mount when a metamodule is installed.
- **LSPosed is not installed.** Its JingMatrix fork, renamed Vector (v2.2), was installed and then cleanly removed during an early test.
- BenOS apps to leave alone: `com.machiav3lli.backup` (NeoBackup), `benos.q25.keymapperbootfix` and Key Mapper (accessibility services for the keyboard and the PIN at boot), `ph.dgsd.benos.attestmon`.
- **overlayfs refuses a lower layer on `/data`**: `mount: 'bold7'->'…': Invalid argument`, and `dmesg`: `overlayfs: filesystem on '/data/local/tmp/ovl/l' not supported`. `/data` is f2fs (`/dev/block/dm-55`) with per-file encryption (and probably casefold), kernel `5.10.198-android12-9`. This is why meta-overlayfs stores module contents in a loop-mounted ext4 image. Bold7 does the same: `layer.img` in the module's folder, built at installation (`customize.sh`), mounted read-only on the module's `img/` at boot. On this ROM, `/system_ext/overlay` and its overlays carry the `vendor_overlay_file` context, not `system_file`.
- According to the KernelSU documentation (not checked on the phone), it runs the module installer (`customize.sh`) and module scripts with its busybox (`/data/adb/ksu/bin/busybox`) in *standalone* mode: `mount`, `cp`, `chmod` there are busybox's, not toybox's. The module scripts call `/system/bin/chcon` and `/system/bin/mke2fs` by their path.
- **No flashable rescue image.** The only safety net is KernelSU's safe mode: press Volume down several times during boot to disable all modules. Everything Bold7 installs must be removable that way.

## Stock SystemUI and launcher

- SystemUI: `/system_ext/priv-app/MtkSystemUI/MtkSystemUI.apk` (`com.android.systemui`, versionCode 34), sha256 `5f67ca21080e5f58b181ab2859395d8a433f735d162d767dcd9e0b7539a8dbeb`. This is the fingerprint the hook guard checks.
- Stock launcher: `com.android.launcher3`, `/system_ext/priv-app/SearchLauncherQuickStep/SearchLauncherQuickStep.apk` (sha256 `15bb0e875d8c00af224f391989a0067a0c68314bd335d79bf9866b1c85944970`), activity `com.android.searchlauncher.SearchLauncher`. Enabled: it holds HOME and it is the recents provider.
- This Launcher3 contains the quickstep interfaces compiled for this exact SystemUI: `ISystemUiProxy`, `IOverviewProxy`, `IRecentTasks`, `IRecentTasksListener`, `IShellTransitions`, `InputMonitorCompat`, `RecentsAnimationController(Compat)`. It is the reference for aligning Bold7's AIDL with the device.
- `config_recentsComponentName` is `com.android.launcher3/com.android.quickstep.RecentsActivity` (stock value), and SystemUI is bound to Launcher3 (`OverviewProxyService`: `isConnected=true`). A previous launcher's overlay pointed it at that launcher, which was bound the same way: the overlay plus `QUICKSTEP_SERVICE` path therefore works on this ROM.
- SystemUI re-evaluates the recents provider only at its start, **before unlock**, with `MATCH_SYSTEM_ONLY`: only a `directBootAware` service is kept. A launcher enabled later is bound only after SystemUI restarts.
- Status bar height: `SystemBarUtils.getStatusBarHeight` in the Q25's `framework.jar` reads `status_bar_height_default` (24 dp, 31 px) and compares it with the cutout, as AOSP does. The older, separate `status_bar_height` dimension is **40 dp (52 px)** on BenOS (`@dimen/status_bar_height_portrait`; `_landscape` 28 dp; `cmd overlay lookup`); in SystemUI only the lock screen previews of the wallpaper picker read it (`KeyguardRemotePreviewManager`, `KeyguardPreviewSmartspaceViewModel`), the shade, the bouncer and the privacy dots going through `SystemBarUtils`. Apps read it through `getIdentifier`: the Bold7 RRO sets it to the banner height (67 px).
- Hiding a status bar icon: `IconManager.addHolder` consults the blocklist (`config_collapsed_statusbar_icon_blocklist`, `setBlockList`) only for simple icons, not for the Wi-Fi and mobile views (classic pipeline, `StatusBarWifiView`, `StatusBarMobileView`); and MediaTek's `StatusIconContainer` shows `mobile` even among its ignored slots. Only the view's `isIconVisible()` decides for them.
- Status icon slots: `config_statusBarIcons` in `framework-res.apk` (sha256 `a0bf9cca12475eb8541c246db1bbfac766b6e1ec0f5cd29258cbfc03e529261b`), 35 names in AOSP 14 order (`no_calling`, `call_strength`, `alarm_clock`, `rotate`… `battery`, `sensors_off`), which no BenOS RRO touches; MediaTek adds `embms` at the head. The `Bold7Android` RRO reorders them and `StatusIconsTest` compares its array with this hard-coded list. **To recheck after every OTA**: `adb shell cmd overlay lookup com.android.systemui android:array/config_statusBarIcons` gives the list SystemUI reads, Bold7's as long as the module is mounted; with the module off, or with `aapt2 dump resources` on the `framework-res.apk` pulled from the phone, the ROM's. A slot added by an OTA is missing from Bold7's array: SystemUI then places it at the head, before the alarm, silently; `dumpsys activity service com.android.systemui/.SystemUIService StatusBarIconControllerImpl` ("icon slots") shows it. It must then be added to the RRO and to the test.
- Vector calls `onPackageReady` for every package loaded in the SystemUI process, and **twice for `com.android.systemui`**: the second time with a class loader without dex (KeyMapper's native libraries), which does not see SystemUI's classes.
- `ro.control_privapp_permissions=enforce`: a priv-app that requests a privileged permission missing from its whitelist prevents boot.
- The microphone is **not** blocked by the privacy setting (`dumpsys sensor_privacy`, microphone and camera in the "disabled" state); the "Unblock device microphone?" dialog seen in an earlier test was not explained.
- **SIM card** inserted: LTE and VoLTE in service. The ringer volume is zero (`SKIP_RINGING … isVolumeOverZero=false` in Telecom).
- MediaTek's **boost framework** (`com.mediatek.boostfwk`), loaded in every app, reads the disk on the main thread on every touch event (`ViewRootImpl.processPointerEvent` → `perfHint` → `isGameApp` → `File.exists`). StrictMode tolerates it (`core/platform`), but it is a cost on every touch in every app, launcher included.
- MediaTek code in `system_server` reads the disk on every foreground activity change (`ScnModule.isGameApp`): StrictMode reports it to apps (see `core/platform`).
- `AppPredictionManagerService` refuses `createPredictionSession` to anyone who is neither the recents provider nor a holder of `PACKAGE_USAGE_STATS`: Launcher3 crash-loops if it is HOME without being the recents provider (see `docs/BASELINE.md`).

## Default roles

| Role | Current holder |
|---|---|
| HOME | `com.android.launcher3` (stock launcher) |
| DIALER | `com.google.android.dialer` |
| SMS | `com.google.android.apps.messaging` |

## Inputs

| Device | Classes | Note |
|---|---|---|
| `Q25_keyboard` | KEYBOARD, ALPHAKEY, **CURSOR** (source KEYBOARD + MOUSE) | `/dev/input/event2` (the number can change: `bold7-trackpad` looks it up by name), I2C bus (`0x18`), vendor, product and version 1, no property; keyboard **and** trackpad on this single device; opened by `init` and `system_server`. The trackpad sends **either arrows (`DPAD_*`) or a relative pointer** (`REL_X`, `REL_Y`, `BTN_MOUSE`), depending on `/proc/q20_switch_key_mouse` (1 at boot, set by `bbqX0kbd_probe`), which `Q25TrackpadCustomizer` switches per app and `bold7-trackpad` sets to 0 while it runs ("Trackpad" section). Android classes it as a pointer (`CursorInputMapper`, `POINTER` mode): trackpad navigation handles both. Layout `/system/usr/keylayout/Q25_keyboard.kl` and `keychars/Q25_keyboard.kcm`: system row among `KEY_SEND` (`CALL`), `KEY_BACK`, `KEY_HOMEPAGE` (`HOME`), `KEY_APPSELECT` (`APP_SWITCH`), `KEY_ESC`, with the key mapping in the "Top-row keys" section below. The `.kl` is AOSP's `Generic.kl` (Apache 2.0) plus two lines, `#key 100 SYM` commented out and `key 231 CALL` (`diff` with `/system/usr/keylayout/Generic.kl`, sha256 `cd79a0a260a00ef5d00c5a7523330677319dbf2aa95e69627aec39b511899622`); no line has `WAKE`. It is found by name (`keyboard.layout = Q25_keyboard` in `/system/usr/idc/Q25_keyboard.idc`, with `device.internal = 1`); libinput looks for it in `/product/usr/keylayout`, `/system_ext/usr`, `/odm/usr`, `/vendor/usr`, the `com.android.input.config` APEX, then `/system/usr`, and `/data/system/devices` last: only `/system/usr/keylayout` has one on the Q25, `/product/usr` exists (`system_file`, containing only `srec/`), `/product/usr/keylayout` does not. Bold7's module places a copy under `/product/usr/keylayout` where End is `ENDCALL`. Another device has vendor 1, product 1, version 1 (bus `0x19`, layout `Generic.kl`): a `Vendor_0001_Product_0001.kl` would affect it too. Key Mapper: no mapping configured |
| `s2716b_ts` | TOUCH, TOUCH_MT | touch screen ("Touch screen" section); grabbed by `bold7-trackpad`, which creates a twin with the same name |
| `mtk-pmic-keys`, `mtk-kpd` | KEYBOARD | power and volume keys |
| Virtual keyboard | KEYBOARD, ALPHAKEY, DPAD, VIRTUAL | `Virtual.kcm` |
| `mt6789-mt6366 Headset Jack` | KEYBOARD, SWITCH | headset jack |

**Key Mapper crashes at every boot**, before unlock (`SQLiteCantOpenDatabaseException` on its database, in encrypted storage); this predates Bold7. And Gboard, which the system keeps disabled, is re-enabled by Android when SystemUI or the home screen crash-loops; it disables itself again within two boots.

Default input method: **the BlackBerry keyboard** (`com.blackberry.keyboard/com.blackberry.inputmethod.core.BlackBerryIME`, priv-app `/system/priv-app/BBKB`). Gboard is installed but disabled.

The keyboard firmware (RP2040) is v1.2. It is updated through `/proc/firmware_upgrade` as root.

## Trackpad

Read-only readings (`getevent -pl`, `dumpsys input`, `/proc`, SELinux, the `bbqX0kbd.ko` driver extracted from `vendor_boot_a` and disassembled, Q25TrackpadCustomizer decompiled).

- **Chain**: sensor read by an RP2040 (Solder Party's i2c_puppet firmware adapted by Zinwa, v1.2, with no sensor setting at all); `bbqX0kbd` driver (Hodafone, kernel `5.10.198-android12-9`, `scmversion gc8e66e3b42fd`), no parameter, three `/proc` entries (`q20_switch_key_mouse`, `q20_spec_power_flag`, `firmware_upgrade`).
- **Mode 1, arrows** (the stock behaviour): two signed 8-bit accumulators; an arrow as soon as one axis reaches **27 counts** (horizontal wins), only one per read, then **both accumulators restart from zero** (the surplus is lost); **no reset over time**; the click gives `KEY_ENTER`. Mode 0, pointer: raw `REL_X`, `REL_Y`, the click `BTN_MOUSE` (`BTN_RIGHT` with a modifier). Writing `0` or `1` also changes the click code (`q20_switch_key_mouse_debug_write`).
- **Permissions**: the file is `0777`, of type `q25_trackpad_proc`: SELinux allows writing it only to `system_app` (Q25TrackpadCustomizer) and to KernelSU's root (`ksu` domain), not to `priv_app`. `/dev/uinput` is `uhid_device`, accessible to the `ksu` domain.
- **Q25TrackpadCustomizer** (`tech.shroyer.q25trackpadcustomizer` 1.4.2, BenOS priv-app, `system_app` domain): **its `AppSwitchService` accessibility service is active** (`enabled_accessibility_services`, with `keymapperbootfix` and Key Mapper). It rewrites the mode on every app change (preferences: `system_default_mode = 1`, no rule for `dev.bold7.*`), and also filters keys (modes while a key is held, "Scroll Wheel" which turns arrows into scrolling); stopping it writes nothing (`onDestroy`). It never re-enables its service by itself (it only reads the setting). Measured cost on the launcher: `docs/PERF.md` §6. `tools/trackpad.sh poser` turns it off, `retour` restores it.
- **keymapperbootfix** (`benos.q25.keymapperbootfix`, PIN entry at the lock screen through accessibility) does not look at which device a key comes from (no call to `getDevice`, `getDeviceId` nor any descriptor in its dex).
- The driver writes every read to the kernel log at `KERN_ERR` level ("X Reg: %d Y Reg: %d. ", "relX_offset= …", "------>key code = %x"): `dmesg` gives the raw counts without changing anything. **It also writes the code of every keyboard key there**: everything typed, passwords and PIN included, is readable in `dmesg` by root, independently of Bold7 (it is the BenOS driver); keep this in mind before sharing a `dmesg` or a bug report.
- **Keyboard firmware update** (`/proc/firmware_upgrade`): first set `adb shell settings put global bold7_trackpad_off 1`, so that `bold7-trackpad` releases the device while the microcontroller restarts.

### Top-row keys (`getevent -lt`, on `Q25_keyboard`)

| Key | Event | `MSC_SCAN` | Android code (`Q25_keyboard.kl`) | Note |
|---|---|---|---|---|
| Menu (BlackBerry logo, left of the trackpad) | `KEY_APPSELECT` (580) | `0x11` | `APP_SWITCH` | one DOWN and one UP, **no repeat** even when held 1.65 s; `PhoneWindowManager` takes it before any app: without `bold7-trackpad`, Menu opens no menu |
| Back (the 9900's Escape) | `KEY_BACK` (158) | `0x07` | `BACK` | |
| End (red) | `KEY_HOMEPAGE` (172) | `0x12` | `HOME`, **`ENDCALL` with the module** | read during a call; as `HOME`, it did not hang up (Telecom disconnected when the other party hung up, `DisconnectCause REMOTE`) |
| Call (green) | `KEY_SEND` (231) | | `CALL` | read during the same call; it answered (`PhoneWindowManager`: `acceptRingingCall` on press while ringing) |
| trackpad click | `KEY_ENTER` (28) in arrow mode, `BTN_MOUSE` in pointer mode | | `ENTER` | |
| Alt | `KEY_LEFTALT` (56) | | `ALT_LEFT` | |
| Left Shift, right Shift | `KEY_LEFTSHIFT` (42), `KEY_RIGHTSHIFT` (54) | | `SHIFT_LEFT`, `SHIFT_RIGHT` | |
| Sym | `KEY_RIGHTALT` (100) | | `ALT_RIGHT` | BBKB takes it as Sym; no `SYM` line |

`end_button_behavior` (system) is **2** (sleep) on the Q25; it acts only on `ENDCALL`. What `PhoneWindowManager.interceptKeyBeforeQueueing` in the Q25's `services.jar` does (read with jadx in raw mode, identical to AOSP 14): `ENDCALL` is **never** passed to apps; on press, `TelecomManager.endCall()` (rejects a ringing call, hangs up the foreground call, never ends an emergency call); if nothing was hung up and the screen is on, holding it for `config_globalActionsKeyTimeout` (500 ms) opens the power-off dialog; otherwise, on release, bit 1 of the setting calls `goHome()` (the `CATEGORY_HOME` intent with `START_FLAG_ONLY_IF_NEEDED`: nothing when the home screen is already in front), bit 2 puts the screen to sleep. With the screen off, neither `HOME` nor `ENDCALL` wakes the device (no `WAKE`), but `ENDCALL` still hangs up.

Modifier keys (`getevent -lt` on the `bold7-trackpad` twin): Alt sends `KEY_LEFTALT` (`ALT_LEFT`), left Shift `KEY_LEFTSHIFT`, right Shift `KEY_RIGHTSHIFT`, **Sym `KEY_RIGHTALT`** (100, `ALT_RIGHT` in the `.kl`, which BBKB takes as Sym); Alt and right Shift together: `KEY_LEFTALT` DOWN, `KEY_RIGHTSHIFT` DOWN then UP, `KEY_LEFTALT` UP. The `.kl` has no `SYM` line.

`KEY_MENU` (139) is `MENU` in the same `.kl`, but the keyboard never sends it. `bold7-trackpad` makes Menu behave like the 9900's (guide 7.1, "Important keys", B): short press, `KEY_MENU` on release; held 400 ms (Android's `long_press_timeout` on the Q25, D), `KEY_APPSELECT` at once, nothing on release. The threshold is in `model.h` (`TP_MENU_LONG_MS`).

### Measure (4 h 40, arrow mode, Bold7 home screen)

`adb shell "su -c 'dmesg -w'"` and `getevent -lt` for 2 minutes; seven gestures, 3 s apart. 909 driver reads, 205 arrows seen by `getevent` (the same as the driver's), no `REL_`. Split at pauses longer than one second, then the reads replayed through the driver (without the remainder from before the measure) and through the `bold7-trackpad` model at 70 and 70.

- **Rate**: one read every **7.5 ms** while moving (133 per second), 1 to 4 counts per read when slow, **up to 42** when fast; no overflow of the 8 bits (it would take more than 101 counts in one read), no reversed arrow.
- **Travel**: one pad width is **200 to 240 counts** (fast: −205 and +219; slow: −240, −243); one height, 90 to 120. A horizontal stroke drifts vertically by **25 to 45 %** (the arc of the thumb).

| Gesture | Reads | Counts (X, Y) | Driver arrows | Model at 70 |
|---|---|---|---|---|
| one notch right | 12 in 80 ms | +52, +21 | **RR** (one of them from the remainder of a movement before the measure: replayed alone, R) | no step (52 < 60; one step from 90) |
| slow stroke left then right | 176 in 7.1 s | 1,055, 565 in absolute value | 35, of which **8 vertical** | 14, of which 2 vertical (a real vertical movement at the turn) |
| fast stroke left-right | 32 in 1 s | −205 then +219 | **5 + 5** | 3 + 3 |
| following fast strokes (12 back-and-forths) | 124 in 2.3 s | 2,036 in X | 43 | 30 |
| slow stroke up-down | 113 in 5 s | 516 in Y | 15 | 5 |
| diagonal | 40 in 2.2 s | 402, 482 | 11, alternating axes (DDLULRRRULU) | 7 (DUURRUU) |
| light touch after 12 s of rest | 11, in two bursts 0.8 s apart | +35, +61 | **DD** | no step |
| fast gesture in all directions | 371 in 4.8 s | 2,080, 2,607 | 85 | 41 |

What the measure shows: the driver gives **5 to 6 arrows per stroke** across the pad, **spurious arrows on the other axis** during a stroke, and **arrows started from a remainder** (the notch that gives two, the light touch). This is the "too sensitive everywhere" feel of the stock trackpad. The `bold7-trackpad` model (threshold 60 to 70, 200 ms pause, one axis at a time, remainder kept) gives about half as many, and none after a pause.

## Touch screen

Read-only readings (`getevent -p`, `/proc/bus/input/devices`, `dumpsys input`, `/sys`, the IDC folders), with `bold7-trackpad` running.

- **`s2716b_ts`**, `/dev/input/event3` (the number can change: `bold7-trackpad` looks it up by name), Synaptics DSX driver (`synaptics_dsx/touch_input`, I2C `0-0067`, `cap_touch_synaptics`, build `1686763`); bus 0, vendor 0, product 3, version `0x2003`; only property `INPUT_PROP_DIRECT`.
- **Type B multi-touch**: `ABS_MT_SLOT` 0 to 9 (10 contacts), `ABS_MT_TRACKING_ID` 0 to 65535, `ABS_MT_POSITION_X` and `_Y` **0 to 2492**, `ABS_MT_TOUCH_MAJOR` and `_MINOR` 0 to 126, no resolution and no pressure; keys `BTN_TOUCH` and `BTN_TOOL_FINGER`.
- **Android**: `TOUCH | TOUCH_MT`, `DIRECT` mode, internal viewport of display 0; scale **0.2888** (720 / 2493: one pixel is 3.46 raw units); `OrientationAware`, rotation 0. **No IDC** (`/system/usr/idc` has neither `s2716b_ts.idc` nor `Vendor_0000_Product_0003.idc`, and `/vendor/usr/idc`, `/odm/usr/idc`, `/product/usr/idc` do not exist); default calibration (`touch.size.calibration` `GEOMETRIC`). The device fingerprint depends only on vendor and product (product non-zero): a twin with the same identifiers and the same location is merged by Android with the real one into a single device, like the keyboard twin (`Device 0: Q25_keyboard`, `EventHub Devices: [ 0 6 ]`).
- **Nothing to reject a touch**: the driver exposes only `buildid`, `flashprog`, `productinfo`, `reset` and `suspend` in `/sys`; no `/sys/board_properties` (virtual keys); `device_config list input_native_boot` is empty.
- The top of the trackpad sits just below the bottom of the screen: when pushing it upward, the thumb touches the last rows of pixels. The position of accidental contacts is not measured yet: `bold7-trackpad` logs each one it ignores.

## Hardware usable for immersion

- **RGB LED**: `/sys/class/leds/{red,green,blue}` (also `lcd-backlight` and `vibrator`), readable only as root from the shell. BenOS ships a `Q25Led` priv-app that very likely deals with them. Coloured LED notifications per account are therefore feasible, preferably through the normal notification path (`setLights`). Whether `Q25Led` relays that path to the LEDs remains to be checked before touching sysfs.
- Other BenOS priv-apps specific to the Q25, to leave alone: `Q25Led`, `Q25TrackpadCustomizer`, `Q25-Res-Changer`.
- System sounds: `/product/media/audio/{alarms,notifications,ringtones,ui}`; defaults `ro.config.ringtone=BeatPlucker.ogg`, `ro.config.notification_sound=bbpro6.m4a.mp3`, `ro.config.alarm_alert=Cesium.ogg`. Settings as read: `ringtone` Beat Plucker, `notification_sound` Pixie Dust, `alarm_alert` Cesium (`content://media/internal/audio/media/<id>?title=…&canonical=1`). BenOS also stores 55 BB10 sounds there as MP3 (`<sound>.m4a.mp3`, BB10 titles), ringtones in `ringtones/` and reminders in `alarms/`; the same sounds as the BB10 firmware. No mount on the stock `/product/media`, and no `audio_assets.xml`: `AudioService` takes its keyboard sounds from `ui/` under their default names. `/system/media` does not exist.
- Boot animation: `/product/media/bootanimation.zip` (2.6 MB, `720 720 24`, 4 parts, 252 full 720x720 frames). It is the only one bootanimation finds: no `/apex/com.android.bootanimation`, no `/oem/media`, no `/system/media`, and `ro.boot.theme` empty (no dark variant). `bootanim` is a `disabled oneshot` service of class `core` started by SurfaceFlinger; `class_start core` is in `on boot`, which follows `post-fs-data` (`/system/etc/init/hw/init.rc`): the module's mounts are done before. On the boot measured: SurfaceFlinger at 9.15 s, bootanim at 10.07 s, `boot_progress_enable_screen` at 25.79 s, `sf_stop_bootanim` at 30.88 s (BenOS's `c` parts played to the end).
- Fonts: `/system/etc/fonts.xml`.
- No on-screen navigation bar.
