import type {Framing, PhotoId, Side} from './Photo';
import type {ScreenSource} from './ScreenMedia';

// The shots on a view of the turntable, in order. `bars`: length in bars of the music (2 s each).
// Framings are in pixels of the 1080 x 1080 renders (prepare-assets.sh): v1 is the front, v2 to v4
// turn it 10 degrees at a time to face the right of the frame, v36 to v33 to face its left.
// `capture` is the take of capture.sh shown on the screen (public/captures/<capture>.mp4), from
// `trim` seconds, at `rate` (the text is typed one key a second on the phone). The phone faces
// the caption: on the right of the frame it is turned to the left, and the other way round.
export type Shot = ScreenSource & {
	id: string;
	bars: number;
	photo: PhotoId;
	side: Side; // where the phone sits; the caption takes the other half
	from: Framing;
	to: Framing;
	tilt?: [number, number];
	wake?: number;
	turn?: {views: PhotoId[]; frames: number};
	kicker: string;
	title: string;
	text: string;
};

export const WAKE: Shot = {
	id: 'wake', bars: 4, photo: 'v1', side: 'right', from: {x: 560, y: 545, h: 1180}, to: {x: 560, y: 520, h: 1020}, wake: 84,
	// Half a turn, from the back to the front, before the screen wakes up.
	turn: {views: ['v19', 'v20', 'v21', 'v22', 'v23', 'v24', 'v25', 'v26', 'v27', 'v28', 'v29', 'v30', 'v31', 'v32', 'v33', 'v34', 'v35', 'v36'], frames: 76},
	capture: 'lockscreen', todo: 'Lock screen: a message arrives',
	kicker: 'Lock screen', title: 'Wake it up.', text: 'A message arrives on the OS 7 lock screen.',
};

export const SHOTS: Shot[] = [
	{
		id: 'home', bars: 3, photo: 'v35', side: 'right', from: {x: 560, y: 440, h: 900}, to: {x: 560, y: 370, h: 780},
		capture: 'home', stills: ['home1', 'home_max'], todo: 'Home screen, the tray',
		kicker: 'Home screen', title: 'Right where you left it.', text: 'The banner, the tray and every view.',
	},
	{
		id: 'sms', bars: 6, photo: 'v2', side: 'left', from: {x: 560, y: 540, h: 1100}, to: {x: 560, y: 470, h: 960},
		capture: 'sms', rate: 1.8, todo: 'A text typed, sent, answered', typing: 'See you at 7!',
		kicker: 'Messages', title: 'Built for typing.', text: 'Real keys, OS 7 word prediction, and the reply right there.',
	},
	{
		id: 'call', bars: 5, photo: 'v34', side: 'right', from: {x: 560, y: 560, h: 1120}, to: {x: 560, y: 470, h: 940},
		capture: 'call', todo: 'An incoming call, answered, hung up',
		kicker: 'Phone', title: 'Pick up. Hang up.', text: 'The OS 7 call screen, on the Call and End keys.',
	},
	{
		id: 'menu', bars: 3, photo: 'v3', side: 'left', from: {x: 560, y: 640, h: 820}, to: {x: 560, y: 620, h: 700},
		capture: 'menu', trim: 0.5, stills: ['home_menu', 'clk_menu'], todo: 'A menu, browsed with the trackpad',
		kicker: 'Trackpad', title: 'Every menu under your thumb.', text: 'The full menu and the short menu, a click away.',
	},
	{
		id: 'alarm', bars: 3, photo: 'v36', side: 'right', from: {x: 560, y: 410, h: 840}, to: {x: 560, y: 520, h: 1060},
		capture: 'alarm', trim: 1, stills: ['clk1'], todo: 'The alarm rings',
		kicker: 'Clock', title: 'Wake up to OS 7.', text: 'The alarm, the bedside clock, the snooze.',
	},
	{
		id: 'search', bars: 3, photo: 'v4', side: 'left', from: {x: 565, y: 430, h: 900}, to: {x: 565, y: 370, h: 780},
		capture: 'search', trim: 1, todo: 'Universal search', typing: 'cale',
		kicker: 'Universal Search', title: 'Just start typing.', text: 'Apps, contacts, messages and settings, from anywhere.',
	},
	{
		id: 'switcher', bars: 3, photo: 'v33', side: 'right', from: {x: 560, y: 560, h: 1120}, to: {x: 560, y: 500, h: 1000},
		capture: 'switcher', trim: 3, stills: ['switcher', 'sw2', 'sw3'], todo: 'Switch Application',
		kicker: 'Switch Application', title: 'Hold Menu. Jump anywhere.', text: 'Every running app, one key away.',
	},
];
