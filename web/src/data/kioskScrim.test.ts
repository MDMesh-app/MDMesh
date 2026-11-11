import { readFileSync } from 'node:fs';
import { rowPlates } from './kioskLauncherLayout';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  BUSY_SPAN,
  CELLS_ON_LONG_EDGE,
  CONTROL_MIN,
  DIM_MAX_STEP,
  SCRIM_STEPS,
  TEXT_MIN,
  blendStep,
  boxBounds,
  cellGrid,
  centreCrop,
  contrastRatio,
  imageCells,
  kioskColours,
  kioskLegibility,
  parseColor,
  placement,
  toHex,
  worstRatio,
  type ImageCells,
  type LegibilityElement,
} from './kioskScrim';

// The vectors the agent's implementation is tested against too: the console's preview shows what the device draws.
interface Box { x: number; y: number; w: number; h: number }
interface Vectors {
  version: number;
  steps: number;
  dimMaxStep: number;
  textMin: number;
  controlMin: number;
  busySpan: number;
  geometry: {
    image: { width: number; height: number };
    window: { w: number; h: number };
    mode: 'fill' | 'fit';
    focal: { x: number; y: number };
    expected: { x: number; y: number; w: number; h: number; scale: number };
  }[];
  blends: { over: string; px: string; step: number; expected: string }[];
  rowPlates: {
    name: string;
    centres: number[];
    textWidths: number[];
    cellWidth: number;
    contentLeft: number;
    contentRight: number;
    expected: { x: number; w: number }[];
  }[];
  cellsOnLongEdge: number;
  parsing: { input: string; expected: string | null }[];
  palette: {
    backgroundColor: string | null;
    textColor: string | null;
    expected: { background: string; text: string; secondary: string };
  }[];
  layout: {
    name: string;
    backgroundColor: string | null;
    textColor: string | null;
    image: { width: number; height: number; cellSize: number; cols: number; rows: number; cells?: [string, string][]; pixels?: string[] };
    window: { w: number; h: number };
    mode?: 'fill' | 'fit';
    focal?: { x: number; y: number };
    elements: { id: string; kind: 'text' | 'secondary' | 'control'; row?: string; box: Box }[];
    expected: {
      background: string;
      text: string;
      secondary: string;
      dim: number;
      plates: Record<string, { step: number; ratio: number; ratioWithoutPlate?: number; busy: boolean }>;
    };
  }[];
}
const VECTORS = JSON.parse(
  readFileSync(resolve(import.meta.dirname, '../../../proto/vectors/kiosk-scrim.json'), 'utf8'),
) as Vectors;

describe('kiosk legibility vectors (shared with the agent)', () => {
  it('uses the same constants', () => {
    expect(VECTORS.version).toBe(3.2);
    expect(VECTORS.steps).toBe(SCRIM_STEPS);
    expect(VECTORS.dimMaxStep).toBe(DIM_MAX_STEP);
    expect(VECTORS.textMin).toBe(TEXT_MIN);
    expect(VECTORS.controlMin).toBe(CONTROL_MIN);
    expect(VECTORS.busySpan).toBe(BUSY_SPAN);
    expect(VECTORS.cellsOnLongEdge).toBe(CELLS_ON_LONG_EDGE);
  });

  it.each(VECTORS.parsing.map((p) => [JSON.stringify(p.input), p] as const))('parses %s as the agent does', (_, p) => {
    const c = parseColor(p.input);
    expect(c == null ? null : toHex(c)).toBe(p.expected);
  });

  it.each(VECTORS.palette.map((p) => [`${p.backgroundColor} / ${p.textColor}`, p] as const))('palette %s', (_, p) => {
    const c = kioskColours(p.backgroundColor, p.textColor);
    expect(toHex(c.background)).toBe(p.expected.background);
    expect(toHex(c.text)).toBe(p.expected.text);
    expect(toHex(c.secondary)).toBe(p.expected.secondary);
  });

  it.each(VECTORS.layout.map((v) => [v.name, v] as const))('layout %s', (_, v) => {
    const colours = kioskColours(v.backgroundColor, v.textColor);
    expect(toHex(colours.background)).toBe(v.expected.background);
    expect(cellGrid(v.image.width, v.image.height)).toEqual({ cellSize: v.image.cellSize, cols: v.image.cols, rows: v.image.rows });
    const cells: ImageCells = v.image.cells
      ? { ...v.image, min: v.image.cells.map((c) => parseColor(c[0])!), max: v.image.cells.map((c) => parseColor(c[1])!) }
      : imageCells(
          v.image.pixels!.flatMap((p) => {
            const n = parseInt(p.slice(1), 16);
            return [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255];
          }),
          v.image.width,
          v.image.height,
          colours.background,
        );
    const elements: LegibilityElement[] = v.elements.map((e) => ({ id: e.id, kind: e.kind, row: e.row, box: e.box }));
    const out = kioskLegibility(cells, v.window, colours, elements, { mode: v.mode ?? 'fill', focal: v.focal });
    expect(out.dim).toBe(v.expected.dim);
    for (const [id, want] of Object.entries(v.expected.plates)) {
      const got = out.plates[id];
      expect(got.step, id).toBe(want.step);
      expect(got.busy, id).toBe(want.busy);
      expect(got.ratio).toBeCloseTo(want.ratio, 2);
      if (want.ratioWithoutPlate != null) expect(got.ratioWithoutPlate).toBeCloseTo(want.ratioWithoutPlate, 2);
    }
  });
});

