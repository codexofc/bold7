#!/bin/sh
# Films the takes of the video's scenario on the Q25, in showcase mode, into
# public/captures/<take>.mp4 (720x720, never committed: they show BlackBerry artwork).
#
# Usage: media/demo/capture.sh [take...]   (default: every take, in the order of the video)
# Takes: lockscreen home sms call menu alarm search switcher (src/storyboard.ts)
#
# Showcase mode (Settings.Global bold7_showcase, core/showcase) shows made-up contacts, messages
# and calls: it is set at the start and deleted at the end with bold7_showcase_event, even after
# an error or ^C. No real call and no real text: the call is the Phone's fabricated screen
# (CallActivity DEMO), the text is typed in Daniel's made-up thread, which sends nothing; the
# message, the reply and the alarm are the showcase's events (bold7_showcase_event), which only
# change what the screens show. Each take plays its own keys, one a second, each after checking
# that the window it is meant for is in front (the take stops otherwise). Each take lasts a bit
# longer than its shot; the video starts it at `trim` and plays it at `rate` (src/storyboard.ts).
# CONFIRM=1 waits for Enter before each take (look at the whole screen first).
SHOWCASE_MESSAGE_CMD="${SHOWCASE_MESSAGE_CMD:-settings put global bold7_showcase_event message}" # Daniel's text arrives
SHOWCASE_REPLY_CMD="${SHOWCASE_REPLY_CMD:-settings put global bold7_showcase_event reply}"       # his reply arrives (sms)
SHOWCASE_ALARM_CMD="${SHOWCASE_ALARM_CMD:-settings put global bold7_showcase_event alarm}"       # the alarm rings now (alarm)
set -eu
cd "$(dirname "$0")"
DIR=/sdcard/bold7-demo
LAUNCHER=dev.bold7.launcher/dev.bold7.launcher.LauncherActivity
# The answer typed in the thread: Showcase.STORY_ANSWER of core/showcase.
ANSWER='See you at 7!'

sh_() { adb shell "$@" </dev/null | tr -d '\r'; }
su_() { adb shell "su -c '$*'" </dev/null; }
event_off() { sh_ settings delete global bold7_showcase_event >/dev/null 2>&1 || true; }
cleanup() {
	sh_ settings delete global bold7_showcase >/dev/null 2>&1 || true
	event_off
	sh_ rm -rf "$DIR" >/dev/null 2>&1 || true
	su_ am force-stop dev.bold7.phone >/dev/null 2>&1 || true
	su_ am force-stop dev.bold7.clock >/dev/null 2>&1 || true
	echo "showcase mode off, files removed from the phone"
}

adb get-state >/dev/null
trap cleanup EXIT
trap 'exit 130' INT TERM
sh_ settings put global bold7_showcase 1
event_off
sh_ mkdir -p "$DIR"
mkdir -p public/captures

# The window in front: the last mCurrentFocus of the dump (an earlier one can be stale).
focused() { sh_ dumpsys window | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | tail -1; }
locked() { sh_ dumpsys window | grep -q 'isKeyguardShowing=true'; }

# expect <window>: waits up to 5 s for it in front, else stops the script.
expect() {
	for _ in 1 2 3 4 5 6 7 8 9 10; do
		case "$(focused)" in *"$1"*) return 0 ;; esac
		sleep 0.5
	done
	echo "   stopped: $1 is not in front ($(focused))" >&2
	exit 1
}
# key <window> <keyevent args>: one key once the window is in front, then a second.
key() { expect "$1"; sh_ input keyevent $2; sleep 1; }
# type <window> <text>: letter by letter, a second each.
type_() {
	w=$1 text=$2
	while [ -n "$text" ]; do
		c=$(printf '%s' "$text" | cut -c1)
		text=$(printf '%s' "$text" | cut -c2-)
		expect "$w"
		if [ "$c" = ' ' ]; then sh_ input keyevent KEYCODE_SPACE; else sh_ input text "'$c'"; fi
		sleep 1
	done
}
home() { sh_ input keyevent KEYCODE_HOME; sleep 1.5; expect "$LAUNCHER"; }

# take <id> <seconds> <what it films> <actions during the take>
take() {
	id=$1 seconds=$2 what=$3 actions=$4
	echo
	echo "== $id ($seconds s): $what"
	if [ "${CONFIRM:-0}" = 1 ]; then
		printf '   Look at the whole screen: nothing personal? Enter to film, s to skip: '
		read -r answer </dev/tty
		[ "$answer" = s ] && return 0
	fi
	adb shell screenrecord --size 720x720 --bit-rate 12000000 --time-limit "$seconds" "$DIR/$id.mp4" </dev/null &
	rec=$!
	sleep 1
	eval "$actions"
	wait "$rec"
	adb pull "$DIR/$id.mp4" "public/captures/$id.raw.mp4" </dev/null >/dev/null
	# screenrecord writes a frame only when the screen changes and stops at the last one: made
	# constant 30 fps, the last frame held to the take's full length.
	ffmpeg -loglevel error -y -i "public/captures/$id.raw.mp4" -vf "fps=30,tpad=stop_mode=clone:stop_duration=$seconds" -t "$seconds" \
		-c:v libx264 -crf 14 -pix_fmt yuv420p -an "public/captures/$id.mp4" </dev/null
	rm -f "public/captures/$id.raw.mp4"
	echo "   public/captures/$id.mp4"
}

