import React from 'react';
import {AbsoluteFill, getStaticFiles, Img, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import bench from '../data/bench.json';
import {PHOTOS, PhotoShot} from './Photo';
import {ScreenMedia} from './ScreenMedia';
import {SHOTS, WAKE, type Shot} from './storyboard';
import {C, clamp, Exit, FadeUp, FONT, Kicker, MaskWords, useRise} from './Text';

// Simplification: provisional address, replace once the public site exists.
export const SITE = 'bold7.example.org';
const LEGAL =
	'BlackBerry and its visual elements are the property of their respective owners. Bold7 is independent, not affiliated with BlackBerry Limited, and distributes no BlackBerry asset.';
const MUSIC_CREDIT = 'Music: “Floating Cities” by Kevin MacLeod (incompetech.com), CC BY 4.0';
// Every image the video shows, credited on the end card.
const PHOTO_CREDIT = [...new Set(Object.values(PHOTOS).map((p) => p.credit))].join('; ');

// Dark backdrop for the cards: a photo of the keyboard, blurred and nearly black.
const Backdrop: React.FC = () => {
	const frame = useCurrentFrame();
	return (
		<AbsoluteFill style={{background: '#040506', overflow: 'hidden'}}>
			<Img src={staticFile('photos/keyboard.jpg')} style={{position: 'absolute', width: 2300, left: -190, top: -170, transform: `scale(${1 + frame / 4000})`, filter: 'blur(14px) brightness(0.22) saturate(0.5)'}} />
			<AbsoluteFill style={{background: 'radial-gradient(ellipse 80% 90% at 55% 45%, rgba(0,0,0,0.1) 0%, rgba(0,0,0,0.8) 100%)'}} />
		</AbsoluteFill>
	);
};

const Caption: React.FC<{shot: Shot; delay?: number}> = ({shot, delay = 0}) => {
	const left = shot.side !== 'left';
	return (
		<AbsoluteFill style={{justifyContent: 'center', alignItems: left ? 'flex-start' : 'flex-end', padding: '0 150px'}}>
			<Exit>
				<div style={{width: 640, textAlign: left ? 'left' : 'right'}}>
					<Kicker delay={delay + 8} align={left ? 'left' : 'right'}>
						{shot.kicker}
					</Kicker>
					<div style={{marginTop: 22}}>
						<MaskWords text={shot.title} size={84} delay={delay + 14} />
					</div>
					<FadeUp delay={delay + 30} style={{marginTop: 22}}>
						{shot.text}
					</FadeUp>
				</div>
			</Exit>
		</AbsoluteFill>
	);
};

// One shot of the scenario: the photo, the screen pinned into it, the caption.
export const PhotoScene: React.FC<{shot: Shot}> = ({shot}) => (
	<AbsoluteFill>
		<PhotoShot photo={shot.photo} side={shot.side} from={shot.from} to={shot.to} tilt={shot.tilt} wake={shot.wake} sweep={shot.wake !== undefined} turn={shot.turn} screen={<ScreenMedia source={shot} />} />
		<Caption shot={shot} delay={shot.wake ?? 0} />
	</AbsoluteFill>
);

export const Wake: React.FC = () => {
	const frame = useCurrentFrame();
	return (
		<AbsoluteFill style={{opacity: interpolate(frame, [0, 30], [0, 1], clamp)}}>
			<PhotoScene shot={WAKE} />
		</AbsoluteFill>
	);
};

// Logo and promise.
export const Logo: React.FC = () => {
	const logo = useRise(4, 18);
	const word = useRise(12);
	return (
		<AbsoluteFill>
			<Backdrop />
			<AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', flexDirection: 'column'}}>
				<Exit>
					<div style={{display: 'flex', alignItems: 'center', gap: 44}}>
						<Img src={staticFile('logo/bold7.svg')} style={{width: 220, height: 220, opacity: Math.min(1, logo * 1.5), transform: `scale(${interpolate(logo, [0, 1], [0.8, 1])})`}} />
						<div style={{clipPath: `inset(0 ${(1 - word) * 100}% 0 0)`, fontFamily: FONT, fontWeight: 700, fontSize: 190, letterSpacing: 2, color: C.text}}>Bold7</div>
					</div>
					<div style={{display: 'flex', justifyContent: 'center', marginTop: 34}}>
						<MaskWords text="BlackBerry OS 7.1, reborn on the Zinwa Q25." size={50} weight={500} color={C.dim} delay={26} stagger={2} />
					</div>
				</Exit>
			</AbsoluteFill>
		</AbsoluteFill>
	);
};

type Series = {id: string; label: string; placeholder?: boolean};
type Bench = {
	device: string;
	series: Series[];
	metrics: {id: string; label: string; unit: string; values: Record<string, number>}[];
	apps?: {series: Series[]; items: {id: string; label: string; cold_start_ms: Record<string, {median: number}>}[]};
};
const BENCH: Bench = bench;
const PLACEHOLDER = BENCH.series.some((s) => s.placeholder);

// Four tiles: three of the home screen against stock Launcher3, one app against Google's.
type Tile = {label: string; unit: string; rows: {label: string; value: number}[]};
const HOME_TILES = ['swipe_p95', 'swipe_late', 'home_pss'];
const APP_TILE = 'messages';
const homeTile = (id: string): Tile => {
	const m = BENCH.metrics.find((x) => x.id === id);
	if (!m) throw new Error(`data/bench.json: no metric ${id}`);
	return {label: m.label, unit: m.unit, rows: BENCH.series.map((s) => ({label: s.label, value: m.values[s.id]}))};
};
const app = BENCH.apps?.items.find((x) => x.id === APP_TILE);
const TILES: Tile[] = [
	...HOME_TILES.map(homeTile),
	...(app && BENCH.apps
		? [{label: `${app.label} app, cold start`, unit: 'ms', rows: BENCH.apps.series.map((s) => ({label: s.label, value: app.cold_start_ms[s.id].median}))}]
		: []),
];

// Show as many decimals as bench.json gives.
const decimals = (v: number) => (String(v).split('.')[1] ?? '').length;

const Bar: React.FC<{label: string; value: number; max: number; unit: string; color: string; progress: number}> = ({label, value, max, unit, color, progress}) => (
	<div style={{display: 'flex', alignItems: 'center', marginTop: 18}}>
		<div style={{width: 230, fontFamily: FONT, fontWeight: 500, fontSize: 27, color: C.dim}}>{label}</div>
		<div style={{width: (value / max) * 380 * progress, height: 40, borderRadius: 6, background: color}} />
		<div style={{marginLeft: 16, fontFamily: FONT, fontWeight: 700, fontSize: 31, color: C.text, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', opacity: Math.min(1, progress * 3)}}>
			{(value * progress).toFixed(decimals(value))} {unit}
		</div>
	</div>
);

const Metric: React.FC<{t: Tile; delay: number}> = ({t, delay}) => {
	const p = useRise(delay);
	const max = Math.max(...t.rows.map((r) => r.value));
	return (
		<div>
			<MaskWords text={t.label} size={38} delay={delay - 6} stagger={2} />
			{t.rows.map((r, i) => (
				<Bar key={r.label} label={r.label} value={r.value} max={max} unit={t.unit} color={i === 0 ? C.accent : C.android} progress={p} />
			))}
		</div>
	);
};

// Benchmarks from data/bench.json.
export const Performance: React.FC = () => (
	<AbsoluteFill>
		<Backdrop />
		<AbsoluteFill style={{padding: '110px 150px'}}>
			<Exit>
				<div style={{display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between'}}>
					<div>
						<Kicker delay={4}>Performance</Kicker>
						<div style={{marginTop: 18}}>
							<MaskWords text="Fast, on the real thing." size={80} delay={10} />
						</div>
						<FadeUp delay={24} size={30} style={{marginTop: 14}}>
							Measured on a {BENCH.device}. Lower is better.
						</FadeUp>
					</div>
					{PLACEHOLDER ? (
						<FadeUp delay={20}>
							<div style={{border: `3px solid ${C.amber}`, color: C.amber, borderRadius: 10, padding: '14px 24px', fontFamily: FONT, fontWeight: 700, fontSize: 30, letterSpacing: 3}}>
								PLACEHOLDER DATA, NOT MEASURED
							</div>
						</FadeUp>
					) : null}
				</div>
				<div style={{display: 'grid', gridTemplateColumns: '1fr 1fr', columnGap: 90, rowGap: 80, marginTop: 130}}>
					{TILES.map((t, i) => (
						<Metric key={t.label} t={t} delay={30 + i * 8} />
					))}
				</div>
			</Exit>
		</AbsoluteFill>
	</AbsoluteFill>
);

const STEPS = [
	{title: 'Download', text: 'The Bold7 zip, and your own copy of the official Bold 9900 software, into Download.'},
	{title: 'Install', text: 'KernelSU manager, Modules, Install, the zip. Then restart.'},
	{title: 'Unlock', text: 'Bold7 sets itself up. One screen asks before it reads your texts.'},
];

const Step: React.FC<{i: number; delay: number}> = ({i, delay}) => {
	const p = useRise(delay);
	const s = STEPS[i];
	return (
		<div style={{flex: 1, paddingTop: 30, position: 'relative'}}>
			<div style={{position: 'absolute', top: 0, left: 0, right: 0, height: 4, background: C.accent, transform: `scaleX(${p})`, transformOrigin: 'left'}} />
			<div style={{overflow: 'hidden'}}>
				<div style={{fontFamily: FONT, fontWeight: 700, fontSize: 120, lineHeight: 1, color: C.accent, transform: `translateY(${(1 - p) * 100}%)`}}>{i + 1}</div>
			</div>
			<div style={{marginTop: 16}}>
				<MaskWords text={s.title} size={58} delay={delay + 6} />
			</div>
			<FadeUp delay={delay + 14} size={32} style={{marginTop: 12}}>
				{s.text}
			</FadeUp>
		</div>
	);
};

// Installation in three steps.
export const Install: React.FC = () => (
	<AbsoluteFill>
		<Backdrop />
		<AbsoluteFill style={{padding: '130px 150px'}}>
			<Exit>
				<Kicker delay={4}>Install</Kicker>
				<div style={{marginTop: 18}}>
					<MaskWords text="Three steps. All on the phone." size={80} delay={10} />
				</div>
				<div style={{display: 'flex', gap: 56, marginTop: 100}}>
					{STEPS.map((s, i) => (
						<Step key={s.title} i={i} delay={26 + i * 14} />
					))}
				</div>
				<FadeUp delay={80} size={28} color={C.faint} style={{marginTop: 110}}>
					No BlackBerry file ships with Bold7: the look is read from your own copy, on the phone.
				</FadeUp>
			</Exit>
		</AbsoluteFill>
	</AbsoluteFill>
);

// End card over the phone, out of focus.
export const End: React.FC = () => {
	const frame = useCurrentFrame();
	const {durationInFrames} = useVideoConfig();
	const p = useRise(8);
	const word = useRise(16);
	const out = interpolate(frame, [durationInFrames - 30, durationInFrames], [1, 0], clamp);
	const music = getStaticFiles().some((f) => f.name === 'music/track.mp3');
	return (
		<AbsoluteFill style={{background: '#000'}}>
			<AbsoluteFill style={{opacity: out}}>
				<PhotoShot photo="v1" side="center" from={{x: 560, y: 545, h: 1200}} to={{x: 560, y: 545, h: 1120}} blur={10} />
				<AbsoluteFill style={{background: 'rgba(3,4,5,0.6)'}} />
				<AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', flexDirection: 'column', paddingBottom: 120}}>
					<div style={{display: 'flex', alignItems: 'center', gap: 36}}>
						<Img src={staticFile('logo/bold7.svg')} style={{width: 170, height: 170, opacity: Math.min(1, p * 1.5), transform: `scale(${interpolate(p, [0, 1], [0.85, 1])})`}} />
						<div style={{clipPath: `inset(0 ${(1 - word) * 100}% 0 0)`, fontFamily: FONT, fontWeight: 700, fontSize: 146, letterSpacing: 2, color: C.text}}>Bold7</div>
					</div>
					<FadeUp delay={30} size={50} color={C.accent} style={{fontWeight: 500, letterSpacing: 2, marginTop: 44}}>
						{SITE}
					</FadeUp>
				</AbsoluteFill>
				<AbsoluteFill style={{justifyContent: 'flex-end', alignItems: 'center', paddingBottom: 56}}>
					<FadeUp delay={44} size={28} color="#b3bcc6" style={{width: 1340, textAlign: 'center', lineHeight: 1.4}}>
						{LEGAL}
					</FadeUp>
					<FadeUp delay={52} size={22} color={C.faint} style={{marginTop: 14, textAlign: 'center', lineHeight: 1.5}}>
						{PHOTO_CREDIT}
						{music ? <br /> : null}
						{music ? MUSIC_CREDIT : null}
					</FadeUp>
				</AbsoluteFill>
			</AbsoluteFill>
		</AbsoluteFill>
	);
};

export {SHOTS};
