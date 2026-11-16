import React from 'react';
import {AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import photos from '../data/photos.json';
import {FACE_MM, SCREEN_MM} from './classic';
import {apply, cssCornerPin, quadToQuad, type Quad} from './homography';
import {SCREEN_PX} from './ScreenMedia';
import {clamp} from './Text';

// A view of the phone in 2.5D: its subject cut out over its own blurred background, the screen
// pinned into it by a homography, a glass reflection over the screen, slow moves.

// `screen`: the four corners of the screen, measured in the image (the renders of the turntable);
// `face`: the front glass of the Classic, from which the screen is deduced (photos).
type PhotoData = {size: [number, number]; face: Quad | null; screen: Quad | null; credit: string};
export const PHOTOS = photos.photos as unknown as Record<string, PhotoData>;
for (const [id, p] of Object.entries(PHOTOS)) {
	if (p.size.length !== 2 || (p.face && p.face.length !== 4) || (p.screen && p.screen.length !== 4)) {
		throw new Error(`data/photos.json: ${id}: size [w, h], face and screen four [x, y] corners or null`);
	}
}
export type PhotoId = keyof typeof photos.photos;

// Where the screen is in the image: measured, or SCREEN_MM carried by the projective map that
// takes the front glass of the Classic (FACE_MM) onto the one located in the photo.
const hasScreen = (id: PhotoId) => !!(PHOTOS[id].screen || PHOTOS[id].face);
export const screenQuad = (id: PhotoId): Quad => {
	const {face, screen} = PHOTOS[id];
	if (screen) return screen;
	if (!face) throw new Error(`data/photos.json: ${id} has neither screen nor face`);
	const m = quadToQuad(FACE_MM, face);
	return SCREEN_MM.map((p) => apply(m, p)) as Quad;
};

// A framing: the photo pixel (x, y) put at the shot's anchor, with h photo pixels of height in view.
export type Framing = {x: number; y: number; h: number};
export type Side = 'left' | 'right' | 'center';
const ANCHOR_X: Record<Side, number> = {left: 0.28, right: 0.7, center: 0.5};

type Props = {
	photo: PhotoId;
	side: Side;
	from: Framing;
	to: Framing;
	tilt?: [number, number]; // rotation about the vertical axis, degrees, start and end
	screen?: React.ReactNode; // 720 x 720
	wake?: number; // frame at which the screen lights up (before it, the photo's own dark glass)
	sweep?: boolean; // a band of light across the phone at the start
	blur?: number; // the whole shot out of focus (end card)
	turn?: {views: PhotoId[]; frames: number}; // views shown in turn, slowing down, before `photo`
};

export const PhotoShot: React.FC<Props> = ({photo, side, from, to, tilt = [0, 0], screen, wake, sweep, blur = 0, turn}) => {
	const frame = useCurrentFrame();
	const {width, height, durationInFrames} = useVideoConfig();
	const [W, H] = PHOTOS[photo].size;
	const t = interpolate(frame, [0, durationInFrames], [0, 1], {...clamp, easing: Easing.inOut(Easing.sin)});
	const f = {x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t, h: from.h + (to.h - from.h) * t};
	const s = height / f.h;
	const ax = width * ANCHOR_X[side];
	const ay = height / 2;
	// Background: the blurred plate, darkened, covering the frame, drifting less than the phone.
	const bgScale = Math.max(width / W, height / H) * 1.15;
	const drift = (t - 0.5) * 40;
	const on = wake === undefined ? 1 : interpolate(frame, [wake, wake + 10], [0, 1], clamp);
	const sweepX = interpolate(frame, [6, 60], [-0.6, 1.6], clamp);
	const src = (suffix: string, id: string = photo) => staticFile(`photos/${id}${suffix}`);
	// The turntable: one view after the other, speeding up then slowing down until `photo`, each
	// one fading into the next (10 degrees apart, the turn reads smoother than cut view to view).
	const at = turn && frame < turn.frames ? interpolate(frame, [0, turn.frames], [0, turn.views.length], {...clamp, easing: Easing.inOut(Easing.sin)}) : null;
	const seq = turn ? [...turn.views, photo] : [photo];
	const shown = at === null ? photo : seq[Math.floor(at)];
	const next = at === null ? null : seq[Math.floor(at) + 1];
	return (
		<AbsoluteFill style={{background: '#050607', overflow: 'hidden', filter: blur ? `blur(${blur}px)` : undefined}}>
			<Img
				src={src('-bg.jpg')}
				style={{position: 'absolute', left: '50%', top: '50%', width: W * bgScale, height: H * bgScale, transform: `translate(-50%, -50%) translateX(${-drift}px)`, filter: 'brightness(0.32) saturate(0.6)'}}
			/>
			<AbsoluteFill style={{background: 'radial-gradient(ellipse 75% 85% at 50% 50%, rgba(0,0,0,0) 30%, rgba(0,0,0,0.75) 100%)'}} />
			<AbsoluteFill style={{perspective: 2600, perspectiveOrigin: `${ax}px ${ay}px`}}>
				<div style={{position: 'absolute', inset: 0, transformOrigin: `${ax}px ${ay}px`, transform: `rotateY(${tilt[0] + (tilt[1] - tilt[0]) * t}deg)`}}>
					<div style={{position: 'absolute', left: 0, top: 0, width: W, height: H, transformOrigin: '0 0', transform: `translate(${ax - f.x * s}px, ${ay - f.y * s}px) scale(${s})`}}>
						<Img src={src('-cut.png', shown)} style={{position: 'absolute', width: W, height: H, filter: 'drop-shadow(0 40px 60px rgba(0,0,0,0.65))'}} />
						{next && at !== null ? <Img src={src('-cut.png', next)} style={{position: 'absolute', width: W, height: H, opacity: at % 1}} /> : null}
						{screen && shown === photo && hasScreen(photo) ? <Screen quad={screenQuad(photo)} on={on}>{screen}</Screen> : null}
						{sweep ? (
							<div
								style={{
									position: 'absolute',
									width: W,
									height: H,
									maskImage: `url(${src('-cut.png')})`,
									maskSize: '100% 100%',
									WebkitMaskImage: `url(${src('-cut.png')})`,
									WebkitMaskSize: '100% 100%',
									background: `linear-gradient(105deg, transparent ${(sweepX - 0.25) * 100}%, rgba(255,255,255,0.3) ${sweepX * 100}%, transparent ${(sweepX + 0.25) * 100}%)`,
									mixBlendMode: 'screen',
								}}
							/>
						) : null}
					</div>
				</div>
			</AbsoluteFill>
		</AbsoluteFill>
	);
};

// The screen pinned on its four corners, under a glass reflection and a slight veil, so that it
// sits in the photo instead of on it. Off (on = 0), the photo's own dark glass shows.
const Screen: React.FC<{quad: Quad; on: number; children: React.ReactNode}> = ({quad, on, children}) => {
	const frame = useCurrentFrame();
	const glint = 30 + Math.sin(frame / 50) * 6;
	return (
		<div style={{position: 'absolute', left: 0, top: 0, width: SCREEN_PX, height: SCREEN_PX, transformOrigin: '0 0', transform: cssCornerPin(quad, SCREEN_PX), overflow: 'hidden', background: '#000', opacity: on}}>
			<div style={{position: 'absolute', inset: 0, filter: `brightness(${0.94 + 0.25 * (1 - on)}) contrast(0.97) saturate(0.96)`}}>{children}</div>
			<div style={{position: 'absolute', inset: 0, background: 'rgba(255,255,255,0.025)', boxShadow: 'inset 0 0 24px rgba(0,0,0,0.55)'}} />
			<div
				style={{
					position: 'absolute',
					inset: 0,
					background: `linear-gradient(118deg, rgba(255,255,255,0) ${glint - 14}%, rgba(255,255,255,0.13) ${glint}%, rgba(255,255,255,0.03) ${glint + 12}%, rgba(255,255,255,0) ${glint + 30}%)`,
				}}
			/>
		</div>
	);
};
