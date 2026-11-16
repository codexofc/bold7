# Launcher performance

## The criterion

After the baseline profile and the early rejection of touches outside the band, **the launcher's background CPU must drop below 50 ms per 30 s of scrolling in another app**, and **the p95 of home screen swipes below that of Launcher3** (18 to 21 ms, `docs/BASELINE.md`). Otherwise, the next step is to move the input path to native code (Rust preferably), backed by the measurement that justifies it: the share of the remaining CPU that falls in Java execution (`libart.so`, JNI) rather than in the kernel. The final budget stays 20 ms and 15 context switches; 50 ms is the threshold for this step.

## Measures before this work

Sections 2 to 5, and the measures just below, concern an earlier BB10-style version of the launcher: it watched the bottom edge of the screen for gestures through an input monitor, and its home screen had pages with Active Frames. The current launcher has no gestures and opens no input monitor. The findings are kept because they describe how Android 14 behaves.

On the Q25, Bold7 launcher in release, priv-app mounted by the module.

| Measure | Value |
|---|---|
| Compilation (`dumpsys package dexopt`) | `status=run-from-apk`: nothing compiled |
| After `cmd package compile -m speed -f`, then `-m everything -f` | `speed-profile` in both cases, almost nothing compiled for lack of a profile |
| Background CPU, 30 s of scrolling in Settings | **730 to 760 ms** (budget 20; Launcher3 0, an earlier BB10-style launcher 200) |
| Context switches, same 30 s | 1,100 |
| Wakeups of the launcher's surface thread | **817 in 16 scrolls**, about 51 per scroll |
| `simpleperf`, launcher process during the scroll | 48% `libart.so` (interpreter: `NterpGetMethod`, `nterp_*`, `DoGetCalleeSaveMethodCallerAndDexPc`, `ResolveFieldWithAccessChecks`), 29% kernel (`__wake_up_sync_key`, `__wake_up`, `binder_transaction`), 5% libc, 4% `libandroid_runtime`, 2% `libinput`, 2% Bold7's compiled code, 2% JIT |
| Home screen, after the partial compilation | swipes p95 20 ms, p90 13 ms, 1.3% late frames (Launcher3: p95 18 to 21 ms, 2 to 2.8%); idle 0 frames; tap to `START` about 119 ms; PSS 46 MB |

How to read it:
- the `InputMonitor` receives **every touch on the screen**, in every app: the gesture monitor is a *spy window* covering the whole screen (`GestureMonitorSpyWindow`, below);
- for each event received, the launcher built a `Touch` (and one per historical position), a `GestureEvent`, went through the session queue, the statistics and the reducer, which answered "nothing";
- the receiver is a `BatchedInputEventReceiver`: each consumed batch of moves re-arms a `Choreographer` callback at the next vsync (`doConsumeBatchedInput` calls `scheduleBatchedInput` after each batch). A vsync request is a binder transaction to SurfaceFlinger (`requestNextVsync`): hence `binder_transaction` in the kernel, and about two wakeups per frame during a scroll (the input socket, then the vsync), which matches the 51 wakeups per scroll;
- all this code ran interpreted: a `speed-profile` without a profile compiles almost nothing, and ART forces `speed-profile` on the holder of the HOME role, whatever filter is requested (below). Hence the 48% in the interpreter.

## 1. The baseline profile, delivered to the system

### What AOSP 14 does

Read in ART Service, `android14-release` branch of `platform/art`:

- `libartservice/.../Dexopter.java`, `adjustCompilerFilter`: if the package holds the HOME role (`Utils.isLauncherPackage`, `RoleManager.ROLE_HOME`), the filter is **always `speed-profile`**, even for `cmd package compile -m speed` or `-m everything`. That is what the measures above show. Without a profile, `dexopt()` lowers this filter to `verify`; the profile is therefore the only way to compiled code for a launcher.
- `Dexopter.dexopt` → `Utils.getOrInitReferenceProfile`: ART takes the existing reference profile (`/data/misc/profiles/ref/<package>/primary.prof`) if it matches the dex (`artd.isProfileUsable`), otherwise it initialises it from the **external profiles**, in the order given by `PrimaryDexUtils.getExternalProfiles`:
  1. the preinstalled profile, `PrebuiltProfilePath`, which `artd/path_utils.cc` (`BuildPrebuiltProfilePath`) places at **`<APK path>.prof`**;
  2. the metadata file, `DexMetadataPath`, i.e. `<APK without extension>.dm` (`BuildDexMetadataPath`), a zip archive from which ART reads the `primary.prof` entry (`ProfileCompilationInfo::kDexMetadataProfileEntry`).
- `artd.cc`, `copyAndRewriteProfile`: `profman --copy-and-update-profile-key` copies this profile into the reference profile, rewriting the key of each dex to the real location of the APK. It returns `kCopyAndUpdateNoMatch` (code 21) if the profile's checksums are not those of the APK's dex files: the profile is then ignored.
- `libprofile/profile/profile_compilation_info.cc`: ART 14 only reads **its own profile formats, `015` and `016`** ("Profile version mismatch" otherwise).

The profile AGP stores in the APK (`assets/dexopt/baseline.prof`) is in format **`010`** (AGP 9.5, `CompileArtProfileTask`: `ArtProfileSerializer.V0_1_0_P`), with a metadata file `baseline.profm`: it is meant for the ProfileInstaller library, which transcribes it on the device. ART does not read it as is, and under Android 14 it never looks inside the APK on its own.

### What Bold7 does

- `apps/launcher/src/main/baseline-prof.txt`: all of `dev/bold7/**`, plus Kotlin and kotlinx.coroutines, classes and methods. The launcher's dex is small (302 classes) and each of its paths is hot at one time or another (touch reception, reducers, home views, springs, `StateFlow` collection); after R8, the profile covers 957 methods and 299 classes, practically the whole dex. A profile recorded on the Q25 (Macrobenchmark) would only prune, useful only if the size of the odex ever mattered.
- `system/module/build.gradle.kts`, task `launcherProfile`: reads `baseline.prof` and `baseline.profm` **from the signed APK**, merges them and rewrites them in format `015` with profgen, AGP's own library (`ArtProfile.addMetadata`, `ArtProfileSerializer.V0_1_5_S`), then the module zip places it at `layer/system/priv-app/Bold7Launcher/Bold7Launcher.apk.prof`, next to the APK. The `.prof` is preferred over the `.dm`: ART reads it first, it is a raw file, without zip, and the package manager does not mistake it for anything (it only reads the `.apk` files of the folder, and validates `.dm` files). Checked on the build: header `pro\0` `015\0`, 957 methods, dex checksum equal to the CRC of `classes.dex` in the APK.
- The compilation itself. Android only compiles a priv-app in `/system` at first boot and after an OTA; an installation or an update of the module leaves the launcher interpreted until ART's daily pass (phone idle and charging). `customize.sh` therefore sets a `compile` flag in the module, and `boot-completed.sh` runs `compile.sh`: flag cleared first (a failure is not retried at every boot), then `cmd package compile -m speed-profile -f dev.bold7.launcher` **only if** `cmd package art dump` does not already show `speed-profile`, `speed` or `everything`. Nothing at the following boots, no periodic work. `boot-completed.sh` rather than `service.sh`: at the `late_start` stage, the package manager does not reliably answer yet.

The decision logic is tested in `system/module/test-compile.sh`.

One limit: a reference profile already present and valid for the **same** dex takes precedence over the `.prof`. That is the case when a version already installed is recompiled by hand; `cmd package compile --reset dev.bold7.launcher` clears it (`ArtManagerLocal.resetDexoptStatus`). Every new build changes the dex, hence the checksum, and ART starts again from the `.prof`.

## 2. Early rejection of touches outside the band

In `GestureInput.kt` of `core/platform` (since removed with the gestures), `SystemGestureInput`:
- a `StreamGate` decides **once per stream, at `DOWN`**: the session answers (`GestureSession.wantsStream`); a refused stream is handed back to the system event by event through `finishInputEvent`, without building anything, until the next `DOWN`;
- the session only refuses what the reducer would have ignored event by event: a `DOWN` outside the bottom-edge band (40 px), or home in the foreground, **while the session is idle**. During a gesture (released, animating), every stream goes through as before;
- on a refused stream, **vsync batching is turned off** (`setBatchingEnabled(false)`): no more re-armed `Choreographer` callback, no more vsync request, hence no more binder transaction to SurfaceFlinger, and a single wakeup per event (the socket) instead of two. Batching comes back with the next admitted `DOWN`: a gesture runs exactly as before.

What remains per event of a refused stream: the looper wakeup on the input socket, the JNI call `dispatchInputEvent` with a `MotionEvent` taken from the framework's pool, two tests, `finishInputEvent`. No allocation in Bold7's code (the filter is a `fun interface` with a `Float` parameter, no boxing), no coroutine, no `StateFlow` emission, no log. `homeOnTop` is only read at `DOWN`.

