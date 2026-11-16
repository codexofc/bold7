// Projective maps between quadrilaterals (Heckbert's square-to-quad), for the corner pin of the
// screen into a photo. Pure functions, no dependency: also run by node in homography.test.ts.

export type Pt = [number, number];
export type Quad = [Pt, Pt, Pt, Pt]; // top left, top right, bottom right, bottom left
// Row-major 3x3, acting on (x, y, 1).
export type H = [number, number, number, number, number, number, number, number, number];

// Unit square (0,0) (1,0) (1,1) (0,1) to `q`.
export const squareToQuad = (q: Quad): H => {
	const [[x0, y0], [x1, y1], [x2, y2], [x3, y3]] = q;
	const dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
	const dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
	const den = dx1 * dy2 - dx2 * dy1;
	const g = (dx3 * dy2 - dx2 * dy3) / den;
	const h = (dx1 * dy3 - dx3 * dy1) / den;
	return [x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h, 1];
};

const mul = (a: H, b: H): H => {
	const r = new Array(9).fill(0) as H;
	for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) r[i * 3 + j] += a[i * 3 + k] * b[k * 3 + j];
	return r;
};

const inverse = (m: H): H => {
	const [a, b, c, d, e, f, g, h, i] = m;
	const A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
	const det = a * A + b * B + c * C;
	return [A, -(b * i - c * h), b * f - c * e, B, a * i - c * g, -(a * f - c * d), C, -(a * h - b * g), a * e - b * d].map((v) => v / det) as H;
};

// Quad `from` to quad `to`.
export const quadToQuad = (from: Quad, to: Quad): H => mul(squareToQuad(to), inverse(squareToQuad(from)));

export const apply = (m: H, [x, y]: Pt): Pt => {
	const w = m[6] * x + m[7] * y + m[8];
	return [(m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w];
};

// CSS matrix3d that sends an element of `size` x `size` px (transform-origin 0 0) onto `q`.
export const cssCornerPin = (q: Quad, size: number): string => {
	const [a, b, c, d, e, f, g, h] = squareToQuad(q);
	const s = 1 / size;
	return `matrix3d(${[a * s, d * s, 0, g * s, b * s, e * s, 0, h * s, 0, 0, 1, 0, c, f, 0, 1].join(',')})`;
};
