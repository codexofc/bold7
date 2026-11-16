#!/bin/sh
# Full-resolution stills, in out/stills/, to check titles, the phone and framing: one per shot,
# taken once its caption is up, from the scene starts of TIMELINE in src/Root.tsx (a bar is 60
# frames).
set -eu
cd "$(dirname "$0")"
npx remotion bundle --out-dir out/bundle >/dev/null
# name:first frame:bars, in the order of TIMELINE
start=0
for scene in wake:4 logo:2 home:3 sms:6 call:5 menu:3 alarm:3 search:3 switcher:3 performance:4 install:4 end:3; do
	name=${scene%%:*} bars=${scene##*:}
	at=$((start + bars * 60 * 2 / 3))
	npx remotion still out/bundle Demo "out/stills/$name.png" --frame="$at" --log=error
	start=$((start + bars * 60))
done