JVM tests (`GestureSessionTest`), touches going through the same `StreamGate` as on the phone: a scroll starting from the middle of the screen never reaches the session (no event forwarded, no transition, no pointer theft, no frame requested, no phase published, no log); neither does a stream from the band with home in the foreground; the gate reopens at the next `DOWN` in the band; a stream outside the band during the release animation still reaches the session. The existing tests all go through the gate and stay green.

Expected, to be measured: the CPU per refused event becomes that of the framework (already compiled in the boot image) and of the kernel, and wakeups drop to one per touch event. The report rate of the touch controller (`s2716b_ts`) is unknown: at 60 Hz, about 25 wakeups per scroll instead of 51; if it is higher, as many wakeups as events. That is the floor of this approach: **as long as the monitor covers the whole screen, every touch in every app wakes the launcher.** If this floor exceeds 50 ms per 30 s, the next approach is required.

### Measured on the Q25 after the profile and the early rejection

| Measure | Before | After |
|---|---|---|
| Compilation | `run-from-apk` | `speed-profile`, file under `/data/dalvik-cache`; `compile: … speed-profile: Success` at first boot; `test-compile.sh` 7 cases, `test-guard.sh` 5 cases |
| Background CPU, 30 s of scrolling in Settings (two passes) | 730 to 760 ms | **200 to 220 ms** |
| Context switches | 1,100 | 511 to 538 |
| Wakeups of the surface thread in 16 scrolls | 817 | **404**, one per touch |
| `simpleperf` samples | 1,445 | 512: kernel 37% (`__wake_up_sync_key` 11%), `libart` 29% (`InvokeVirtualOrInterfaceWithVarArgs`, `NterpCommonInvoke*`, `CodeInfo::DecodeInlineInfoOnly`: the JNI crossing to `onInputEvent`), `libinput` 9% (`InputConsumer::consume`), libc 8%, `libandroid_runtime` 4.5%, `libutils` 4.4% |
| Home screen | | tap to `START` about 115 ms, idle 0 frames, PSS 48 to 57 MB; swipes p95 25 to 27 ms (20 in the previous session with no change to the home code: measurement variance, handled separately) |

**The criterion is not met at this step** (200 ms against less than 50). What remains is the delivery itself: wakeup, socket, `InputConsumer`, JNI crossing. Native code would only remove part of the ART share (§4); it is the number of events received that must go, hence §3, done right after.

## 3. Receiving only the band

### What the monitor is under Android 14

`InputManager.monitorGestureInput` creates on the system side a `GestureMonitorSpyWindow` (`frameworks/base`, `services/core/java/com/android/server/input/GestureMonitorSpyWindow.java`, `android14-release` branch): an input window without content, `SPY | TRUSTED_OVERLAY | NOT_FOCUSABLE`, layer `Integer.MAX_VALUE`, whose touchable region is **replaced by the bounds of its own surface** (`replaceTouchableRegionWithCrop(null)`), a surface set to full screen (`setCrop(null)`). The `InputDispatcher` chooses the spy windows of a pointer **at its `DOWN`**, by their touchable region, then sends them the whole stream of that pointer, moves outside the region included; a spy window takes nothing from apps as long as it does not call `pilferPointers`.

### Option A: a thin capture window

A real window 16 to 40 px high at the bottom edge, which receives the band's touches, then starts the gesture (or takes a monitor) on a `DOWN` in the band.

- **What it gains**: no more wakeups outside the band. The background cost of scrolling in another app drops to zero, like Launcher3 on this phone (which has no monitor, as gesture navigation is off).
- **What it costs apps**: an ordinary window **consumes** the touches in its area. On the Q25, with no on-screen navigation bar (`docs/DEVICE.md`), apps draw down to the edge: 40 px is 5.6% of the 720 px height, and the band takes the last row of Settings, the buttons at the bottom of dialogs, and the suggestion bar of the BlackBerry keyboard, placed at the bottom of the screen when the physical keyboard is in use. A tap in the band no longer reaches the app (an earlier BB10-style launcher has this defect with a 16 px band). Giving it back would require reinjecting the event (`INJECT_EVENTS`, signature, out of reach) or declaring the band as an inset (`providedInsets`, reserved for system windows): the app then shrinks by 40 px, which has nothing BB10 about it.
- **How to do it cleanly on Android 14** (`android14-release` sources): a system type (`TYPE_NAVIGATION_BAR_PANEL`, `TYPE_SYSTEM_ERROR`…) or `PRIVATE_FLAG_TRUSTED_OVERLAY` require `INTERNAL_SYSTEM_WINDOW`, `signature|module`, out of reach of Bold7's key. That leaves `TYPE_APPLICATION_OVERLAY` (`SYSTEM_ALERT_WINDOW`, an appop grantable to a priv-app), an untrusted overlay penalised by the touch occlusion of Android 12 and later, except with `PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY`: `WindowState.shouldWindowHandleBeTrusted` then treats it as trusted if the app holds `SYSTEM_APPLICATION_OVERLAY`, `signature|recents|role|installer`, which the recents provider can obtain.

This last path opens a **variant A': a spy window of the launcher's own**, which consumes nothing. `INPUT_FEATURE_SPY` requires `MONITOR_INPUT` (`WindowManagerService.sanitizeSpyWindow`), which the launcher has; the `InputDispatcher` requires a spy window to be trusted, which `SYSTEM_APPLICATION_OVERLAY` provides. A window the size of the band (or full screen with a U-shaped touchable region for the bottom and the sides), and `pilferPointers` through its token when the gesture starts. It costs one more permission in the whitelist (and perhaps the `SYSTEM_ALERT_WINDOW` appop, to be checked), a window and its `ViewRootImpl` on the surface thread: more code than option B, for the same result.

### Option B, chosen and done: cropping the monitor's own surface

The `InputMonitor` gives the client the surface of its spy window (`InputMonitor.getSurface()`). Since its touchable region follows the bounds of this surface, a single transaction shrinks it to the band, when the monitor is opened (`SystemGestureInput.open`):

```kotlin
SurfaceControl.Transaction().use { it.setCrop(monitor.surface, band).apply() }
```

