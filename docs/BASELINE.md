# Baseline measures

Measured on the Q25 (BenOS `Q25-BenOS_06.06.2026`, 60 Hz screen, so 16.6 ms per frame) with `tools/perf.sh`. Raw outputs are in `build/perf/<label>/` (not in git); running them again gives the same orders of magnitude.

## What `perf.sh` measures

| Measure | Method |
|---|---|
| Swipes | 20 back-and-forth page swipes on the home screen (`input swipe`, 200 ms), the launcher's `dumpsys gfxinfo` reset beforehand |
| Rest | home screen shown, 10 s of doing nothing, frames rendered by the launcher |
| Tap | icon found by `uiautomator`, target app killed (`am force-stop`), tap, then logcat timestamps: `START` (the launcher has started the activity) and `Displayed` (first frame of the app). 5 cold runs |
| Background | Android Settings in the foreground, scrolled for 30 s; CPU (`utime + stime`) and context switches of all the launcher's threads |
| Memory | `TOTAL PSS` from `dumpsys meminfo`, home screen shown |

Known biases:
- the tap goes through `cmd input`, which adds about 70 ms to "tap to START" (measured: an `input keyevent` takes 73 ms). This bias is the same for every launcher;
- the target app is Google Calendar, which opens a "What's new" screen on every cold start: "tap to first frame" counts the first frame of any activity of the package.

## Stock Launcher3 (SearchLauncherQuickStep, versionName 14)

Measured while Launcher3 held HOME and the recents provider role (SystemUI bound, `isConnected=true`).

```
ICON_PAGE=0 tools/perf.sh launcher3-1 Calendar
ICON_PAGE=0 tools/perf.sh launcher3-2 Calendar
```

| Measure | Pass 1 | Pass 2 |
|---|---|---|
| Swipes: frames rendered | 862 | 859 |
| Swipes: janky frames | 1.97 % | 2.79 % |
| Swipes: p50 / p90 / p95 / p99 | 10 / 15 / 18 / 26 ms | 11 / 16 / 21 / 28 ms |
| Rest 10 s: frames rendered | 0 | 0 |
| Tap to START (5 runs) | 118 to 156 ms | 123 to 136 ms |
| Tap to first frame | 905 to 978 ms | 902 to 974 ms |
| Background 30 s: CPU | 0 ms | 0 ms |
| Background 30 s: context switches | 0 | 0 |
| PSS | 107.8 MB | 123.5 MB |

Medians over the 10 tap runs: **125 ms** to `START`, **951 ms** to the first frame.

To be read with these caveats:
- the "Calendar" icon on the Launcher3 home screen starts `com.android.calendar.event.LaunchInfoActivity`, a relay activity, not `AllInOneActivity`: the first frame is therefore not comparable with a launcher that starts the main activity directly. The time to `START` is comparable;
- the Launcher3 home screen has only one page: the swipes there are mostly edge bounce, lighter than a real page change;
- 0 ms of background CPU is not a freeze: the process was not frozen (`isFrozen=false`), it was doing nothing.

What it shows: Launcher3 has no frame at rest and no background work. Even it misses vsyncs on this 60 Hz screen (p95 between 18 and 21 ms): the Bold7 budget for swipes is stricter than Android's own reference.

### First attempt, while another launcher was still the recents provider

A first attempt, with another launcher still the recents provider, made **Launcher3 crash in a loop** while loading:

```
FATAL EXCEPTION: launcher-loader
java.lang.SecurityException: Permission Denial: createPredictionSession from pid=19190, uid=10148
  expected caller to hold PACKAGE_USAGE_STATS permission
  at com.android.launcher3.model.QuickstepModelDelegate.recreatePredictors(QuickstepModelDelegate.java:344)
```

`AppPredictionManagerService` only serves the recents provider or a holder of `PACKAGE_USAGE_STATS`, and Launcher3 does not declare that permission. **For Bold7, being the recents provider is therefore also the condition for using the system's app predictions.** This attempt left a trace: Gboard, which the system kept disabled, went back to its default state; two reboots later, the system had disabled it again on its own.

## Budgets

| Measure | Launcher3 | Bold7 budget |
|---|---|---|
| Swipes: janky frames | 2 to 2.8 % | < 1 % |
| Swipes: p95 | 18 to 21 ms | ≤ 16.6 ms |
| Rest 10 s | 0 | 0 frames |
| Tap to START (`cmd input` bias included) | 125 ms | ≤ 120 ms |
| Tap to first frame (Calendar, cold) | 951 ms (relay activity) | ≤ 750 ms |
| Background 30 s: CPU | 0 ms | ≤ 20 ms |
| Background 30 s: context switches | 0 | ≤ 15 |
| Home screen PSS | 108 to 124 MB | ≤ 120 MB |

The first-frame budget comes from an earlier BB10-style launcher measured on the same phone, without its launch delay (1089 ms minus 340 ms): that is what the system gives when the launcher starts Calendar's main activity without waiting.
