#!/usr/bin/env bash
# Replayable performance measurements of whatever launcher holds the HOME role on the Q25.
#
#   tools/perf.sh <label> <icon-label>
#   APP=<package>/<activity> tools/perf.sh <label>
#
# <label>       name of the run, results go to build/perf/<label>/ (summary.txt + raw dumps)
# <icon-label>  text or content-desc of a home-screen icon to tap (as uiautomator sees it)
#
# With APP, an app of Bold7 instead of the launcher: its cold
# start (am start -W as root, TotalTime: launch to first frame), APP_RUNS times (10), then
# APP_SCROLLS (20) flings up and down of its list, opened with APP_ARGS (default "--ei rows 500",
# the 500 made-up rows of dev.bold7.essai/.DemoActivity, and of dev.bold7.messages/.MessagesActivity). The app is force-stopped between runs
# and at the end; nothing else of it is touched.
#
# Env: ICON_PAGE (0), SWIPES (20 round trips), RUNS (5 cold taps), IDLE_S (10), BG_S (30), BG_APP (Settings),
#      SWIPE_Y (360; 670 on the BBOS 7 tray), TRAY (0: the tray is not raised nor scrolled),
#      HUB_SCROLLS (refused: the list is Messages', measured with APP=dev.bold7.messages/.MessagesActivity),
#      HUB_SPLATS (0; 1: HUB_ROWS (200) made-up rows of the Hub from the start, so that the swipes
#      and the rest run with the banner's unread counts drawn)
#
# Requires the phone unlocked on its home screen. The only state it touches is
# screen_off_timeout, raised during the run and restored on exit, and with HUB_SPLATS the Hub's
# made-up rows (Settings.Global bold7_hub_demo, HUB_ROWS of them), deleted on exit.
set -euo pipefail

label=${1:?usage: tools/perf.sh <label> <icon-label>, or APP=<package>/<activity> tools/perf.sh <label>}
if [ -z "${APP:-}" ]; then icon=${2:?usage: tools/perf.sh <label> <icon-label>}; fi
if [ "${HUB_SCROLLS:-0}" -gt 0 ]; then
  echo "HUB_SCROLLS: the list is Messages': APP=dev.bold7.messages/.MessagesActivity tools/perf.sh <label> (500 made-up rows)" >&2
  exit 2
fi
SWIPE_Y=${SWIPE_Y:-360} ICON_PAGE=${ICON_PAGE:-0} SWIPES=${SWIPES:-20} RUNS=${RUNS:-5} IDLE_S=${IDLE_S:-10} BG_S=${BG_S:-30}
BG_APP=${BG_APP:-com.android.settings/.Settings}
out=build/perf/$label
mkdir -p "$out"
sh() { adb shell "$@" | tr -d '\r'; }
log() { echo "$*" | tee -a "$out/summary.txt"; }
: > "$out/summary.txt"

timeout=$(sh settings get system screen_off_timeout)
# Also deletes the Hub's made-up rows, even if this run did not set them: never left on.
restore() {
  adb shell settings put system screen_off_timeout "$timeout"
  if [ "${HUB_SCROLLS:-0}" -gt 0 ] || [ "${HUB_SPLATS:-0}" -gt 0 ]; then
    adb shell settings delete global bold7_hub_demo >/dev/null
  fi
  # The app measured is never left running, even when the run stops half way.
  if [ -n "${APP:-}" ]; then adb shell am force-stop "${APP%%/*}"; fi
}
trap restore EXIT
sh settings put system screen_off_timeout 1800000
sh input keyevent KEYCODE_WAKEUP
sh wm dismiss-keyguard  # no-op past a PIN: the check below then stops the run
sleep 1
if sh dumpsys window | grep -q 'isKeyguardShowing=true'; then
  echo "The phone is locked: unlock it and run again." >&2; exit 1
fi

# First process-wide value of a gfxinfo field ("Janky frames", "95th percentile", ...)
gfx() { grep -m1 "^$2:" "$1" | cut -d: -f2- | sed 's/^ *//'; }

