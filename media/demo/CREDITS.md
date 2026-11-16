# Credits of the Bold7 demo video

Where everything the video shows or plays comes from.

## In the repository

| Item | Source | License |
|---|---|---|
| Bold7 logo (`public/logo/bold7.svg`) | a copy of `docs/logo/bold7.svg` of this repository, an original drawing | the repository's |
| Barlow Semi Condensed Regular, Medium and Bold (`public/fonts/`) | The Barlow Project Authors; Bold copied from `docs/logo/fonts/`, Regular and Medium from the google/fonts repository (`ofl/barlowsemicondensed/`) | SIL Open Font License 1.1 (`public/fonts/OFL.txt`) |
| Screen corners in each view (`data/photos.json`) | measured on the renders below, with `scripts/tour-corners.mjs` of the Bold7 site | the repository's |

## Images of the BlackBerry Classic (Q20), the Q25's template

Downloaded by `prepare-assets.sh` from their original address, checked by sha256, never committed. Each one is credited on the end card.

| Id | Source | Author | License | Original | What I changed |
|---|---|---|---|---|---|
| `v1` to `v4`, `v19` to `v36` | the 360 degree turntable of a free BlackBerry Classic 3D model on TurboSquid: 36 renders, one every 10 degrees, `https://p.turbosquid.com/ts-thumb/N6/9KKMrt/ByXtBj6h/bbc/jpg/1421094817/1920x1080/turn_fit_q99/8ef18607d02d2fe86f09c48d2e7a6810a51dd01a/bbc-<n>.jpg` (n from 1 to 36; the sha256 of each one is in `prepare-assets.sh`) | not readable: the model's page sits behind a bot check, so it is credited "TurboSquid free model" | a free model; its owner confirmed it is free and that Bold7 may use it | 1080 x 1080 renders on a grey studio backdrop; the screen shows the BlackBerry 10 home screen | the screen painted over in dark glass, then always covered by a Bold7 screen (four corners measured in each view, `data/photos.json`); phone cut out (Vision mask), backdrop blurred and darkened; the front and three-quarter views carry the shots, the half turn from the back (`v19` to `v36`, through the profile `v28`) opens the video |
| `keyboard` | https://unsplash.com/photos/black-and-silver-blackberry-qwerty-phone-FPmk3wxfu40 (file `https://images.unsplash.com/photo-1582816836858-39618651bc17`, sha256 `55163d50…bac1bfa29c`) | Randy Lu | Unsplash License (commercial use and changes allowed, no attribution required; credited all the same), https://unsplash.com/license | 6000 x 4000, at an angle, screen on | blurred, nearly black backdrop of the Performance and Installation cards, no inlay |

The same renders turn on the home page of the Bold7 site, credited there in the footer.

**No longer used**: the two photos by Szeremeta on Wikimedia Commons (CC BY 4.0, `File:Blackberry_Classic,_December_2025_(1).jpg` and `(2).jpg`), which carried the shots before the turntable; they are neither downloaded nor shown any more, so they leave the end card. **Left out**: BlackBerry's press photos and those of GSMArena, PhoneArena or other sites without an explicit free license; on Wikimedia Commons, `File:Blackberry_Q20_smartphone.jpg` (Nowforever) and `File:BlackBerry_Classic.png` (Kt38138), under **CC BY-SA 4.0**, since a video showing them would itself have to be published under CC BY-SA.

## Outside the repository

| Item | Source | License, rights |
|---|---|---|
| Music, `public/music/track.mp3` | "Floating Cities", Kevin MacLeod, incompetech.com (`https://incompetech.com/music/royalty-free/mp3-royaltyfree/Floating%20Cities.mp3`, sha256 `e7365652…ef2ddd`), 120 bpm | Creative Commons Attribution 4.0: commercial use and changes allowed, attribution on the end card |
| Screen recordings, `public/captures/*.mp4` | filmed by `capture.sh` on the Q25 in showcase mode: every name, number, message, call and alarm is made up (`core/showcase`), the carrier shows as "Carrier" | BlackBerry OS 7 artwork extracted from the firmware of my 9900: property of BlackBerry Limited |
| Still screens, `public/private/screens/*.png` | real 720 x 720 screenshots of Bold7 on the Q25 (audit 3 archive), shown only for a shot without a recording; the final render uses none of them | the same |

The BlackBerry logo visible on the renders is the one of the modelled phone; it stays.

The end notice, readable on the last card: "BlackBerry and its visual elements are the property of their respective owners. Bold7 is independent, not affiliated with BlackBerry Limited, and distributes no BlackBerry asset."
