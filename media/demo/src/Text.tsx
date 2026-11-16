import {loadFont} from '@remotion/fonts';
import React from 'react';
import {interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

export const FONT = 'Barlow Semi Condensed';
for (const [file, weight] of [
	['Regular', '400'],
	['Medium', '500'],
	['Bold', '700'],
]) {
	loadFont({family: FONT, url: staticFile(`fonts/BarlowSemiCondensed-${file}.ttf`), weight});
}

export const C = {text: '#eef1f4', dim: '#a3acb6', faint: '#7c8691', accent: '#4fb0ff', amber: '#f2b134', android: '#6b747e'};
export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

export const useRise = (delay = 0, damping = 200) => {
	const frame = useCurrentFrame();
	const {fps} = useVideoConfig();
	return spring({frame, fps, delay, config: {damping, mass: 0.9}});
};

// Words rise into view from behind a mask, one after the other.
export const MaskWords: React.FC<{text: string; delay?: number; size: number; weight?: number; color?: string; stagger?: number; tracking?: number}> = ({
	text,
	delay = 0,
	size,
	weight = 700,
	color = C.text,
	stagger = 3,
	tracking = 0,
}) => {
	const frame = useCurrentFrame();
	const {fps} = useVideoConfig();
	return (
		<div style={{fontFamily: FONT, fontWeight: weight, fontSize: size, lineHeight: 1.08, color, letterSpacing: tracking, textWrap: 'balance'}}>
			{text.split(' ').map((word, i) => {
				const p = spring({frame, fps, delay: delay + i * stagger, config: {damping: 200}});
				return (
					<span key={i} style={{display: 'inline-block', overflow: 'hidden', verticalAlign: 'top', paddingBottom: size * 0.12, marginBottom: -size * 0.12}}>
						<span style={{display: 'inline-block', transform: `translateY(${(1 - p) * 110}%)`}}>{word}</span>
						{' '}
					</span>
				);
			})}
		</div>
	);
};

// Accent rule that draws itself, then the label slides out from behind it.
export const Kicker: React.FC<{children: string; delay?: number; align?: 'left' | 'right'}> = ({children, delay = 0, align = 'left'}) => {
	const line = useRise(delay);
	const label = useRise(delay + 6);
	return (
		<div style={{display: 'flex', alignItems: 'center', gap: 18, flexDirection: align === 'left' ? 'row' : 'row-reverse'}}>
			<div style={{width: 56, height: 3, background: C.accent, transform: `scaleX(${line})`, transformOrigin: align}} />
			<div style={{overflow: 'hidden'}}>
				<div
					style={{
						fontFamily: FONT,
						fontWeight: 500,
						fontSize: 26,
						letterSpacing: interpolate(label, [0, 1], [12, 6]),
						color: C.accent,
						textTransform: 'uppercase',
						transform: `translateX(${(1 - label) * (align === 'left' ? -100 : 100)}%)`,
					}}
				>
					{children}
				</div>
			</div>
		</div>
	);
};

// Body text: settles upward out of a slight blur.
export const FadeUp: React.FC<{children: React.ReactNode; delay?: number; size?: number; color?: string; style?: React.CSSProperties}> = ({
	children,
	delay = 0,
	size = 36,
	color = C.dim,
	style,
}) => {
	const p = useRise(delay);
	return (
		<div
			style={{
				fontFamily: FONT,
				fontWeight: 400,
				fontSize: size,
				lineHeight: 1.32,
				color,
				textWrap: 'balance',
				opacity: p,
				transform: `translateY(${(1 - p) * 22}px)`,
				filter: `blur(${(1 - p) * 6}px)`,
				...style,
			}}
		>
			{children}
		</div>
	);
};

// Lifts and fades its content out over the last frames of the enclosing sequence.
export const Exit: React.FC<{children: React.ReactNode; frames?: number}> = ({children, frames = 14}) => {
	const frame = useCurrentFrame();
	const {durationInFrames} = useVideoConfig();
	const out = interpolate(frame, [durationInFrames - frames, durationInFrames], [0, 1], clamp);
	return <div style={{opacity: 1 - out, transform: `translateY(${-out * 18}px)`}}>{children}</div>;
};
