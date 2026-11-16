// node src/homography.test.ts : fails if the corner pin stops landing on its corners.
import assert from 'node:assert/strict';
import {apply, quadToQuad, squareToQuad, type Quad} from './homography.ts';

const q: Quad = [[560, 538], [1570, 478], [1570, 1859], [560, 1847]];
const near = (a: number[], b: number[]) => a.every((v, i) => Math.abs(v - b[i]) < 1e-6);
const unit: Quad = [[0, 0], [1, 0], [1, 1], [0, 1]];
unit.forEach((p, i) => assert.ok(near(apply(squareToQuad(q), p), q[i]), `corner ${i}`));
const from: Quad = [[1, 1], [71.4, 1], [71.4, 82], [1, 82]];
from.forEach((p, i) => assert.ok(near(apply(quadToQuad(from, q), p), q[i]), `quad corner ${i}`));
// A straight line stays straight: the middle of an edge lands on the edge.
const mid = apply(quadToQuad(from, q), [36.2, 1]);
assert.ok(Math.abs((mid[1] - 538) / (mid[0] - 560) - (478 - 538) / (1570 - 560)) < 1e-9);
console.log('homography ok');
