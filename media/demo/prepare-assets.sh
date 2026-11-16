#!/bin/sh
# Puts the assets the video reads, and that git does not keep, into public/ (CREDITS.md):
#   music/track.mp3       Kevin MacLeod, "Floating Cities", CC BY 4.0
#   photos/v<n>.jpg       views of the 360 degree turntable of a free BlackBerry Classic model on
#                         TurboSquid (1080 x 1080, view n every 10 degrees, 1 is the front),
#                         downloaded and checked by sha256, the render's own screen (BlackBerry
#                         10) painted over in dark glass; with v<n>-cut.png (the phone alone,
#                         Vision's mask, matte.swift) and v<n>-bg.jpg (the backdrop without it,
#                         blurred), for the parallax. The screen corners of data/photos.json are
#                         those of the Bold7 site (data/tour.json), in these pixels.
#   photos/keyboard.jpg   a photo of the Classic's keyboard under the Unsplash License, the
#                         backdrop of the cards
#   private/screens/      real 720x720 screenshots of Bold7 on the Q25, chosen one by one for having
#                         no personal data, from the audit archive (SHOTS=<dir> to read them
#                         elsewhere); they show BlackBerry artwork: never committed
# Needs curl, sips and swift (macOS 14 or later), magick. Idempotent: a view already prepared is
# kept (delete public/photos/ to redo them).
set -eu
cd "$(dirname "$0")"
CACHE=.cache
fetch() { # <url> <file> <sha256>
	if [ -f "$2" ] && [ "$(shasum -a 256 "$2" | cut -d' ' -f1)" = "$3" ]; then return; fi
	mkdir -p "$(dirname "$2")"
	curl -sfL -A "bold7-demo (video credits)" -o "$2.part" "$1"
	if [ "$(shasum -a 256 "$2.part" | cut -d' ' -f1)" != "$3" ]; then
		rm -f "$2.part"; echo "sha256 mismatch: $1" >&2; exit 1
	fi
	mv "$2.part" "$2"; echo "ok $2"
}

fetch "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Floating%20Cities.mp3" public/music/track.mp3 \
	e7365652410d1fbc8a14d87537855d625c1a5fa8b7de8c626f47c13256ef2ddd

# One view of the turntable: the render with its screen in dark glass (the polygon is the
# screen's quad of data/photos.json pushed 1.5 px outwards, or - when the screen is not seen),
# the phone cut out (Vision's mask, its edge pulled in a pixel, its jags rounded off by a blur and
# a steep level), and the studio backdrop without it, blurred.
TURN=https://p.turbosquid.com/ts-thumb/N6/9KKMrt/ByXtBj6h/bbc/jpg/1421094817/1920x1080/turn_fit_q99/8ef18607d02d2fe86f09c48d2e7a6810a51dd01a
view() { # <n> <sha256> <polygon or ->
	id=v$1
	fetch "$TURN/bbc-$1.jpg" "$CACHE/$id.orig" "$2"
	out=public/photos/$id.jpg
	[ -f "$out" ] && [ -f "public/photos/$id-cut.png" ] && [ -f "public/photos/$id-bg.jpg" ] && return
	mkdir -p public/photos
	if [ "$3" = - ]; then cp "$CACHE/$id.orig" "$out"; else magick "$CACHE/$id.orig" -fill '#0b0c0e' -draw "polygon $3" -quality 95 "$out"; fi
	swift matte.swift "$out" "$CACHE/$id-mask.png"
	magick "$out" \( "$CACHE/$id-mask.png" -morphology Erode Disk:1 -blur 0x3 -level 35%,65% -blur 0x0.7 \) -alpha off \
		-compose CopyOpacity -composite PNG32:"public/photos/$id-cut.png"
	magick "$out" \( "$CACHE/$id-mask.png" -morphology Dilate Disk:30 -negate \) -alpha off \
		-compose CopyOpacity -composite -channel RGBA -blur 0x60 +channel -background black -alpha remove \
		-quality 90 "public/photos/$id-bg.jpg"
	echo "ok public/photos/$id"
}

