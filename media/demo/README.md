# Bold7 demo video

The Bold7 presentation video, 1920 x 1080 at 30 fps, made with Remotion (React): 360° renders of a free 3D model of the BlackBerry Classic, the Q25's form factor, in 2.5D, with Bold7 filmed on the Q25 on its screen; animated English titles; royalty-free music. Nothing in this folder enters the Android build.

## Files

| Path | Role |
|---|---|
| `src/storyboard.ts` | the shots: view, framing, filmed take, fallback captures, texts, the opening half turn |
| `src/Root.tsx` | the timeline (`TIMELINE`) and the music |
| `src/Scenes.tsx` | wake, logo, one shot per feature, performance, install, end |
| `src/Photo.tsx` | the screen inlay system (below) |
| `src/ScreenMedia.tsx` | what the screen shows: the filmed take, otherwise still captures, otherwise a "TO FILM" card |
| `src/homography.ts`, `src/classic.ts` | the homography and the Classic's face dimensions; `node src/homography.test.ts` checks it |
| `src/Text.tsx` | font and text animations |
| `data/photos.json` | for each image: its size, the four screen corners in pixels, its credit |
| `data/bench.json` | the measurements of the Performance scene |
| `prepare-assets.sh`, `matte.swift` | download and preparation of the views, the keyboard photo, the music and the still screens |
| `capture.sh` | films the storyboard's takes on the Q25, in showcase mode |
| `stills.sh` | one full-resolution image per shot in `out/stills/` |
| `CREDITS.md` | source, author and licence of every image, font and sound |

## The inlay system

1. **Views.** `prepare-assets.sh` downloads 22 of the 36 renders of the 360° turn of a free TurboSquid model (1080 x 1080, one every 10°, `v1` facing), checks their sha256, paints their screen (a BlackBerry 10 home screen) over in dark glass, cuts the phone out with Vision's foreground mask (`matte.swift`, macOS 14 or later) and builds the blurred studio background without it (`<id>-bg.jpg`). These are the same renders as the website's turn.
2. **Screen corners.** `data/photos.json` gives, for each view, the four screen corners (top left, top right, bottom right, bottom left), measured to half a pixel and checked by eye (`null` from the back and in profile). The 720 x 720 content is pinned on these corners with `matrix3d`.
3. **Making the screen belong to the image:** a faint veil, an inner shadow, a slowly moving oblique glass reflection, contrast and saturation slightly lowered.
4. **2.5D:** the cut-out subject with its drop shadow above its blurred, darkened background, which drifts more slowly (parallax); slow zoom and pan between two framings (`from`, `to`); at the opening, a half turn (`turn`) goes from the back (`v19`) to the front through the profile, slowing down, each view cross-fading into the next; on wake, the screen turns on and a light sweep crosses the frame.
5. **Transitions:** 0.5 s cross-fade, cut on the music's bar.

Adding a view: its `view` line in `prepare-assets.sh` (number, sha256, screen widened by 1.5 px or `-`), and its entry in `data/photos.json` (`size`, `screen`, `credit`).

## Storyboard (86 s)

Every cut falls on a bar (120 bpm, 2 s).

| Time | Shot | View | Screen |
|---|---|---|---|
| 0:00 to 0:08 | Wake, "Wake it up." | half turn from the back to the front (`v19` to `v36`, then `v1`), full frame; the screen turns on, light sweep | lock screen, a message arrives |
| 0:08 to 0:12 | Logo, "BlackBerry OS 7.1, reborn on the Zinwa Q25." | | |
| 0:12 to 0:18 | Home screen, "Right where you left it." | three-quarter left (`v35`), top half | home, then the tray |
| 0:18 to 0:30 | Messages, "Built for typing." | nearly facing, to the right (`v2`) | a text typed letter by letter, sent, a reply |
| 0:30 to 0:40 | Phone, "Pick up. Hang up." | three-quarter left (`v34`) | incoming call, answered, hung up |
| 0:40 to 0:46 | Trackpad, "Every menu under your thumb." | three-quarter right (`v3`), key row | a menu browsed with the trackpad |
| 0:46 to 0:52 | Clock, "Wake up to OS 7." | nearly facing (`v36`), pulled back | the alarm rings |
| 0:52 to 0:58 | Universal Search, "Just start typing." | three-quarter right (`v4`) | universal search |
| 0:58 to 1:04 | Switch Application, "Hold Menu. Jump anywhere." | three-quarter left (`v33`) | app switcher |
| 1:04 to 1:12 | Performance | keyboard, blurred | |
| 1:12 to 1:20 | Install, three steps | keyboard, blurred | |
| 1:20 to 1:26 | End: logo, site, legal notice, image and music credits | front (`v1`), blurred | |