describe('geometry and blending vectors (3.2)', () => {
  it.each(VECTORS.geometry.map((g) => [`${g.image.width}x${g.image.height} in ${g.window.w}x${g.window.h} ${g.mode} @${g.focal.x},${g.focal.y}`, g] as const))(
    '%s',
    (_, g) => {
      const p = placement(g.image.width, g.image.height, g.window.w, g.window.h, g.mode, g.focal);
      expect(p.x).toBeCloseTo(g.expected.x, 6);
      expect(p.y).toBeCloseTo(g.expected.y, 6);
      expect(p.w).toBeCloseTo(g.expected.w, 6);
      expect(p.h).toBeCloseTo(g.expected.h, 6);
      expect(p.scale).toBeCloseTo(g.expected.scale, 9);
    },
  );

  it.each(VECTORS.rowPlates.map((r) => [r.name, r] as const))('row plates %s', (_, r) => {
    const got = rowPlates(r.centres, r.textWidths, r.cellWidth, r.contentLeft, r.contentRight);
    expect(got.length).toBe(r.expected.length);
    got.forEach((g, i) => {
      expect(g.x).toBeCloseTo(r.expected[i].x, 9);
      expect(g.w).toBeCloseTo(r.expected[i].w, 9);
    });
    // In a real grid (icons a cell apart) neighbouring plates are at least 8 dp apart.
    if (r.name.startsWith('seven')) {
      for (let i = 1; i < got.length; i++) expect(got[i].x - (got[i - 1].x + got[i - 1].w)).toBeGreaterThanOrEqual(8 - 1e-9);
    }
  });

  it.each(VECTORS.blends.map((b) => [`${b.over} over ${b.px} at ${b.step}`, b] as const))('blend %s', (_, b) => {
    expect(toHex(blendStep(parseColor(b.over)!, parseColor(b.px)!, b.step))).toBe(b.expected);
  });
});