# An app instead of the launcher: cold start, then its list, and the run ends.
# Its activities are not exported (dev.bold7.essai): started as root, as the test sessions do.
if [ -n "${APP:-}" ]; then
  pkg=${APP%%/*}
  APP_RUNS=${APP_RUNS:-10} APP_SCROLLS=${APP_SCROLLS:-20} APP_ARGS=${APP_ARGS:---ei rows 500}
  start_app() { sh "su -c 'am start -W -n $APP $APP_ARGS'"; }
  log "run: $label"
  log "app: $APP $(sh dumpsys package "$pkg" | grep -m1 versionName | sed 's/^ *//')"
  log "compiled: $(sh dumpsys package dexopt | grep -A4 "\[$pkg\]" | grep -m1 -oE 'status=[a-z-]+' || true)"
  log "build: $(sh getprop ro.build.display.id)"
  log "params: APP_RUNS=$APP_RUNS APP_SCROLLS=$APP_SCROLLS APP_ARGS=$APP_ARGS"
  log ""
  log "## cold start, $APP_RUNS runs (launch to first frame, TotalTime of am start -W; budget 750 ms)"
  : > "$out/app-start.txt"
  for r in $(seq "$APP_RUNS"); do
    sh am force-stop "$pkg"
    sleep 1
    start_app >> "$out/app-start.txt"
    total=$(grep TotalTime "$out/app-start.txt" | tail -1 | grep -oE '[0-9]+' || true)
    log "run $r: ${total:-?} ms"
    sleep 2
  done
  sh am force-stop "$pkg"
  sleep 1
  start_app > /dev/null
  sleep 2
  sh dumpsys gfxinfo "$pkg" reset >/dev/null
  sh "for i in \$(seq $APP_SCROLLS); do input swipe 360 560 360 180 150; sleep 0.8; input swipe 360 180 360 560 150; sleep 0.8; done"
  sh dumpsys gfxinfo "$pkg" > "$out/app-gfxinfo.txt"
  f=$out/app-gfxinfo.txt
  log ""
  log "## list scrolls ($((APP_SCROLLS * 2)) flings; budget < 1 % janky, p95 <= 16.6 ms)"
  log "frames: $(gfx "$f" 'Total frames rendered')  janky: $(gfx "$f" 'Janky frames')"
  log "p50 $(gfx "$f" '50th percentile')  p90 $(gfx "$f" '90th percentile')  p95 $(gfx "$f" '95th percentile')  p99 $(gfx "$f" '99th percentile')"
  exit 0
fi

home=$(sh cmd role get-role-holders android.app.role.HOME)
go_home() { sh input keyevent KEYCODE_HOME; sleep 2; }

log "run: $label"
log "launcher: $home $(sh dumpsys package "$home" | grep -m1 versionName | sed 's/^ *//')"
log "build: $(sh getprop ro.build.display.id)"
log "params: ICON_PAGE=$ICON_PAGE SWIPES=$SWIPES SWIPE_Y=$SWIPE_Y TRAY=${TRAY:-0} RUNS=$RUNS IDLE_S=$IDLE_S BG_S=$BG_S BG_APP=$BG_APP HUB_SCROLLS=${HUB_SCROLLS:-0} HUB_SPLATS=${HUB_SPLATS:-0}"

# Made-up rows from the start: their unread ones make the banner's counts, there to measure.
if [ "${HUB_SPLATS:-0}" -gt 0 ]; then sh settings put global bold7_hub_demo "${HUB_ROWS:-200}"; fi

# 1. Page swipes on the home screen: right-to-left then back, SWIPES times, at SWIPE_Y (360, the
# middle of BB10's pages; 670 for the BBOS 7 tray lowered, where a swipe changes view).
go_home
sh dumpsys gfxinfo "$home" reset >/dev/null
sh "for i in \$(seq $SWIPES); do input swipe 600 $SWIPE_Y 120 $SWIPE_Y 200; sleep 0.6; input swipe 120 $SWIPE_Y 600 $SWIPE_Y 200; sleep 0.6; done"
sh dumpsys gfxinfo "$home" > "$out/swipes-gfxinfo.txt"
f=$out/swipes-gfxinfo.txt
log ""
log "## swipes ($((SWIPES * 2)) gestures)"
log "frames: $(gfx "$f" 'Total frames rendered')  janky: $(gfx "$f" 'Janky frames')"
log "p50 $(gfx "$f" '50th percentile')  p90 $(gfx "$f" '90th percentile')  p95 $(gfx "$f" '95th percentile')  p99 $(gfx "$f" '99th percentile')"

# 1b. The BBOS 7 tray, only with TRAY > 0: raised and lowered TRAY times (a swipe up
# from its row, then down from its first rows), then, raised, its rows scrolled down and up TRAY
# times. The view All of the phone needs more than 5 rows for the scroll to move.
if [ "${TRAY:-0}" -gt 0 ]; then
  tray() {
    sh dumpsys gfxinfo "$home" reset >/dev/null
    sh "$1"
    sh dumpsys gfxinfo "$home" > "$out/$2-gfxinfo.txt"
    f=$out/$2-gfxinfo.txt
    log ""
    log "## $2 ($3)"
    log "frames: $(gfx "$f" 'Total frames rendered')  janky: $(gfx "$f" 'Janky frames')"
    log "p50 $(gfx "$f" '50th percentile')  p90 $(gfx "$f" '90th percentile')  p95 $(gfx "$f" '95th percentile')  p99 $(gfx "$f" '99th percentile')"
  }
  go_home
  tray "for i in \$(seq $TRAY); do input swipe 360 670 360 300 150; sleep 0.8; input swipe 360 300 360 670 150; sleep 0.8; done" tray-raise "$((TRAY * 2)) moves"
  sh input swipe 360 670 360 300 150
  sleep 1
  tray "for i in \$(seq $TRAY); do input swipe 360 600 360 300 150; sleep 0.8; input swipe 360 300 360 600 150; sleep 0.8; done" tray-scroll "$((TRAY * 2)) scrolls"
  go_home
fi

# 2. Idle home screen: frames rendered while nothing happens.
go_home
sleep 2
sh dumpsys gfxinfo "$home" reset >/dev/null
sleep "$IDLE_S"
sh dumpsys gfxinfo "$home" > "$out/idle-gfxinfo.txt"
log ""
log "## idle ${IDLE_S}s"
log "frames: $(gfx "$out/idle-gfxinfo.txt" 'Total frames rendered')"

# 3. Tap on an icon until the app's first frame, cold start.
# ICON_PAGE: how many pages right of where HOME lands the icon sits.
next_page() { sh input swipe 600 360 120 360 200; sleep 1; }
to_icon_page() { go_home; for ((i = 0; i < ICON_PAGE; i++)); do next_page; done; }
to_icon_page
sh uiautomator dump /sdcard/bold7-ui.xml >/dev/null
sh cat /sdcard/bold7-ui.xml > "$out/home-ui.xml"
sh rm /sdcard/bold7-ui.xml
bounds=$(grep -oE "(text|content-desc)=\"$icon\"[^>]*bounds=\"[^\"]+\"" "$out/home-ui.xml" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' || true)
[ -n "$bounds" ] || { echo "Icon '$icon' not found on the home screen, see $out/home-ui.xml" >&2; exit 1; }
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | tr -c '0-9' ' ')"
tx=$(((x1 + x2) / 2)) ty=$(((y1 + y2) / 2))

# One tap to learn which component the icon starts.
t=$(sh date +%s.%N)
sh input tap $tx $ty
sleep 4
cmp=$(sh logcat -d -v epoch -T "$t" -s ActivityTaskManager:I | grep -m1 -oE 'START u0 .*cmp=[^ }]+' | sed 's/.*cmp=//')
[ -n "$cmp" ] || { echo "No activity start seen after tapping '$icon'" >&2; exit 1; }
pkg=${cmp%%/*}
log ""
log "## tap '$icon' ($tx,$ty, page $ICON_PAGE right of HOME) -> $cmp, cold start, $RUNS runs"
log "tap->START is the launcher's share (includes ~70 ms of 'cmd input' injection), tap->Displayed is the first frame"
: > "$out/tap-logcat.txt"
for r in $(seq "$RUNS"); do
  sh am force-stop "$pkg"
  to_icon_page
  t=$(sh date +%s.%N)
  sh input tap $tx $ty
  sleep 5
  sh logcat -d -v epoch -T "$t" -s ActivityTaskManager:I > "$out/tap-run.txt"
  cat "$out/tap-run.txt" >> "$out/tap-logcat.txt"
  # First frame of any activity of the package: some apps open on a "what's new" screen.
  start=$(grep -m1 "START u0 .*cmp=$cmp" "$out/tap-run.txt" | awk '{print $1}' || true)
  shown=$(grep -m1 "Displayed $pkg/" "$out/tap-run.txt" | awk '{print $1}' || true)
  if [ -n "$start" ] && [ -n "$shown" ]; then
    log "run $r: tap->START $(awk -v a="$t" -v b="$start" 'BEGIN{printf "%d", (b-a)*1000}') ms, tap->Displayed $(awk -v a="$t" -v b="$shown" 'BEGIN{printf "%d", (b-a)*1000}') ms"
  else
    log "run $r: incomplete (start='$start' displayed='$shown')"
  fi
done
rm -f "$out/tap-run.txt"
go_home

# 4. Launcher CPU and wakeups while another app is used (scrolled) in the foreground.
pid=$(sh pidof "$home")
cpu() { sh "su -c 'cut -d\" \" -f14,15 /proc/$pid/stat; cat /proc/$pid/task/*/status | grep ctxt_switches'" | awk 'NR==1{t=$1+$2; next} {s+=$2} END{print t, s}'; }
sh am start -n "$BG_APP" >/dev/null
sleep 3
read -r t0 c0 <<<"$(cpu)"
sh "end=\$((\$(date +%s) + $BG_S)); while [ \$(date +%s) -lt \$end ]; do input swipe 360 550 360 200 300; sleep 1; input swipe 360 200 360 550 300; sleep 1; done"
read -r t1 c1 <<<"$(cpu)"
log ""
log "## launcher in background, ${BG_S}s of scrolling in $BG_APP"
log "cpu: $(((t1 - t0) * 10)) ms  context switches (all threads): $((c1 - c0))"
go_home

# 5. Memory of the launcher's main process, on the home screen.
sleep 2
sh dumpsys meminfo "$home" > "$out/meminfo.txt"
log ""
log "## memory"
log "$(grep -m1 -E 'TOTAL PSS:' "$out/meminfo.txt" | sed 's/^ *//')"
