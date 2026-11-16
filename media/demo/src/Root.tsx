import React from 'react';
import {AbsoluteFill, Audio, Composition, getStaticFiles, interpolate, Series, useCurrentFrame} from 'remotion';
import {End, Install, Logo, Performance, PhotoScene, SHOTS, Wake} from './Scenes';
import {clamp} from './Text';

// The storyboard at 30 fps. Each scene fades in over the previous one (XF frames). The music is
// at 120 bpm, a bar every 2 s: with every length a whole number of bars plus XF, each cut lands
// on a bar line.
const XF = 15;
const BAR = 60;
export const TIMELINE: {id: string; bars: number; Scene: React.FC}[] = [
	{id: 'wake', bars: 4, Scene: Wake},
	{id: 'logo', bars: 2, Scene: Logo},
	...SHOTS.map((s) => ({id: s.id, bars: s.bars, Scene: () => <PhotoScene shot={s} />})),
	{id: 'performance', bars: 4, Scene: Performance},
	{id: 'install', bars: 4, Scene: Install},
	{id: 'end', bars: 3, Scene: End},
];
const TOTAL = TIMELINE.reduce((sum, s) => sum + s.bars * BAR, 0);

const FadeIn: React.FC<{children: React.ReactNode}> = ({children}) => {
	const frame = useCurrentFrame();
	return <AbsoluteFill style={{opacity: interpolate(frame, [0, XF], [0, 1], clamp)}}>{children}</AbsoluteFill>;
};

// Music from prepare-assets.sh (CREDITS.md), kept low under the picture, faded in and out.
const Music: React.FC = () => {
	if (!getStaticFiles().some((f) => f.name === 'music/track.mp3')) return null;
	return <Audio src={staticFileOf('music/track.mp3')} volume={(f) => 0.9 * interpolate(f, [0, 45, TOTAL - 90, TOTAL], [0, 1, 1, 0], clamp)} />;
};
const staticFileOf = (name: string) => getStaticFiles().find((f) => f.name === name)!.src;

const Demo: React.FC = () => (
	<AbsoluteFill style={{background: '#000'}}>
		<Series>
			{TIMELINE.map(({id, bars, Scene}, i) => (
				<Series.Sequence key={id} name={id} durationInFrames={bars * BAR + (i === TIMELINE.length - 1 ? 0 : XF)} offset={i === 0 ? 0 : -XF} premountFor={XF}>
					{i === 0 ? <Scene /> : <FadeIn><Scene /></FadeIn>}
				</Series.Sequence>
			))}
		</Series>
		<Music />
	</AbsoluteFill>
);

export const RemotionRoot: React.FC = () => (
	<>
		<Composition id="Demo" component={Demo} durationInFrames={TOTAL} fps={30} width={1920} height={1080} />
	</>
);