describe('kiosk legibility building blocks', () => {
  it('contrast matches WCAG references', () => {
    expect(contrastRatio(0x000000, 0xffffff)).toBeCloseTo(21, 5);
    expect(contrastRatio(0x777777, 0xffffff)).toBeCloseTo(4.48, 2);
  });

  it('blends in integer steps, rounding half up', () => {
    expect(blendStep(0x000000, 0xffffff, 0)).toBe(0xffffff);
    expect(blendStep(0x000000, 0xffffff, SCRIM_STEPS)).toBe(0x000000);
    expect(blendStep(0x000000, 0xffffff, 25)).toBe(0x808080);
  });

  it('parses only what Android reads the same way', () => {
    expect(parseColor('#fff')).toBeNull();
    expect(parseColor('teal')).toBe(0x008080);
    expect(parseColor('constructor')).toBeNull();
    expect(parseColor('__proto__')).toBeNull();
    expect(parseColor(undefined)).toBeNull();
  });

  it('never dims more than 30%, and text always reaches 4.5:1 on its plate', () => {
    const colours = kioskColours(null, null);
    for (let v = 0; v < 256; v += 17) {
      const cells = imageCells(new Uint8ClampedArray(40 * 32 * 4).map((_, i) => (i % 4 === 3 ? 255 : v)), 40, 32, colours.background);
      const out = kioskLegibility(cells, { w: 40, h: 32 }, colours, [
        { id: 't', kind: 'text', box: { x: 0, y: 0, w: 40, h: 16 } },
        { id: 'x', kind: 'control', box: { x: 0, y: 16, w: 40, h: 16 } },
      ]);
      expect(out.dim).toBeLessThanOrEqual(DIM_MAX_STEP);
      expect(out.plates.t.ratio).toBeGreaterThanOrEqual(TEXT_MIN);
      expect(out.plates.x.step).toBe(0);
      expect(out.textRatio).toBe(out.plates.t.ratio);
    }
  });

  it('keeps per-channel minimum and maximum per cell, transparent pixels over the background', () => {
    // 4 x 2 px, cells of 1 px... but long edge 240 gives cells of 2 px: one 2 x 2 cell holds a black and a white pixel.
    const iw = 240;
    const ih = 2;
    const rgba = new Uint8ClampedArray(iw * ih * 4);
    for (let i = 0; i < iw * ih; i++) rgba.set([128, 128, 128, 255], i * 4);
    rgba.set([0, 0, 0, 255], 0);
    rgba.set([255, 255, 255, 255], 4);
    rgba.set([255, 0, 0, 0], (iw + 2) * 4); // transparent: the background colour shows
    const cells = imageCells(rgba, iw, ih, 0x102030);
    expect(cells.cellSize).toBe(2);
    expect(cells.cols).toBe(120);
    expect(toHex(cells.min[0])).toBe('#000000');
    expect(toHex(cells.max[0])).toBe('#FFFFFF');
    expect(toHex(cells.min[1])).toBe('#102030');
    expect(toHex(cells.max[2])).toBe('#808080');
  });

  it('reads the cells under a box with a one-cell margin, through the crop', () => {
    const cells: ImageCells = {
      width: 24, height: 16, cellSize: 1, cols: 24, rows: 16,
      // Green holds the row, blue the column.
      min: Array.from({ length: 384 }, (_, i) => (Math.floor(i / 24) << 8) | i % 24),
      max: Array.from({ length: 384 }, (_, i) => (Math.floor(i / 24) << 8) | i % 24),
    };
    // 5 dp per px; the box covers px x 4..12, y 10..12.4: cells 3..12 x 9..13 with the margin.
    const { lo, hi } = boxBounds(cells, { w: 120, h: 80 }, { x: 20, y: 50, w: 40, h: 12 }, 0);
    expect(lo).toBe((9 << 8) | 3);
    expect(hi).toBe((13 << 8) | 12);
  });

  it('bounds every pixel: the worst contrast is at the nearer bound, 1 inside', () => {
    expect(worstRatio(0xffffff, 0x000000, 0x202020)).toBeCloseTo(contrastRatio(0xffffff, 0x212121), 10);
    expect(worstRatio(0x000000, 0xd0d0d0, 0xf0f0f0)).toBeCloseTo(contrastRatio(0x000000, 0xcfcfcf), 10);
    expect(worstRatio(0x808080, 0x000000, 0xffffff)).toBe(1);
  });
});

describe('centre crop (CENTER_CROP), both orientations', () => {
  const cases: [string, number, number][] = [
    ['landscape photo', 4000, 3000],
    ['portrait photo', 3000, 4000],
    ['square', 2000, 2000],
    ['very wide panorama', 12000, 1000],
    ['very tall strip', 800, 9000],
  ];
  const windows: [string, number, number][] = [
    ['phone portrait', 360, 780],
    ['phone landscape', 780, 360],
    ['tablet 4:3 landscape', 1024, 768],
    ['tablet 4:3 portrait', 768, 1024],
    ['tablet 16:10 portrait', 800, 1280],
    ['tablet 16:10 landscape', 1280, 800],
  ];
  it.each(cases.flatMap(([n, iw, ih]) => windows.map(([wn, w, h]) => [`${n} on ${wn}`, iw, ih, w, h] as const)))(
    '%s: fills the window, centred, in its proportions',
    (_, iw, ih, w, h) => {
      const c = centreCrop(iw, ih, w, h);
      // The crop has the window's proportions, lies inside the image, is centred, and is as large as it can be.
      expect(c.w / c.h).toBeCloseTo(w / h, 6);
      expect(c.x).toBeGreaterThanOrEqual(-1e-9);
      expect(c.y).toBeGreaterThanOrEqual(-1e-9);
      expect(c.x + c.w).toBeLessThanOrEqual(iw + 1e-9);
      expect(c.y + c.h).toBeLessThanOrEqual(ih + 1e-9);
      expect(c.x).toBeCloseTo((iw - c.w) / 2, 6);
      expect(c.y).toBeCloseTo((ih - c.h) / 2, 6);
      expect(Math.abs(c.w - iw) < 1e-6 || Math.abs(c.h - ih) < 1e-6).toBe(true);
    },
  );

  it('rotating the window re-crops: a landscape photo shows all of its width only in landscape', () => {
    const portrait = centreCrop(4000, 3000, 360, 780);
    const landscape = centreCrop(4000, 3000, 780, 360);
    expect(portrait.h).toBe(3000);
    expect(portrait.w).toBeCloseTo((3000 * 360) / 780, 6);
    expect(landscape.w).toBe(4000);
    expect(landscape.h).toBeCloseTo((4000 * 360) / 780, 6);
  });
});