# TurboSquid free model (BlackBerry Classic): the front and three quarters to the right, for the
# shots; the half turn from the back to the front, for the opening.
view 1 c983a1e42a7eb1238eb98701ff5f7ed8ca7afbb9602c38104041d963df858665 '314.5,173.5 802.5,173.5 802.5,660.5 314.5,660.5'
view 2 694f714e0c7e463057869a92cc0422ce97daf580864dd6f9ff7c9d8feb185dda '320.5,167.5 799.5,179.5 799.5,659 320.5,662.5'
view 3 2319018d185e81211a5de4ecb1e9475901a15bbe9270a472f38b070d23fa2cf7 '334.5,161.5 790,185 789.5,657 334.5,664.5'
view 4 d7cbcbe952953fc9bddb510c1baffd5f87ec81897987146b8e9f3686d92ece64 '356.5,156.5 773.5,190.5 773.5,655 355.5,666'
view 19 c87cb7b1c11d56784cee226688ed378711d00ba958ebd5b9be98c7bf46527b15 -
view 20 cc4e11f7335d99680fb9172c5c83c44b0953aa86c8a8abf1878845d2d563e075 -
view 21 2571dc5abed750919ea96a384e5e34c455708d8607048cb615cf65a3439c47f4 -
view 22 09f937b0ce51b9b149cd910c30416657d146db14c63fb6a5fcdbcfd9439104ea -
view 23 34ef411d10ec10241cb547386ef9b2ba520d9aa1bc3c79d4458ea54a0e923996 -
view 24 227840c40cb32750bcf4b35cd5b8951c55202f9108ca67e0090c20d2709e8ed4 -
view 25 9d1a69edcb252147daadde748a8230f55401c2d2844c32ff74d7e41115bac67b -
view 26 df663c7530ee2010ed8e2d4ceeda628c438696c416db485804e007da03276c1a -
view 27 7087c2c31f68c96f81c8a73f72e0f05865398607538f463a5716c4f263eba63b -
view 28 e1091184bcd9dd1cc0efe9b7be4787b3217ccea02527e96d95e0574cc3a47a7a -
view 29 7cee9896d994693c5b15fbc36203ab36a89f8c52562f85935363742d717245f8 '491.5,206 570.5,146 570.5,669.5 491.5,650'
view 30 4c854ac2a02b4e68ef82ec406f60684780749fb0775ec73a4472c52fe389341b '454.5,204.5 616.5,144.5 616.5,670 455,650.5'
view 31 f232829ed71c8d8546eec33cfd0324a551dac4ce5c8bedb374f411e973026970 '421.5,201.5 660.5,146 660.5,669.5 421,651.5'
view 32 e48ccba2cc1c5ca1ca369a9793812538e59e3a92543b4b44f07b1c38e62077c3 '390.5,198.5 700.5,149.5 700.5,669 390.5,652.5'
view 33 57d197a717e9519d8bf3be5b5e84704e0abfd9e50ec35a93c0388ced8c9f2e46 '363.5,194.5 735.5,152.5 735,667.5 363.5,654'
view 34 a587ad786f06787422a9fd60fe6db98bfa6297cf103517357150f7fa53e87757 '342.5,189.5 763.5,158 763.5,666 342.5,655.5'
view 35 9e1238dd904b2d66db3bddf8cda333c651bcc441cecc40a84b37ab379ae6a343 '326.5,185 784.5,163 784.5,664.5 326.5,657'
view 36 d6009b9485511b78413eb27c2d4cd4d0c889eda67e4c258c1a700547d55fd5be '317.5,179 797.5,168 797.5,662.5 316.5,659'
# Randy Lu, Unsplash License: the keyboard from above, a backdrop for the charts and the install.
fetch "https://images.unsplash.com/photo-1582816836858-39618651bc17" "$CACHE/keyboard.orig" \
	55163d50434417c973305f6cc8fb7fad63a4c4935e3b7802cb9ad6bac1bfa29c
[ -f public/photos/keyboard.jpg ] || sips -Z 3200 -s format jpeg -s formatOptions 93 "$CACHE/keyboard.orig" --out public/photos/keyboard.jpg >/dev/null

shots="${SHOTS:-$(git rev-parse --path-format=absolute --git-common-dir)/../build/archive-worktrees/audit-3/audit3}"
# Each name checked by eye; add one only after looking at the whole picture.
for n in home1 home_max home_menu switcher sw2 sw3 clk1 clk_menu; do
	if [ -f "$shots/$n.png" ]; then
		mkdir -p public/private/screens
		cp "$shots/$n.png" public/private/screens/
	else
		echo "no $shots/$n.png" >&2
	fi
done
echo "ok public/private/screens"