Why it holds, read in AOSP 14 (`frameworks/native`, `android14-release`):
- **the touchable region follows the crop.** With `replaceTouchableRegionWithCrop` and no crop layer, SurfaceFlinger takes as region the input bounds of the layer itself (`Layer::fillInputInfo` in the old front end, `LayerSnapshotBuilder::updateInput` in the new one), and for a layer without a buffer these bounds are its crop (`getCroppedBufferSize` returns the crop when there is no buffer; `getInputBounds` only falls back to the parent's bounds when there is no crop);
- **SurfaceFlinger accepts a crop from another process.** `setClientStateLocked` finds the layer by its handle without checking who created it, and `layer_state_t::sanitize` (`libs/gui/LayerState.cpp`) only strips, without permission, non-rectangular matrices, display decoration, input information and a few other fields, not `eCropChanged`. The touchable region cannot be written directly (`eInputInfoChanged` requires `ACCESS_SURFACE_FLINGER`), but the crop leads to it;
- **the system does not set it again.** `GestureMonitorSpyWindow` only applies its transaction at creation and removal;
- **a finger leaving the band keeps its stream.** The `InputDispatcher` (`services/inputflinger/dispatcher/InputDispatcher.cpp`) only looks for spy windows at the start of a gesture (`findTouchedSpyWindowsAtLocked`, on `DOWN`); afterwards each move goes to every window of the touch state holding the pointer, wherever it is. Only a slippery foreground window (`isSlippery`) can lose a pointer on the way, which a spy is not. No need to widen the region during a gesture.

**The coordinate trap.** Once cropped, the spy window carries a translation (`dumpsys input`: transform `ROT_0 TRANSLATE`, frame `[0,680][720,720]`), and the `InputDispatcher` delivers its events in window coordinates: a finger placed at y=718 arrived at y=38, which `startsOnEdge` refused, and no gesture started any more on the first try. `SystemGestureInput` therefore brings each event back to screen coordinates (`offsetLocation(rawX - x, rawY - y)`, history included) before the filter.

The band runs from row `edgeBandTop(height)` to the bottom of the screen, over the full width. The `InputDispatcher` tests a point by its integer pixel: `edgeBandTop` is the floor of `height - EDGE_PX`, so that every `DOWN` that `startsOnEdge` accepts falls in the band and no other does (`GestureMathTest`, which fails if the limit moves by one pixel). The band height stays the one of the original quickstep integration, 40 px. The early rejection of §2 stays behind it, as a safety net (home in the foreground, or a system that would not honour the crop).

- **Gain**: that of option A, no more wakeups outside the band; the `InputDispatcher` no longer sends anything to the launcher for a `DOWN` elsewhere.
- **Cost for apps: none.** The window stays a spy: a tap in the band reaches the app as before, the gesture only takes the pointers beyond the touch slop (`pilferPointers`, unchanged). No inset, no new window, no new permission, no `TRUSTED_OVERLAY` to obtain: the system has already set it.
- **Proven on the Q25**: `dumpsys input` shows the launcher's monitor with `touchableRegion=[0,680][720,720]`; BenOS follows AOSP on these paths, variant A' was not needed.
- **The other edges**: a crop is a single rectangle. BB10 gestures start from the bottom (minimise, Peek), from the top (quick settings or the app menu) and from the left side on the home screen (Hub) (an earlier BB10-style launcher used a 12 dp bottom band and the top third of the left edge). That would need one monitor per edge, each cropped to its band: one more channel per edge, silent as long as no `DOWN` falls in it.

The list of what stays Android (the inside of third-party apps, the deep pages of Settings, rare dialogs) does not mention gestures. What it implies here: the inside of third-party apps keeps its behaviour. A band that steals their touches would break it; on the BlackBerry Classic, the reference device of that version, gestures start from the bottom of the screen without taking anything away from apps. Option B respects both: BB10 fidelity (gestures from the bottom and the sides) and apps left intact.

**Decision**: the profile and the early rejection were not enough (210 ms), so option B, the crop, was chosen and done. If an OTA broke it (region back to full screen), variant A'; never a window that consumes the band's touches.

### Measured on the Q25 after the crop

| Measure | Before this work | Profile and early rejection | Crop |
|---|---|---|---|
| Launcher background CPU, Settings scroll outside the band | 740 ms per 30 s | 210 ms per 30 s | **0 ms** (20 swipes) |
| Context switches | 1,100 | 511 to 538 | **0** |
| Touchable region of the launcher's monitor | full screen | full screen | `[0,680][720,720]` |
| Gesture to home | | | contact to start 51.7 ms, 0 missed vsync |
| Short release | | | back to the app, 0 missed vsync |

**The CPU criterion is met**: under 50 ms, and even the final budget (20 ms, 15 context switches) for scrolling another app. Native code no longer has a purpose on this path. The p95 of home swipes is a separate matter (25 to 27 ms at the last measure, 20 the time before with no change to the home code): it does not depend on the monitor.

## 4. The input path in native code: possible, but aimed at the wrong cost

- **The monitor's channel is not in the public NDK.** `InputChannel` is a hidden Java class wrapping an `android::InputChannel` from `libinput` (a socket pair). The NDK only offers `AInputQueue` (for `NativeActivity`) and `AMotionEvent_fromJava` (API 31), which starts from a `MotionEvent` already delivered in Java: neither reads an arbitrary channel.
- **What a minimal JNI layer would cost.** Getting the socket descriptor (through `InputChannel.writeToParcel`, whose format is hidden), then, in Rust, reading the `InputMessage`s and sending back `FINISHED` messages with their sequence number, that is, reimplementing the private protocol of `InputConsumer`: a few hundred lines, tied to a binary structure that changes from one Android version to the next (AOSP pins it with `static_assert`s, not with an API). Loading `libinput.so` directly would be shorter, but it is a private platform library: whether the namespace of bundled `/system` apps allows it on the Q25 is unverified, and it is still tied to the same private ABI. Pointer theft and the recents transition stay in Java and binder anyway.
- **What it would gain.** A native consumer wakes up **as often** as a Java consumer: the `InputDispatcher` sends the same number of messages, and each must be acknowledged. Native code only removes the JNI crossing and the Java execution per event, which is precisely what the baseline profile moves from the interpreter to compiled code. The wakeup, the socket and the scheduler (the 29% in the kernel) remain.

So, under the rule that reopening the Kotlin-or-native decision requires a measurement on the device: the native proposal was only justified if the remaining CPU fell mostly in `libart.so` and `libandroid_runtime.so`. After the early rejection, the measurement showed the opposite (kernel 37%, `libinput` 9%, the ART share being mostly the JNI crossing); after the crop, there is nothing left to address. No native code.

## Checked on the Q25

- **Module of this branch** installed by `tools/install.sh` (which first removes the launcher update placed in `/data` by `adb install -r`, otherwise it hides the priv-app at an equal `versionCode`), then reboot: mounts done, launcher started from its folder under `/system/priv-app`, `compile: … speed-profile: Success` in the module log, `dumpsys package dexopt` at `speed-profile`. `test-compile.sh`: 9 cases out of 9. Removal is checked at the end of the session with `tools/uninstall.sh`.
- **Gestures** after reboot: to home (contact to start 19.9 ms, 0 missed vsync), short release (back to the app, 0 missed vsync), tap in the band (Settings opens the touched subpage: the touch still reaches the app).
- **`perf.sh`, two passes** (Calendar icon on page 1):

| Measure | Pass 1 | Pass 2 | Launcher3 | Budget |
|---|---|---|---|---|
| Home swipes, late | 1.81% | 1.66% | 2 to 2.8% | |
| Swipe p95 | 28 ms | 26 ms | 18 to 21 ms | not met |
| Idle 10 s | 0 frames | 0 frames | 0 | met |
| Tap to START | 87 to 125 ms | 118 to 144 ms | 125 ms | 120, borderline |
| Tap to first frame of Calendar | 866 to 1,007 ms | 904 to 979 ms | | 750, not met |
| Background CPU, 30 s | 0 ms, 0 context switches | 0 ms, 0 | 0 | met |
| PSS | 45 MB | 49 MB | 108 to 124 MB | met |

Outside the scope of this work: the swipe p95 and an app's first frame, which do not depend on the monitor.

## 5. p95 of home screen swipes

Before this work, `gfxinfo` gave for page swipes a p95 of 28 to 29 ms and 2.5 to 2.9% late frames, against 18 to 21 ms and 2 to 2.8% for Launcher3 (budget: 16.6 ms and under 1%).

### Method

Two Perfetto traces taken on the Q25 under the same conditions, home in the foreground: the stock Launcher3, then Bold7 compiled with `speed-profile`. Twelve round trips `input swipe 600 360 120 360 200` then `120 360 600 360 200`, 0.6 s between each (the loop of `tools/perf.sh`). Configuration: SurfaceFlinger frametimeline, `sched_switch` and `sched_wakeup`, CPU frequencies and idle, atrace `gfx`, `view`, `input`, `am`, `dalvik`, `binder_driver` for both packages. `gfxinfo` on the Bold7 pass: 796 frames, 2.51% late, p95 28 ms, p99 32 ms, GPU p95 12 ms.

Read with `trace_processor_shell`, frame by frame: each `Choreographer#doFrame <vsync>` of the main thread is linked to its `actual_frame_timeline_slice` row by the vsync number, to the RenderThread frame that starts during its `postAndWait` (the parent of `syncFrameState`), and to the `waiting for GPU completion` wait that follows; added to that are the scheduling states of both threads over these intervals (`thread_state`) and the frequency of each CPU at the start of the frame. The view names in the `HWUI:` slices are R8's, translated through `apps/launcher/build/outputs/mapping/release/mapping.txt`.

### What the traces show

| | Bold7 | Launcher3 |
|---|---|---|
| Frametimeline frames | 675 | 447 |
| of which "Buffer Stuffing" (one vsync late, queue full) | **543 (80%)** | 139 (31%) |
| of which "App Deadline Missed" | 17, **one at the start of each swipe** | 12 |
| Actual frame duration (frametimeline), p50 / p90 / p95 | 9.7 / 24.0 / 27.9 ms | 10.4 / 20.1 / 33.7 ms |
| `doFrame`, p50 / p95 | 2.8 / 12.0 ms | 3.3 / 9.7 ms |
| RenderThread frame, p50 / p95 | 7.4 / 16.2 ms | 5.6 / 14.7 ms |
| of which `Drawing` (display list replay), p50 | 5.7 ms | 5.6 ms |
| `dequeueBuffer`, p50 / p90 | 0.3 / 5.9 ms | 0.4 / 0.7 ms |
| `ged-swd` "Wait PreFence" (the GPU waits for composition to return the buffer), mean | **3.4 ms** | 1.1 ms |
| `ged-swd` "Wait 3D Fence" (the GPU's own work), mean | 0.9 ms | 1.0 ms |
| SurfaceFlinger GPU composition | 21 frames out of 686 | 11 out of 772 |

Not at fault: GPU work per frame (identical), composition (HWC in both cases), garbage collection (a single GC, in the background on `HeapTaskDaemon`, 23 ms, nothing on the main thread), binder (no transaction on Bold7's main thread, 43 for Launcher3), scheduling (mean `Runnable` time 0.07 ms for the main thread and 0.10 ms for the RenderThread: they get a core as soon as they ask), allocations (no visible slice). Nor the springs: an ordinary scroll frame costs as much as in Launcher3.

The 17 late frames, all at the first `MOVE` of a swipe (one every 880 ms or so):

| | First frame of a swipe | Ordinary frame |
|---|---|---|
| `doFrame` | 12 to 22 ms (17.7 on average) | 2.8 ms |
| of which `Record View#draw()` | **5 to 12 ms** (8.2): `HWUI:s5` = `GridPage` (8.3 ms) or `HWUI:t3` = `FramesView` (5.9 ms) | 0.3 ms |
| RenderThread `Drawing` | 11 to 12 ms | 5.7 ms |
| CPU at the start of the frame | little cores at 915 MHz, big at 734 MHz | 1,194 and 828 MHz |

The next frame waits for the still busy RenderThread (`postAndWait` 9 to 11 ms, `doFrame` 12 to 14 ms); the buffer arrives one vsync too late, and from then on **the queue stays full until the end of the spring**: Bold7 draws one frame per vsync for about 500 ms, never skipping one that would drain the queue. Each frame of that half second waits for a free buffer (`dequeueBuffer` 5 to 12 ms) and then for composition to release that buffer before the GPU starts ("Wait PreFence"). `gfxinfo` counts this wait in the frame duration: hence frames of 18 to 32 ms that cost no more than elsewhere, and the 28 ms p95. The 12 ms GPU p95 of `gfxinfo` is this same wait, not work.

### The cause

`PagesView.place()` made pages more than one screen away `INVISIBLE` and those coming in `VISIBLE`. Under Android 14, `View.setFlags` **discards the display list** of a view that becomes invisible (`cleanupDraw` then `resetDisplayList`) and fully invalidates the one that becomes visible again (`invalidate(true)`), as well as its parent. At the first `MOVE` of each swipe, the incoming page (the grid or the Active Frames, alternately in this loop) was therefore re-recorded in full, its fifteen `TextView`s with their icon and text shadow, then replayed by a RenderThread with cold caches, all on CPUs still at their lowest frequency after 0.6 s of idle. Launcher3 does not hide its pages: its `Workspace` leaves them visible and clipping does the rest.

### The fix

One line in `PagesView.place()`: an off-screen page gets `alpha = 0` instead of `INVISIBLE`. A transparent view keeps its display list; HWUI does not replay it (`RenderNode::nothingToDraw` is true at zero alpha, `RenderNodeDrawable::forceDraw` stops there); changing the alpha only touches a property of the `RenderNode` (`invalidateViewProperty`), without re-recording the page or its parent. Nothing else changes: a transparent page is not "visible to the user" (`View.isVisibleToUser` tests alpha as well as visibility), so neither accessibility nor the `uiautomator dump` of `tools/perf.sh` sees it, and it receives no touch since it is offset at least one page outside `PagesView`. The springs and the page architecture stay as they are; the fix also applies to the vertical blocks of the Active Frames.

What it costs: all pages are recorded at the first frame after the process starts (or after a rebuild of the grid, when the app list changes), instead of page by page on first pass; a few more display lists in memory.

Expected from the measurement: no more "App Deadline Missed" frame at the start of swipes (recording at 0.3 ms like an ordinary frame, `doFrame` of 7 to 9 ms instead of 12 to 22 despite CPUs at their lowest), hence almost no more "Buffer Stuffing"; `gfxinfo` under 1% late frames and a p95 between 17 and 20 ms, at Launcher3's level or better. If the p95 stays above, the next lead is the cost of the first `MOVE` itself (`input` of 6 to 7 ms at low frequency, in Launcher3 too).

### Redoing the measurement

From the repository root, home in the foreground, screen unlocked. The configuration:

```bash
mkdir -p build/perf/p95
cat > build/perf/p95/config.pbtx <<'PBTX'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" target_buffer: 0 process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "power/cpu_idle"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "input"
  atrace_categories: "am"
  atrace_categories: "dalvik"
  atrace_categories: "binder_driver"
  atrace_apps: "com.android.launcher3"
  atrace_apps: "dev.bold7.launcher"
} } }
duration_ms: 20000
PBTX
```

The Bold7 trace, started in the background with the configuration on standard input, then the swipes and `gfxinfo` over the same pass:

```bash
adb shell dumpsys gfxinfo dev.bold7.launcher reset >/dev/null
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/bold7.pftrace --background < build/perf/p95/config.pbtx
adb shell 'for i in $(seq 12); do input swipe 600 360 120 360 200; sleep 0.6; input swipe 120 360 600 360 200; sleep 0.6; done'
adb shell dumpsys gfxinfo dev.bold7.launcher > build/perf/p95/bold7-gfxinfo.txt
sleep 2   # the trace lasts 20 s
adb pull /data/misc/perfetto-traces/bold7.pftrace build/perf/p95/
```

The reference, with Launcher3 holding HOME, the same way:

```bash
adb shell dumpsys gfxinfo com.android.launcher3 reset >/dev/null
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/launcher3.pftrace --background < build/perf/p95/config.pbtx
adb shell 'for i in $(seq 12); do input swipe 600 360 120 360 200; sleep 0.6; input swipe 120 360 600 360 200; sleep 0.6; done'
adb shell dumpsys gfxinfo com.android.launcher3 > build/perf/p95/launcher3-gfxinfo.txt
sleep 2
adb pull /data/misc/perfetto-traces/launcher3.pftrace build/perf/p95/
```

The first two things to read in the Bold7 trace: the share of "Buffer Stuffing" and "App Deadline Missed" in the process's `actual_frame_timeline_slice`, and the duration of `Record View#draw()` in the first `Choreographer#doFrame` of each swipe.

### After the grid and home screen fidelity: the p95 rises again

The fix above gave 1.95% late frames and a p95 of 22 to 24 ms. After merging the persistent grid work (persistent grid, edit mode, folders) and the home screen fidelity work (labels on two lines with a halo, a transparent bottom bar drawn over the pages, an image-based indicator, Active Frames), `perf.sh` over two passes gives 2.5 to 2.7% late frames, a p95 of 26 to 27 ms and a PSS of 68 MB (55 to 58 before).

Three traces taken as above, read frame by frame in the same way: one before the first fix, one of `main` at abd33ec (after the grid), and `launcher3.pftrace`.

| | Before the 1st fix | `main` after the grid | Launcher3 |
|---|---|---|---|
| Frames in the frametimeline | 675 | 681 | 447 |
| of which "Buffer Stuffing" / "App Deadline Missed" | 543 / 17 | 548 / 16 | 139 / 12 |
| `doFrame`, p50 / p95 | 2.8 / 12.0 ms | 2.8 / 8.6 ms | 3.3 / 9.7 ms |
| `Record View#draw()`, p99 | 8.7 ms | **0.5 ms** | 3.0 ms |
| RenderThread frame, p50 / p95 | 7.4 / 16.2 ms | **9.2 / 17.7 ms** | 5.6 / 14.7 ms |
| of which `Drawing`, mean | 5.8 ms | **7.0 ms** | 5.9 ms |
| of which `renderFrameImpl` (Skia replay), mean / p50 / p95 | 0.99 / 0.88 / 2.0 ms | **2.11 / 1.73 / 4.26 ms** | 0.35 / 0.28 / 0.76 ms |
| of which `eglSwapBuffersWithDamageKHR` and `flush commands`, means | 3.3 and 0.9 ms | 3.2 and 1.0 ms | 3.2 and 0.9 ms |
| `syncFrameState` / `prepareTree`, means | 0.40 / 0.31 ms | 0.64 / 0.47 ms | 0.40 / 0.33 ms |
| HWUI memory (`HWUI All Memory`), peak | 3.0 MB | 7.5 MB | 9.4 MB, 7 of it purgeable |

The first fix holds: no more re-recording on the first `MOVE` (`Record View#draw()` at 0.4 ms in the "App Deadline Missed" frames, 8.2 ms before; their `doFrame` drops from 17.5 to 9.0 ms). The main thread even costs less than before. The whole gap is on the RenderThread, and almost entirely in `renderFrameImpl`, Skia's replay of the display lists: **+1.1 ms per frame on average**, twice as much as before and six times Launcher3. The rest of `Drawing` (buffer swap, submission to the GPU) does not move, nor does the wait for the GPU (`waiting for GPU completion`, p50 0.8 then 0.5 ms). The 16 "App Deadline Missed" frames are still on the first `MOVE` of each swipe, but it is now the RenderThread that makes them miss: 17.1 ms on average (14.4 before), `Drawing` 13.6 ms, on CPUs at their lowest frequency.

This cost follows the grid. In every other swipe, `renderFrameImpl` rises from 1 ms to 2.5 to 4.5 ms as the grid enters the screen, and in the other it falls as the grid leaves (means 2.5 and 1.8 ms depending on direction; 1.05 and 0.95 before the grid). The Active Frames page, four labels without a halo, stays around 1 ms.

What the trace rules out:
- **the transparent bar**: since `HomeView` and `PagesView` no longer clip their children (`clipChildren = false`), HWUI no longer bounds the damage of a moved page, and the redrawn area goes from `0 40 720 612` to `0 40 720 720`, bar included, on every frame. But before the grid, the 83 frames that already redrew that full height cost as much as the others (`renderFrameImpl` 1.05 against 0.99 ms), and the GPU wait did not increase: redrawing the bar costs almost nothing;
- **the indicator redrawn on every frame**: on each frame the main thread only re-records the `DecorView`, content, home chain (0.21 ms in total); the indicator is not part of it;
- **the two-line `StaticLayout` and the heavier `TileView`s**: recorded once, nothing on the main thread;
- **the halo blur computed on every frame**: Skia stores the blurred mask of each glyph in its glyph cache, once.

### The cause

The pages move by `translationX`, in fractional pixels: the finger position, then the spring position. Skia only reuses a text already prepared for the GPU (its placed glyphs, its vertices) if the drawing has moved by a whole number of pixels since it was prepared (`TextBlob::canReuse`, `can_use_direct`); otherwise it prepares it again, on every frame of the swipe, for every visible label. The `setShadowLayer` halo draws each text a second time, with its own filter, so two preparations per line. The fidelity work multiplied this: full labels on two lines instead of one truncated line, a larger font (24 px), two draws per line. Hence the doubled `renderFrameImpl` when the grid is on screen. HWUI's "Misc" memory shows it too: it grows by about 6 KB per drawn frame for the whole trace (from 0.1 to 4.1 MB, nothing purgeable), against 0.7 KB before the grid.

Launcher3 does not have the problem: its `PagedView` scrolls by `scrollX`, an integer. Its labels, shadows included, are reused from one frame to the next, hence its 0.35 ms.

### The fix

In `PagesView.place()`, the spring and finger position stays fractional for the computation, but the translation applied to the pages is rounded to the pixel. Texts therefore move only by whole pixels from one frame to the next and Skia reuses what it prepared; icons also land on the pixel grid, with no bilinear filtering while moving. Nothing changes on screen: at 60 Hz, a spring that advances tens of pixels per frame has no use for half a pixel, and a page at rest was already on a whole pixel. The fix also covers the vertical blocks of the Active Frames, which go through the same function. The halo, the two lines, the transparent bar and the indicator stay as they are.

Expected from the measurement: `renderFrameImpl` back under 1 ms (p50 around 0.3 to 0.9 ms) and the RenderThread frame around 7 to 8 ms at p50, so 1.9% late frames or fewer and a p95 of 22 to 24 ms, possibly lower since the pre-grid version already paid this cost for one line of text. HWUI's "Misc" memory should no longer grow over the swipes, which should give back part of the PSS; the rest of the extra 10 to 13 MB (grid pages kept built, folder panel, `StaticLayout`) is not visible in this trace and is read with `dumpsys meminfo`. If the p95 stays above Launcher3, the remaining gap is the first frame of each swipe on CPUs at their lowest frequency (lead already noted above) and an `eglSwapBuffersWithDamageKHR` of 3.2 ms, identical in Launcher3.

## 6. The Hub account list: accessibility that wakes the trackpad

This section and the next concern an earlier BB10-style version of the launcher (the Hub and its account list, Peek, Active Frames); the findings on the framework, the trackpad service and the CPUs still apply. The gesture that opens and closes the account list met its criterion (no missed vsync) but not the budget: 3 to 3.7% late frames, p95 26 to 29 ms, then 1.99% and p95 24 ms once the Hub was composited as a hardware layer and the scrim moved into the layer's paint. A Perfetto trace of 20 slides (18 s, `gfx view input sched freq power/gpu_frequency`, read with `trace_processor` 49.0) gave the cause.

### What the trace shows
- **Drawing the Hub is not the cause.** The launcher's RenderThread redraws the layer (`drawLayer [FrameLayout] 720.0 x 680.0`) only 9 times in 433 frames; SurfaceFlinger composites the wallpaper on the GPU (`gles (Wallpaper BBQ wrapper)`), but its target is cached 417 times out of 444 (`RenderEngine` draws only 27 times), and the launcher goes through HWC. The GPU frequency is not in the trace: the Q25 kernel does not emit `power/gpu_frequency`.
- **The slow frames are those where the RenderThread runs on the little cores.** Over the 161 frames above 8 ms, it runs 785 ms on the little cores and 316 on the big ones; over the 272 others, 403 and 788. It also waits for its buffers (`dequeueBuffer` up to 11 ms, `eglMQuerySurface` up to 12 ms).
- **What keeps the big cores busy: the launcher's main thread, answering accessibility.** 1940 `AccessibilityInteractionController$PrivateHandler: #2` messages (`findAccessibilityNodeInfoByAccessibilityId`), 2.9 s of the main thread out of 18 s, about 110 per second during the gestures. They come from `tech.shroyer.q25trackpadcustomizer`, a BenOS accessibility service that switches the trackpad mode per app (`DEVICE.md`): its `AppSwitchService` runs 45 times, 9.2 s in total, and walks the launcher's view tree on each event. The events are the launcher's own: 85 `SendWindowContentChangedAccessibilityEvent`, in bursts of five, every 100 ms of each gesture, none at rest.

### The cause
`View.setTranslationX` calls `notifySubtreeAccessibilityStateChangedIfNeeded`, which posts a `TYPE_WINDOW_CONTENT_CHANGED` (once per 100 ms, `getSendRecurringAccessibilityEventsInterval`) as soon as an accessibility service is active; `setAlpha` does too, when alpha leaves or returns to 0. Checked on the Q25's `framework.jar` (`stubs/device/framework/framework.jar`, `dexdump -d`, class `android.view.View`): `setTranslationX` and `setAlphaInternal` call it, `setAnimationMatrix`, `setClipBounds` and `setLayerPaint` do not.

### The fix
The Hub slides by its animation matrix (`View.setAnimationMatrix`, a RenderNode property) and no longer by `translationX`; the panel is no longer hidden by its alpha but by its `clipBounds`, empty when the list is closed. The gesture therefore no longer notifies accessibility, nor the trackpad. The animation matrix is not part of touch hit-testing: no effect here, since `HubPage` keeps all touches while the list is open or moving.

### What remains
`PagesView` moves the home screen pages by `translationX`: each page swipe sends the same events and wakes the same service, which may explain swipes at 0.8 to 1.3% late frames. The same remedy there requires hand-written hit-testing during the spring (the animation matrix is not part of it): left to the home screen work, with a trace before and after.

### Second trace (`8c934b3`): the full queue, and the layer that primes it

Measurement of `8c934b3`: 2.22 and 2.68% late frames, p95 29 ms, GPU p95 18 ms; same result with the trackpad service briefly removed (2.15%, p95 28): that service is no longer the cause. Perfetto trace of 20 slides (`hub2.pftrace`, `gfx view input sched freq am wm`).

- **Accessibility no longer moves**: 2 `AccessibilityInteractionController` requests in 18 s (1940 before), 2 runs of the trackpad service (45 before).
- **The GPU does not work harder**: its own work (`ged-swd`, "Wait 3D Fence") is 0.9 ms on average (p95 1.5) in gestures without waiting and 1.4 ms (p95 3.2) in the others. The 17 to 20 ms "GPU" of `gfxinfo` is "Wait PreFence", the GPU waiting for composition to return the buffer: 8.0 ms on average (p95 16.5) in full-queue gestures, 2.0 (p95 7.2) in the others.
- **Two kinds of gestures, like the swipes of §5**: 8 gestures out of 17 never wait for a buffer; the other 9, opening or closing, wait from their 4th or 5th frame until the end of the spring (about 23 frames out of 28, `dequeueBuffer` 5.5 ms on average). The frametimeline classes 405 frames out of 478 as "Buffer Stuffing".
- **What primes it: the first frames of each gesture.** The main thread's first frame costs 8 to 11 ms (4 to 5 ms of it input), the RenderThread's 15 ms on closing (CPUs still cold after 0.8 s at rest: `eglSwapBuffersWithDamageKHR` 5.8 ms, `prepareTree` 1.4) and **20 to 22 ms on opening**, where it creates the Hub's hardware layer (`flush layers` 3.7 to 4.9 ms, `drawLayer` 1.6 to 2.6, `flush commands` 3.5 to 3.9, `Drawing` 13 to 18 ms instead of 4.7), 9 openings out of 9. The main thread's second frame then waits for it (`postAndWait` 8.7 ms, `doFrame` 13 to 16 ms).

The fix (next commit): no more layer. The Hub is replayed on every frame, as when its list scrolls ("hub scrolls", p95 13 ms); the scrim becomes a black view again, slid by the same animation matrix, whose `ColorDrawable` alpha is the only thing that changes (one re-recorded rectangle, nothing at 0; `ColorDrawable.setAlpha` and `View.invalidateDrawable` do not notify accessibility, checked in the Q25's `framework.jar`).

Expected: no more 20 ms frame on opening, so fewer full-queue gestures, as for closing. What remains, the first frame of a gesture on cold CPUs and the queue that stays full until the end of the animation, is the mechanism of §5, which Launcher3 also has on this phone (p95 18 to 21 ms, 2 to 2.8% on swipes): it is not due to drawing the Hub.

## 7. The gesture started cold

Starting measurements (`perf.sh` over two passes): swipes 0.49 to 1.40% late frames, p95 18 to 22 ms; Hub account list 2.15 to 3.13%, p95 28 to 29 ms; no missed vsync; Launcher3 on this phone 2 to 2.8%, p95 18 to 21. Budget: under 1%, p95 at most 16.6 ms.

This section rereads the four traces already taken (the two home screen swipe traces of §5; `hub.pftrace` and `hub2.pftrace` of §6, Hub slides), with `trace_processor` 49.0, and the Q25's `framework.jar` (`stubs/device/framework/framework.jar`, `dexdump -d`). The Helio G99 has six little cores (cpu 0 to 5, 500 MHz to 2.0 GHz) and two big ones (cpu 6 and 7, 725 MHz to 2.2 GHz).

### The first frame of a gesture, in `hub2.pftrace`

- **The `DOWN` and the first `MOVE` fall in the same vsync.** `input swipe` injects its moves without pause after the `DOWN`: the `DOWN` (event time 3170657.0 ms) is delivered at 3170660.0 in 2.4 ms, on cpu 5; nothing is requested from rendering; the next vsync (3170670.4) opens the first frame, which handles the `MOVE` of 3170664.1. Under `perf.sh` there is therefore no frame before the first frame of the gesture.
- **This first frame does the same work as the others, three times slower.** Its `input` lasts 4.7 ms on cpu 5 (of which `ViewPostImeInputStage` 2.9 ms), against 1.5 to 1.9 ms on cpu 6 or 7 for the following frames (`ViewPostImeInputStage` 0.95 ms): same code path (`HubPage.accountsTouch`, `place`), only the core changes. In the same frame, `Record View#draw()` is 0.95 ms against 0.33 ms afterwards; the only addition is re-recording the `FrameLayout` of the Hub content (0.38 ms), not attributed.
- **Frequencies stay at the floor.** At the start of the first frame, the little cores are at 500 to 700 MHz and the big ones at **725 MHz, their minimum**; 50 ms later almost nothing has moved; around 150 ms, the little cores reach 1.3 to 2.0 GHz in a little over half the gestures, and the big ones mostly stay at 725 MHz for the whole gesture.
- **Yet the platform sends its touch boost.** The `mtkPowerAIDL` thread of MediaTek's power HAL wakes 0.2 to 0.94 ms after each `DOWN` is delivered to the launcher (2.6 to 3.5 ms after the event time, and likewise on `UP`), woken by the `InputDispatcher` of `system_server` (tid 2118): this is the `Boost::INTERACTION` that Android sends on each touch (`pokeUserActivity` to `PowerManagerService`, at most once per 100 ms). And HWUI calls `notifyFramePending` on every frame (478 times), where Android 14 sends `CPU_LOAD_RESET` to its ADPF session after 100 ms without a frame, if the HAL supports ADPF. Neither raises any frequency.
- **MediaTek's mechanism fails.** The trace carries 35 `uclamp fail` counters that go to -1 then 0, in bursts (276 times), and once to -22 (`EINVAL`, on surfaceflinger's `binder:946_4`), on the launcher's main thread, its RenderThread, the GPU driver's `mali-cmar-backe` thread, its binder threads, the `InputDispatcher` and binder threads of `system_server`, `servicemanager`, and the injection's `sh` and `cmd`: the rendering chain of a frame, as MediaTek's framework tracks it (FPSGO). The first burst comes 190 ms after the start of the first gesture. The Q25's `framework.jar` carries the `com.mediatek.boostfwk` framework, called from `Choreographer.doFrame` and `onVsync` (`FrameScenario`), `ViewRootImpl$ViewPostImeInputStage.processPointerEvent` (`ScrollScenario`), `Scroller` and `OverScroller`; the launcher process has a `FramePolicy` thread. Reading: MediaTek's boost goes through a utilization floor (uclamp) on the rendering threads, and that floor is not set. Its implementation (`BoostFwkFactoryImpl`) is not in the repository's copies.

The rest is the mechanism of §5 and §6: first frame late, queue full until the end of the spring. Launcher3 has it too on this phone.

### Lead 1, done: `PagesView` by animation matrix

**Evidence.** In the two swipe traces of §5, 122 and 123 `SendWindowContentChangedAccessibilityEvent` in 24 swipes, about five per swipe, 0.7 ms each on the main thread (90 and 82 ms in total): `PagesView.place` changes the `translationX` of every page on every frame, and the alpha of the page entering or leaving. The BenOS trackpad service was not yet running when they were taken (no `q25trackpadcustomizer` process); in `hub.pftrace`, taken later, it was running, and 85 of these events led to 1940 `findAccessibilityNodeInfoByAccessibilityId` requests, 2.9 s of the main thread in 18 s (§6). The `View` methods that call `notifySubtreeAccessibilityStateChangedIfNeeded` in the Q25's `framework.jar`: `setTranslationX` and `Y`, `setAlphaInternal`, `setScaleX` and `Y`, `setRotation*`, `offsetLeftAndRight` and `offsetTopAndBottom`, `invalidateOutline`, `onScrollChanged`, `setFrame`, `setFlags`; `setAnimationMatrix`, `setClipBounds` and `setTransitionAlpha` only set a `RenderNode` property and `invalidateViewProperty`, and `View.isVisibleToUser` reads the transition alpha like the alpha.

**What was done** (`PagesView.place`, `stripBase` and `barShift` in `HomePaging.kt`):
- at rest, each page is placed by its `translationX`, as before: hit-testing finds it where it is drawn;
- while the strip moves (finger or spring), the translations stay those of the last rest position, and the whole strip slides by **its own animation matrix**, one call per frame. Hit-testing does not see this matrix, but has nothing to look at: during the movement, `PagesView` keeps all touches (a finger placed on a moving strip catches it, `handle`), and once at rest the matrix is removed and the translations take the position back. The only reader of a position during the movement is the trackpad focus square (`HomeKeys.place`, which adds `PagesView.shift`) and nothing else (dropping an icon computes its cell, `cellOrigin`);
- off-screen pages are hidden by their **transition alpha** at 0, on every frame, instead of their alpha: HWUI skips them entirely (`RenderNode::nothingToDraw`), as before. A first version clipped them to nothing (empty `clipBounds`); on review, it left the RenderThread walking the neighbouring pages on every redrawn frame, even at rest (HWUI only skips a view clipped to empty if it has `clipToBounds`, and neither `PagesView` nor `FramesView` clips its children): scrolling and the Hub account list, Active Frames scrolling and the edit mode pulse paid the price;
- the bottom bar does the same (`HomeView`, `barShift`): translated at rest, animation matrix while moving. While it is drawn offset (spring, swipe or Peek to or from the Hub), it refuses the `DOWN`, which falls through to the pages: they catch the strip, instead of a button or indicator hit-tested where the bar was at rest answering under a finger that sees something else;
- a page added or replaced during the movement immediately takes the current rest translation (`place` sets it on every frame, doing nothing when it does not change), without resetting the others; detaching mid-gesture brings the strip back to rest on its page;
- nothing allocated per frame: `stripBase` returns an integer, and the callback to the bar is a `fun interface` with two `Int`s (a lambda would box them). What the spring already allocated remains (`SpringState`).

JVM tests (`HomePagingTest`): at rest, the translations take the shown position; while moving, no translation changes and each page is drawn at `index × width − scroll`, whatever the scroll; a page is only drawn if part of it is on screen; the bar stays between the Active Frames and the grid and leaves with the Active Frames towards the Hub, and its offset while moving draws it where its scroll puts it.

**Expected from the measurement.** The before trace is taken on `main` (`build/perf/froid/avant.pftrace` and `avant-gfxinfo.txt`: 732 frames, 0.82% late, p95 18 ms, trackpad service active). In the after trace: **no** `SendWindowContentChangedAccessibilityEvent` during a swipe, at most one per swipe, 100 ms after rest (the translations set back); no `AccessibilityInteractionController` request during a swipe. In `perf.sh`, swipes at the bottom of the current range, 0.5% or less, and a slightly lower p95, but **not the budget**: the first frame on cold cores and the full queue remain. The Hub account list should not move (the Hub already slides by matrix), nor rest, tap, background CPU and PSS.

### Lead 2, ruled out: lightening the first frame

The first `MOVE` does nothing more than the following ones in `PagesView` or in `HubPage`: the `VelocityTracker` is taken at `DOWN`, nothing is built or allocated when crossing the threshold apart from `requestDisallowInterceptTouchEvent` and the `ACTION_CANCEL` sent to the children. What costs is the core (2.9 ms against 0.95 for the same `ViewPostImeInputStage`). The only extra cost specific to this frame, re-recording the Hub content (0.38 ms), does not change the fate of a 25 ms frame (main thread and RenderThread) that has 16.6.

### Lead 3, ruled out: waking CPU and GPU at `DOWN`

Under `perf.sh`, the `DOWN` and the first `MOVE` fall in the same vsync: a frame requested at `DOWN` would be the gesture's first frame itself, nothing precedes it. And the system already sends the power HAL a boost on each `DOWN`, with no effect on frequencies. A frame at `DOWN` would cost on every home screen touch, every tap included, for nothing in the measurement. With a real finger, the threshold leaves one to three frames before the first movement: the question would remain the boost that does not take.

### Lead 4, outside the app: the RenderThread on the little cores

Thread placement and frequencies belong to the scheduler and MediaTek's framework. An app cannot set its own floor (uclamp requires `CAP_SYS_NICE`), and the route Android gives it, a `PerformanceHintManager` session, is the one HWUI already uses for the main thread and the RenderThread: one more session would only duplicate it. No code. What remains is decided outside the app, after read-only checks: whether the HAL supports ADPF (`dumpsys performance_hint`), whether the kernel has uclamp, what MediaTek's HAL does with a touch. Changing the power configuration through the module would affect the whole phone: that is a separate decision (§8).

## 8. The foreground app floor, and the eco mode

Design choice: performance first, with an eco mode. The first frame of a gesture starts on cold cores (§7); what is missing is set outside the app.

### Read-only readings on the Q25

- **The governor does not wait.** Kernel `5.10.198-android12-9`, MediaTek's `sugov_ext` governor on both policies (`policy0`, cpu 0 to 5, 500 MHz to 2.0 GHz, capacity 348; `policy6`, cpu 6 and 7, 725 MHz to 2.2 GHz, capacity 1024), `up_rate_limit_us` and `down_rate_limit_us` at 1000. The `scaling_min_freq` floors are the hardware minimums. What lags is the utilization of a thread coming out of idle: `/proc/sys/kernel/sched_pelt_multiplier` is 1 (32 ms half-life).
- **uclamp is there, at zero.** `/dev/cpuctl/<group>/cpu.uclamp.min`, `cpu.uclamp.max`, `cpu.uclamp.latency_sensitive`, context `cgroup`, `system:system 0660`; `top-app` at `0.00`, `max`, `0`. MediaTek's `/vendor/etc/init/hw/init.cgroup.rc` sets 100 (%) and `latency_sensitive` 1 on all groups at `post-fs-data` ("boot perf. tune", with the frequency floors at maximum), then sets 0 everywhere again on `sys.boot_completed=1`.
- **MediaTek's power HAL runs empty.** `vendor.mediatek.hardware.mtkpower@1.0-service` (root, domain `mtk_hal_power`) also serves `IPower/default` (AIDL v2). According to the strings of `libpowerhal.so`, everything goes through tables: `/vendor/etc/powercontable.xml` (resources), `powerscntbl.xml` (scenarios, including the touch boost, `PERF_RES_POWERHAL_TOUCH_BOOST_*`), `power_app_cfg.xml` (per-package profiles), `power_whitelist_cfg.xml`, and their copies under `/data/vendor/powerhal/`. **None of these files exists on BenOS**, nor `fstb.cfg`, `xgf.cfg`, `gbe.cfg`; `/data/vendor/powerhal/` is empty; neither does `/proc/perfmgr/boost_ctrl/`, which MediaTek's init prepares for it (this kernel only has `eas_ioctl`, `perf_ioctl`, `eara_ioctl`, `xgff_ioctl`). Hence the `Boost::INTERACTION` received with no effect in §7, and `TouchUtility: notifyAppState error = NULL` in the log on each app switch.
- **No per-package profile: Launcher3 gets nothing more than Bold7.** No app table or whitelist; the log shows the same path (`PowerHalWrapper: amsBoostNotify`) for `com.android.settings` and for the Bold7 launcher. Consistent with §5: Launcher3 has the same first frames on this phone.
- **ADPF absent**: `dumpsys performance_hint` answers `HAL Support: false`. HWUI's sessions go nowhere.
- **FPSGO tracks the launcher but its boost fails.** MediaTek's kernel module (`/sys/kernel/fpsgo`, `fpsgo_enable` 1, `enable_uclamp_boost` active) does compute a performance index for the launcher (`perf idx` counter up to 100 in `hub2.pftrace`), and applies it through a per-thread uclamp: this is the `uclamp fail` of §7, at -1, and at -22 (`EINVAL`) on a surfaceflinger real-time thread, which the kernel refuses with a zero priority. The origin of the -1 (thread not found or kernel refusal) is not established, and cannot be fixed without touching the kernel. In any case FPSGO only acts after ~190 ms: it would not warm up the first frame.
- **No usable init event.** No `on property:` in the ROM's `.rc` files writes to `cpuctl`, a frequency or PELT (only `/proc/perfmgr/boost_ctrl/cpu_ctrl/boot_freq` at boot, a missing path). An `.rc` mounted by the module would come too late: `init` reads its own before `post-fs-data`.
- **Who can write.** uclamp: root, and `system_server` through its task profiles (`/system/etc/task_profiles.json` only sets `UClampLatencySensitive`, for `CpuPolicySpread` and `CpuPolicyPack`). `scaling_min_freq` and `sugov_ext/*`: `system:system 0660`, `sysfs_devices_system_cpu`. `sched_pelt_multiplier`: root only, `proc_sched`. Neither the launcher (`priv_app`) nor SystemUI: it takes the root of the module scripts, or `system_server`.
- **MediaTek's HAL can write the same file.** `libpowerhal.so` carries `/dev/cpuctl/top-app/cpu.uclamp.min`, `set_topapp_uclamp_min` and `resetScenario`; `vendor.mediatek.hardware.mtkpower@1.0-init.rc` (l. 86 and 87) gives it the file (`system`) and sets `vendor.powerhal.boot_complete 1` on the same `sys.boot_completed=1`. Without its tables, no scenario should touch it, and the value read after 21 min of use was indeed `0.00`; but it is the first writer to suspect if the value set by Bold7 changes.
- **The `top-app` group does not contain only the foreground app.** Read with the screen on and the launcher in the foreground: the launcher; the whole BlackBerry keyboard (`com.blackberry.keyboard`); `zygote` and `zygote64` (`init.zygote64.rc`, profile `MaxPerformance`); `cameraserver` (`cameraserver.rc`, `MaxPerformance`) and `camerahalserver` (`/vendor/etc/init/camerahalserver.rc`); and `system_server` threads: `android.ui`, `android.display`, `android.anim`, `android.anim.lf`, `FramePolicy`, `AccessibilityTr`, `LazyTaskWriterT`, `pool-7-thread-1`. `MaxPerformance` puts them in `cpu:/top-app` (`/system/etc/task_profiles.json`, l. 162) for the whole life of the process, screen off included.

### The levers

| Lever | What it does | Scope | Chosen |
|---|---|---|---|
| `top-app/cpu.uclamp.min` | as soon as a thread of the foreground app runs, the scheduler requests a frequency matching the floor | the foreground app, the keyboard, zygote, the camera servers, the `system_server` UI threads; nothing when nothing runs | **yes, 20%** |
| `top-app/cpu.uclamp.latency_sensitive` | placement on an idle core, no frequency | the foreground app | no, as long as no trace shows the RenderThread queued behind another thread |
| `sched_pelt_multiplier` at 4 | utilization rises and falls four times faster | the whole system, background included | no: more expensive, not targeted |
| `scaling_min_freq` | permanent floor | everything, screen off included | no |
| HAL tables (`powerscntbl.xml`, `power_app_cfg.xml`) | a boost of a few hundred ms on each touch, or a profile reserved for Bold7's package | the finest | plan, below |

Why 20%: 205 out of 1024. On a little core, 205 / 348 × 2.0 GHz is 1.18 GHz, 1.47 GHz with schedutil's 25% margin (which `sugov_ext` may keep), instead of 500 to 700 MHz; the thread still fits on a little core (80% threshold of 348, i.e. 278) and the big cores stay at 725 MHz, where §7 already sees them fast. This is the order of the floors that MediaTek's surfaceflinger sets on its own threads (`vendor.debug.sf.cpupolicy.min_boost` 165, `upbound_uclamp_min` 350, out of 1024). From 28% upwards, every thread of the foreground app would move to the big cores.

### Implementation

`system/module/src/energy.sh`, started in the background by `boot-completed.sh`, writes 20 to `/dev/cpuctl/top-app/cpu.uclamp.min` once per boot. Since it starts on the same `sys.boot_completed=1` as MediaTek's reset, it first waits for the value to be `0.00`, `init`'s reset, for at most 30 s (once per boot, never afterwards); past that delay, it touches nothing and logs the value found (100 from `init`, or another writer's). Without uclamp in the kernel, it does nothing. Nothing else runs: no process, no timer. The value lives in memory, in the cgroup: a boot without the module gives back the ROM's 0, nothing survives removal.

The eco mode now follows Battery Saver (§9); by hand, for testing: `sh /data/adb/modules/bold7/energy.sh /data/adb/modules/bold7 /dev/cpuctl eco` (root) sets 0 again and sets the module's `eco` flag (`customize.sh` copies it into the directory that replaces the old one): the flag only serves the floor set before `saver.sh` reads Battery Saver, because at boot `energy.sh boot` first applies the flag, then `saver.sh` applies `low_power` if it differs; a manual `eco` therefore holds until the next toggle or reboot; `perf` sets 20 again and removes the flag; without a mode, it shows the value and the kept choice. Each decision goes to `bold7.log`. Test without module or cgroup: `system/module/test-energy.sh`.

### An eco mode that follows Battery Saver: no way without a daemon

§9 takes this up. In power saving mode, Android sets `Settings.Global.low_power`, calls `IPower.setMode(LOW_POWER)` (MediaTek's HAL has nothing in its tables for it) and changes the `cache_key.is_power_save_mode` nonce. None of these signals reaches a root writer without a process waiting for it, and no init trigger is available (above). The routes, from least bad to worst:

1. **A busybox `inotifyd`, started by `service.sh`**, blocked on a file that the SystemUI module (`system/sysui`) would write in its own directory when Battery Saver changes (SystemUI already follows it for the "Battery saving" tile), and which calls `energy.sh perf` or `eco`. No wakeup at rest (blocked in the kernel), but one more root process, alive all the time: a daemon, which needs a deliberate decision.
2. A `system_server` hook (Vector, scope `android`) on the Battery Saver controller, which would write itself as uid `system`: no process, but a hook in `system_server`. Ruled out as a precaution.
3. The floor alone, with the eco mode by hand: what is shipped.

### Plan not done: MediaTek's HAL tables

The finest option would be to give the HAL its tables back: a touch boost (`PERF_RES_POWERHAL_TOUCH_BOOST_*`: frequency, uclamp, duration) only during interaction, or a per-package profile for `dev.bold7.launcher`. But their format is documented nowhere here and would have to be written blind for a vendor root daemon, by mounting `/vendor/etc` as an overlay, and the HAL reads them at boot: each attempt needs a reboot. A reading step first: the Zinwa Q25's original firmware, if it carries them, would give the format.

### Expected battery cost (estimate, to be measured)

- Screen on at rest: nothing, no thread of the group runs. Screen off: the top app moves to `TOP_SLEEPING`, placed in the `background` group according to AOSP; what stays in `top-app` is zygote, the camera servers, the keyboard and the `system_server` UI threads, which sleep with the screen off: probably a low cost, to be checked through `cgroup.procs` and `time_in_state` with the screen off, floor then eco. No added wakeup.
- Besides gestures: an app launch starts from zygote, so with the floor until the new process changes group (launches slightly faster, slightly more expensive); `system_server` window animations (`android.anim`) and the keyboard benefit too.
- In use: foreground app work that lands on a little core runs at about 1.2 to 1.5 GHz instead of 0.5 to 0.7, roughly twice the energy per cycle (higher voltage, V² estimate, not measured), but finished two to three times faster. A UI that draws a few ms per frame pays a few tens of mW during gestures, against the hundreds of mW of the screen. What pays the most: an app working nonstop in the foreground (video, game, navigation). The big cores do not change below 28%.

## 9. The eco mode follows the "Battery saving" tile

The eco mode of §8 follows Android's Battery Saver, which the BB10-style "Battery saving" tile turns on and off. Saver on, floor at 0; off, 20%. `energy.sh … eco|perf` remains for testing.

### The signal: a property the framework already sets

The route proposed in §8 (a file written by one of Bold7's apps, waited on by a root `inotifyd`) is not needed: `system_server` already changes a system property on each toggle. Read-only readings on the Q25:

- **`cache_key.is_power_save_mode`** (context `binder_cache_system_server_prop`) is the nonce of the `PowerManager.isPowerSaveMode()` cache. In the Q25's `services.jar`, `PowerManager.invalidatePowerSaveModeCaches()` is only called by `BatterySaverController` (constructor, `setFullEnabledLocked`, `setAdaptiveEnabledLocked`) and by `BatterySaverPolicy.updatePolicyDependenciesLocked` (a change of the saver policy, rare). The value does not tell the state: it is a number that changes.
- **The state is `Settings.Global.low_power`**, written by `BatterySaverStateMachine.enableBatterySaverLocked` **before** the call to `BatterySaverController.enableBatterySaver`, which changes the nonce (order read in the dex). When the nonce moves, `settings get global low_power` therefore already tells the new state. The tile, the automatic saver and plugging in the charger (which turns it off) all go through there.
- **`resetprop -w <name> <value>`** (KernelSU, `/data/adb/ksu/bin/resetprop`) waits for the property to leave the given value: exit code 0 at once if it differs, otherwise it sleeps. Measured: process blocked in `futex_wait_queue_me`, **1 voluntary context switch in 10 s** (the one going to sleep), 0 CPU ticks; RSS 5.0 MB of which 0.7 MB anonymous. `__system_property_wait` however sleeps on the global serial of the property area, not on the futex of that single property: it wakes on every system property write, rereads the value and goes back to sleep. Measured on the Q25 on review, screen on: about 5 wakeups per minute, no fork, utime 0 and stime 1 tick over 120 s, each wakeup landing on a CPU the writer has already woken. The cost is therefore that of these wakeups, not zero, and it compares with a control (`resetprop -w ro.build.type user`, which sleeps on the same serial) rather than with nothing.
- KernelSU's busybox (1.36.1, topjohnwu) does have `inotifyd`, and root (domain `ksu`) reads the launcher's data directory (`privapp_data_file`): the §8 route was possible, but it required code in the launcher, followed nothing once it was dead, and stopped (`x`) if its directory was recreated. This one depends on none of Bold7's apps.

### Implementation

`system/module/src/saver.sh`, started by `boot-completed.sh` once `energy.sh boot` has succeeded (after MediaTek's reset, the settings provider answers), runs until reboot:

1. reads the nonce, then `low_power` (`1` on; `0` or `null` off; anything else, `system_server` restarting: nothing changes and it is logged);
2. if the state differs from the one in force, calls `energy.sh eco` or `perf` (which also sets or removes the `eco` flag); a failed write is retried on the next change;
3. waits for the nonce to change (`resetprop -w`), and starts again.

If only `system_server` restarts (the module and `saver.sh` stay), the nonce may change before `SettingsProvider` answers: `low_power` is then unreadable, the line is logged and the floor in force is kept. This is only wrong if the saver state changed during the restart with no later nonce toggle; the next toggle, or rebooting the phone, corrects it.

The rule between the tile and the manual command: **the Battery Saver state wins at every boot and at each of its changes; a manual `energy.sh … eco|perf` holds until the next one.** At boot, `energy.sh boot` first applies the kept choice, then `saver.sh` the Battery Saver state if it differs.

Guards: only one `saver.sh` follows (`saver.pid`; an older one stops at its next wakeup); it stops at the next wakeup if the module is disabled (`disable`) or marked for removal (`remove`), and the following reboot stops it anyway; it also stops if the wait fails, or returns without the nonce having changed (which would loop if a version of `resetprop` reversed its meaning). In safe mode, KernelSU does not run `boot-completed.sh`: nothing runs. Test without module or property: `system/module/test-saver.sh` (15 cases), passed with the phone's `sh` and, once as root, with KernelSU's busybox that runs the module scripts.

### Cost

- At rest: two sleeping root processes, `sh` (`saver.sh`, about 0.7 MB anonymous) and `resetprop` (0.7 MB anonymous), no timer; `resetprop -w` wakes on every system property write (about 5 per minute with the screen on, no fork).
- On each toggle: one `cmd settings` (binder to `system_server`) and, if the state changed, one `energy.sh`. Expected latency: that of `cmd settings`, under a second; to be measured.