lockscreen() {
	home
	sh_ input keyevent KEYCODE_SLEEP; sleep 2; sh_ input keyevent KEYCODE_WAKEUP; sleep 2
	locked || { echo "   stopped: no lock screen after waking" >&2; exit 1; }
	take lockscreen 10 "the lock screen; at 3 s Daniel's text arrives (texts counter 3 to 4)" \
		'sleep 2; sh_ $SHOWCASE_MESSAGE_CMD'
	# Lifted without a code, as a lock in its grace period is; otherwise it stays locked.
	sh_ wm dismiss-keyguard; sleep 2
	locked && { echo "   the phone stays locked: unlock it by hand, then run the other takes" >&2; exit 1; }
	event_off
}
home_() {
	home
	take home 10 "home screen, then the tray raised to All at 2 s and lowered at 5 s" \
		'sleep 1; expect "$LAUNCHER"; sh_ input swipe 360 670 360 300 250; sleep 2.5; expect "$LAUNCHER"; sh_ input swipe 360 160 360 640 250'
}
sms() {
	# Daniel's text is there (the newest row), his thread opened from the inbox.
	sh_ $SHOWCASE_MESSAGE_CMD >/dev/null
	su_ am force-stop dev.bold7.messages
	sh_ am start -W -n dev.bold7.messages/.MessagesActivity >/dev/null
	key MessagesActivity KEYCODE_DPAD_DOWN
	key MessagesActivity KEYCODE_DPAD_CENTER
	expect ConversationActivity
	sleep 1
	take sms 22 "the answer typed letter by letter, sent, then Daniel's reply" \
		'type_ ConversationActivity "$ANSWER"; key ConversationActivity KEYCODE_ENTER; sleep 1; sh_ $SHOWCASE_REPLY_CMD'
	su_ am force-stop dev.bold7.messages
	event_off
}
call() {
	su_ am force-stop dev.bold7.phone
	su_ am start -n dev.bold7.phone/.CallActivity --es dev.bold7.phone.DEMO incoming >/dev/null; sleep 2
	expect dev.bold7.phone
	take call 12 "an incoming call rings, is answered at 4 s, hung up at 9 s (fabricated screen, no call)" \
		'sleep 2.5; su_ am force-stop dev.bold7.phone; su_ am start -n dev.bold7.phone/.CallActivity --es dev.bold7.phone.DEMO call >/dev/null; sleep 5; su_ am force-stop dev.bold7.phone'
}
menu() {
	home
	take menu 8 "Menu, the full menu browsed with the trackpad, then Escape" \
		'key "$LAUNCHER" KEYCODE_MENU; key "$LAUNCHER" KEYCODE_DPAD_DOWN; key "$LAUNCHER" KEYCODE_DPAD_DOWN; key "$LAUNCHER" KEYCODE_DPAD_DOWN; key "$LAUNCHER" KEYCODE_DPAD_UP; key "$LAUNCHER" KEYCODE_ESCAPE'
}
alarm() {
	su_ am force-stop dev.bold7.clock
	sh_ am start -W -n dev.bold7.clock/.ClockActivity >/dev/null
	expect ClockActivity
	take alarm 9 "the showcase's alarm rings at 1 s, Snooze at 6 s" \
		'sh_ $SHOWCASE_ALARM_CMD; sleep 5; key dev.bold7.clock KEYCODE_DPAD_CENTER'
	event_off
	su_ am force-stop dev.bold7.clock
}
search() {
	home
	take search 8 "universal search: letters typed from the home screen, results appear" \
		'type_ dev.bold7.launcher cale'
	key dev.bold7.launcher KEYCODE_ESCAPE
	home
}
switcher() {
	home
	take switcher 9 "Switch Application from the menu, three apps crossed, closed" \
		'key "$LAUNCHER" KEYCODE_MENU; key "$LAUNCHER" KEYCODE_DPAD_UP; key "$LAUNCHER" KEYCODE_ENTER; key "" KEYCODE_DPAD_RIGHT; key "" KEYCODE_DPAD_RIGHT; key "" KEYCODE_DPAD_RIGHT'
	key "" KEYCODE_ESCAPE
	home
}

for t in ${*:-lockscreen home sms call menu alarm search switcher}; do
	case $t in
	home) home_ ;;
	lockscreen | sms | call | menu | alarm | search | switcher) $t ;;
	*) echo "unknown take: $t" >&2; exit 2 ;;
	esac
done
echo
echo "Watch every file in full before rendering (no name, number, message or photo of your own)."