Until a take is filmed, the screen shows checked still captures (home, menu, alarm, app switcher) or a "TO FILM" card saying what to film.

## Getting started

```sh
cd media/demo
npm ci                 # dependencies pinned by package-lock.json, in node_modules/
./prepare-assets.sh    # views, keyboard photo, music and still screens (network, sips, swift, magick)
npm run studio         # interactive preview
./stills.sh            # out/stills/*.png
npx remotion render Demo out/bold7-demo.mp4
```

## Filming the takes (`capture.sh`)

`./capture.sh` (or `./capture.sh call sms`), with the Q25 connected; set `ANDROID_SERIAL` when more than one device is attached. The script sets `bold7_showcase` to 1 and clears it on exit together with `bold7_showcase_event`, even on error or ^C. Each take plays its own keys, one per second, each after checking that the expected window is in front (otherwise it stops); `CONFIRM=1` waits for Enter before each take. It films with `screenrecord` at 720 x 720, pulls the file, re-encodes it to a steady 30 fps for the take's length (`screenrecord` only writes a frame when the screen changes), stores it in `public/captures/<take>.mp4` and deletes its files from the phone.

No real call and no real text message: the call is `CallActivity`'s fabricated demo screen (a made-up contact and avatar in showcase mode), and the text is typed in a made-up thread that sends nothing. The arriving message, the reply and the alarm are showcase events (`settings put global bold7_showcase_event message|reply|alarm`, `SHOWCASE_MESSAGE_CMD`, `SHOWCASE_REPLY_CMD`, `SHOWCASE_ALARM_CMD`) that only change what the screens show. Watch every file in full before rendering; shift a take's start with `trim` (seconds) and its speed with `rate` in `src/storyboard.ts` (the text, typed one key per second, plays at 1.8).

## Measurements (`data/bench.json`)

The same file as the website's `data/bench.json`, produced by `tools/bench.py json` from the raw `tools/perf.sh` runs. The Performance scene shows four tiles: `swipe_p95`, `swipe_late` and `home_pss` from `metrics` (Bold7 against stock Launcher3), then the cold start of the `messages` app from `apps.items` (Bold7 against Google's app). A series marked `placeholder` shows "PLACEHOLDER DATA, NOT MEASURED". Each value is shown with as many decimals as the file gives.

## Dependencies

Pinned to exact versions (`package.json`, `package-lock.json`), in `node_modules/`, which git ignores.

| Package | Licence |
|---|---|
| `react`, `react-dom` 19.3.1, `@remotion/fonts`, `@types/react` | MIT |
| `typescript` 5.9.3 | Apache 2.0 |
| `remotion`, `@remotion/cli` 4.0.539 | **Remotion License**: not a free software licence; free for individuals and for companies of up to three people (`node_modules/remotion/LICENSE.md`) |

The rest of the tree is MIT, ISC, BSD or Apache, save a few build tools. Remotion is not MIT, Apache or BSD: it only produces the video and never enters an APK.

## Known limits

- The site address (`SITE` in `src/Scenes.tsx`) is provisional.
- No key-press effect while typing: without a video of hands, nothing would keep it in sync with the screen, so it would not be believable.
- The `switcher` take opens Switch Application through the menu: a long press on Menu sent by `adb` does not open it, while the title says "Hold Menu".
