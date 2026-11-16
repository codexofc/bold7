import React from 'react';
import {AbsoluteFill, getStaticFiles, Img, interpolate, OffthreadVideo, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {clamp, FONT} from './Text';

export const SCREEN_PX = 720;

const has = (name: string) => getStaticFiles().some((f) => f.name === name);

// What the phone's screen shows in a shot, 720 x 720, by preference:
// 1. public/captures/<capture>.mp4, filmed on the Q25 by capture.sh, from `trim` seconds, at `rate`;
// 2. `stills`, real screenshots of Bold7 (public/private/screens/, prepare-assets.sh), in turn;
// 3. a plain card that says what is still to film (and types `typing`, if given).
export type ScreenSource = {capture: string; trim?: number; rate?: number; stills?: string[]; todo: string; typing?: string};

export const ScreenMedia: React.FC<{source: ScreenSource}> = ({source}) => {
	const frame = useCurrentFrame();
	const {fps, durationInFrames} = useVideoConfig();
	const capture = `captures/${source.capture}.mp4`;
	if (has(capture)) return <OffthreadVideo src={staticFile(capture)} trimBefore={Math.round((source.trim ?? 0) * fps)} playbackRate={source.rate ?? 1} muted style={{width: SCREEN_PX, height: SCREEN_PX}} />;

	const stills = (source.stills ?? []).map((n) => `private/screens/${n}.png`).filter(has);
	if (stills.length > 0) {
		const step = durationInFrames / stills.length;
		const i = Math.min(stills.length - 1, Math.floor(frame / step));
		return (
			<AbsoluteFill>
				{stills.map((s, k) => (
					<Img
						key={s}
						src={staticFile(s)}
						style={{position: 'absolute', width: SCREEN_PX, height: SCREEN_PX, opacity: k === i ? (i === 0 ? 1 : interpolate(frame - i * step, [0, 10], [0, 1], clamp)) : k === i - 1 ? 1 : 0}}
					/>
				))}
			</AbsoluteFill>
		);
	}

	// Simplification: a stand-in until the take is filmed; it says so on screen.
	const typed = source.typing ? source.typing.slice(0, Math.max(0, Math.floor((frame - 20) / 3))) : '';
	return (
		<AbsoluteFill style={{background: 'linear-gradient(#262b33, #121418)', alignItems: 'center', justifyContent: 'center', flexDirection: 'column', fontFamily: FONT, textAlign: 'center', padding: 60}}>
			<div style={{fontSize: 34, letterSpacing: 6, color: '#8d97a2'}}>TO FILM</div>
			<div style={{fontSize: 52, fontWeight: 700, color: '#e8ecf0', marginTop: 18, lineHeight: 1.15}}>{source.todo}</div>
			{source.typing ? (
				<div style={{marginTop: 50, width: 560, padding: '18px 22px', background: '#f2f4f6', color: '#111', fontSize: 40, textAlign: 'left', minHeight: 50}}>
					{typed}
					<span style={{opacity: frame % 20 < 10 ? 1 : 0}}>|</span>
				</div>
			) : null}
		</AbsoluteFill>
	);
};
