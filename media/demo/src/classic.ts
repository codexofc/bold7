// Front of the BlackBerry Classic (Q20), whose body the Q25 keeps, in mm from the top left corner
// of the body (72.4 x 131 mm), measured on its press photos at about 4.1 px/mm.
import type {Quad} from './homography';

// What photos.json locates in each photo: the black front glass, bounded by its top edge, its two
// side edges (along the steel frame) and the bright line above the row of keys.
export const FACE_MM: Quad = [[1, 1], [71.4, 1], [71.4, 82.5], [1, 82.5]];
// The 3.5 inch square screen: 62.9 mm, centred, its top 16.6 mm under the top of the body.
export const SCREEN_MM: Quad = [[4.75, 16.6], [67.65, 16.6], [67.65, 79.5], [4.75, 79.5]];
