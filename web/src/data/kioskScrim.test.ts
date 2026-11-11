import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  ALLOWANCE_PERCENT,
  SCRIM_STEPS,
  TEXT_MIN,
  blendStep,
  centreCrop,
  contrastRatio,
  kioskColours,
  parseColor,
  sampleGrid,
  scrimFor,
  toHex,
} from './kioskScrim';

// The vectors the agent's implementation is tested against too: the console's preview shows what the device draws.
interface Vectors {
  steps: number;
  textMin: number;
  allowancePercent: number;
  palette: { backgroundColor: string | null; textColor: string | null; expected: { text: string; secondary: string } }[];
  scrim: {
    name: string;
    backgroundColor: string | null;
    textColor: string | null;
    samples: string[];
    expected: {
      text: string;
      secondary: string;
      scrim: string;
      step: number;
      alpha: number;
      ratioWithoutScrim: number | null;
      ratio: number | null;
    };
  }[];
}
const VECTORS = JSON.parse(
  readFileSync(resolve(import.meta.dirname, '../../../proto/vectors/kiosk-scrim.json'), 'utf8'),
) as Vectors;

describe('kiosk scrim vectors (shared with the agent)', () => {
  it('uses the same constants', () => {
    expect(VECTORS.steps).toBe(SCRIM_STEPS);
    expect(VECTORS.textMin).toBe(TEXT_MIN);
    expect(VECTORS.allowancePercent).toBe(ALLOWANCE_PERCENT);
  });

  it.each(VECTORS.palette.map((p) => [`${p.backgroundColor} / ${p.textColor}`, p] as const))(
    'palette %s',
    (_, p) => {
      const c = kioskColours(p.backgroundColor, p.textColor);
      expect(toHex(c.text)).toBe(p.expected.text);
      expect(toHex(c.secondary)).toBe(p.expected.secondary);
    },
  );

  it.each(VECTORS.scrim.map((v) => [v.name, v] as const))('scrim %s', (_, v) => {
    const colours = kioskColours(v.backgroundColor, v.textColor);
    expect(toHex(colours.text)).toBe(v.expected.text);
    expect(toHex(colours.secondary)).toBe(v.expected.secondary);
    expect(toHex(colours.background)).toBe(v.expected.scrim);
    const s = scrimFor(v.samples.map((x) => parseColor(x)!), colours);
    expect(s.step).toBe(v.expected.step);
    expect(s.alpha).toBe(v.expected.alpha);
    if (v.expected.ratio == null) {
      expect(s.ratio).toBe(Infinity);
    } else {
      expect(s.ratio).toBeCloseTo(v.expected.ratio, 2);
      expect(s.ratioWithoutScrim).toBeCloseTo(v.expected.ratioWithoutScrim!, 2);
      expect(s.ratio).toBeGreaterThanOrEqual(TEXT_MIN);
    }
  });
});

describe('kiosk scrim building blocks', () => {
  it('contrast matches WCAG references', () => {
    expect(contrastRatio(0x000000, 0xffffff)).toBeCloseTo(21, 5);
    expect(contrastRatio(0x777777, 0xffffff)).toBeCloseTo(4.48, 2);
    expect(contrastRatio(0x123456, 0x123456)).toBe(1);
  });

  it('blends in integer steps, rounding half up', () => {
    expect(blendStep(0x000000, 0xffffff, 0)).toBe(0xffffff);
    expect(blendStep(0x000000, 0xffffff, SCRIM_STEPS)).toBe(0x000000);
    // 255 * 25 / 50 = 127.5 -> 128
    expect(blendStep(0x000000, 0xffffff, 25)).toBe(0x808080);
  });

  it('parses the colour forms a configuration may hold', () => {
    expect(parseColor('#fff')).toBe(0xffffff);
    expect(parseColor('#0F1115')).toBe(0x0f1115);
    expect(parseColor('#800F1115')).toBe(0x0f1115);
    expect(parseColor('teal')).toBeNull();
    expect(parseColor(undefined)).toBeNull();
  });

  it('never needs more than the solid colour, for any single sample', () => {
    const colours = kioskColours('#3A3A3A', '#FFFFFF');
    for (let v = 0; v < 256; v += 15) {
      const s = scrimFor([(v << 16) | (v << 8) | v], colours);
      expect(s.ratio).toBeGreaterThanOrEqual(TEXT_MIN);
      expect(s.step).toBeLessThanOrEqual(SCRIM_STEPS);
    }
  });

  it('crops the centre like CENTER_CROP', () => {
    // A 4000 x 3000 landscape photo on a 9 x 19.5 portrait phone: full height, a centred slice of the width.
    const c = centreCrop(4000, 3000, 9, 19.5);
    expect(c.h).toBe(3000);
    expect(c.w).toBeCloseTo((3000 * 9) / 19.5, 6);
    expect(c.x).toBeCloseTo((4000 - c.w) / 2, 6);
    expect(c.y).toBe(0);
    // Same aspect: the whole image.
    expect(centreCrop(1600, 1000, 16, 10)).toEqual({ x: 0, y: 0, w: 1600, h: 1000 });
  });

  it('samples 48 along the long edge', () => {
    expect(sampleGrid(1384.6, 3000)).toEqual({ cols: 22, rows: 48 });
    expect(sampleGrid(4000, 3000)).toEqual({ cols: 48, rows: 36 });
    expect(sampleGrid(10000, 1)).toEqual({ cols: 48, rows: 1 });
  });
});
