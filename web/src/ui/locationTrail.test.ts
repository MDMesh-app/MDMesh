import { describe, expect, it } from 'vitest';
import { arrowSegments, screenAngle, showPoints, type Pt } from './locationTrail';

const pts = (...xy: [number, number][]): Pt[] => xy.map(([x, y]) => ({ x, y }));

describe('screenAngle', () => {
  it('is 0° heading right (east) and turns clockwise, as SVG’s rotate() does', () => {
    expect(screenAngle({ x: 0, y: 0 }, { x: 10, y: 0 })).toBeCloseTo(0);
    expect(screenAngle({ x: 0, y: 0 }, { x: 0, y: 10 })).toBeCloseTo(90); // down the screen (south)
    expect(screenAngle({ x: 0, y: 0 }, { x: -10, y: 0 })).toBeCloseTo(180);
    expect(screenAngle({ x: 0, y: 0 }, { x: 0, y: -10 })).toBeCloseTo(-90); // north
  });
});

describe('arrowSegments', () => {
  it('puts an arrow in the middle of a segment, pointing older → newer', () => {
    expect(arrowSegments(pts([0, 0], [100, 0], [100, 10], [100, 110]), 20, 60)).toEqual([
      { x: 50, y: 0, angle: 0 },
      { x: 100, y: 60, angle: 90 },
    ]);
  });

  it('spreads several along a long segment, never at its ends (the fixes’ dots)', () => {
    expect(arrowSegments(pts([0, 0], [300, 0]), 20, 100).map((a) => a.x)).toEqual([50, 150, 250]);
  });

  it('keeps arrows at least `spacing` apart along the path, so short segments get every few', () => {
    const steps = pts([0, 0], [30, 0], [60, 0], [90, 0], [120, 0], [150, 0], [180, 0]);
    expect(arrowSegments(steps, 20, 70).map((a) => a.x)).toEqual([15, 105]);
  });

  it('draws none on segments too short to hold one (zoomed out), or with one fix', () => {
    expect(arrowSegments(pts([0, 0], [5, 5], [10, 10]), 20, 60)).toEqual([]);
    expect(arrowSegments(pts([0, 0]), 20, 60)).toEqual([]);
    expect(arrowSegments([], 20, 60)).toEqual([]);
  });

  it('with a view, makes only the arrows inside it: a long jump at high zoom stays cheap', () => {
    // A jump of 10 million px (two fixes far apart at zoom 19) seen through a 1000 px window in its middle.
    const jump = pts([0, 0], [10_000_000, 0]);
    const view = { min: { x: 5_000_000, y: -500 }, max: { x: 5_001_000, y: 500 } };
    const a = arrowSegments(jump, 20, 100, view);
    expect(a.length).toBe(10);
    expect(a.every((p) => p.x >= 5_000_000 && p.x <= 5_001_000 && p.angle === 0)).toBe(true);
    // The same places as without the view: they don't move as the map pans.
    expect(a.map((p) => Math.round(p.x))).toEqual(
      Array.from({ length: 10 }, (_, k) => 5_000_050 + k * 100),
    );
  });

  it('with a view, skips segments outside it and keeps the spacing of those before', () => {
    const steps = pts([0, 0], [30, 0], [60, 0], [90, 0], [120, 0], [150, 0], [180, 0]);
    const view = { min: { x: 100, y: -10 }, max: { x: 200, y: 10 } };
    expect(arrowSegments(steps, 20, 70, view).map((a) => a.x)).toEqual([105]);
    expect(arrowSegments(steps, 20, 70, { min: { x: 0, y: 50 }, max: { x: 200, y: 60 } })).toEqual([]);
  });

  it('skips repeated fixes at one spot (no NaN angle)', () => {
    expect(arrowSegments(pts([0, 0], [0, 0], [0, 0]), 0, 0)).toEqual([]);
  });
});

describe('showPoints', () => {
  it('shows every fix’s dot only when fixes sit far enough apart on screen', () => {
    expect(showPoints(pts([0, 0], [20, 0], [40, 0], [60, 0]), 16)).toBe(true);
    expect(showPoints(pts([0, 0], [4, 0], [8, 0], [12, 0]), 16)).toBe(false);
  });

  it('goes by the typical gap: one long jump among tight fixes keeps them hidden', () => {
    expect(showPoints(pts([0, 0], [3, 0], [6, 0], [9, 0], [400, 0]), 16)).toBe(false);
  });

  it('always shows a short trail’s dots (nothing to crowd)', () => {
    expect(showPoints(pts([0, 0], [1, 0]), 16)).toBe(true);
    expect(showPoints(pts([0, 0]), 16)).toBe(true);
  });
});
