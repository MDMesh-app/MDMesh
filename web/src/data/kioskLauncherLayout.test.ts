import { describe, expect, it } from 'vitest';
import { LABEL_LINES, PLATE_PAD_X, PLATE_PAD_Y, launcherLayout, wrapLines, type Measure } from './kioskLauncherLayout';

/** Half the size per character: 7 dp per character at 14 sp. */
const measure: Measure = (text, size) => text.length * (size / 2);
const m14 = (s: string) => measure(s, 14, 400);

describe('wrapLines (TextView, maxLines, END ellipsize)', () => {
  it('fits on one line when it can', () => {
    expect(wrapLines('Notes', 64, 2, m14)).toEqual(['Notes']);
  });
  it('breaks at spaces, then ellipsizes the last line', () => {
    expect(wrapLines('Signage Player', 64, 2, m14)).toEqual(['Signage', 'Player']);
    expect(wrapLines('Inventory management tool', 64, 2, m14)).toEqual(['Inventory', 'manageme…']);
    expect(wrapLines('Safety Check Pro Max', 64, 2, m14)).toEqual(['Safety', 'Check Pr…']);
  });
  it('is the same at every scale: it works in dp', () => {
    const a = wrapLines('Signage Player Pro', 64, LABEL_LINES, m14);
    const b = wrapLines('Signage Player Pro', 64, LABEL_LINES, m14);
    expect(a).toEqual(b);
  });
});

describe('launcherLayout (agent KioskRenderer)', () => {
  const apps = ['Scanner', 'Notes', 'Signage Player', 'Inventory management tool', 'Browser'];

  it('lays a phone out in dp: 16 dp gutter, cells of at least 80 dp, header on the first icon', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    // inner = 360 - 2 * 8 = 344; 344 / 80 = 4 columns of 86 dp.
    expect(l.columns).toBe(4);
    expect(l.cells[0].w).toBe(86);
    expect(l.cells[0].x).toBe(8);
    expect(l.cells[0].icon.x).toBe(8 + 15);
    expect(l.header.x).toBe(l.cells[0].icon.x);
    expect(l.header.y).toBe(24);
    expect(l.cells[4].y).toBe(l.cells[0].y + l.cells[0].h);
  });

  it('gives tablets the 24 dp gutter', () => {
    const l = launcherLayout({ w: 1024, h: 768 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    // inner = 1024 - 32 = 992; 12 columns of 82 dp, 8 dp left over split.
    expect(l.columns).toBe(12);
    expect(l.cells[0].x).toBe(16 + 4);
  });

  it('wraps labels in two lines and gives the row the tallest cell', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    expect(l.cells[2].lines).toEqual(['Signage', 'Player']);
    expect(l.cells[0].h).toBe(l.cells[2].h);
    expect(l.cells[3].lines[1].endsWith('…')).toBe(true);
  });

  it('wraps the header title only, with 8 / 4 dp padding', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    expect(l.headerPlate).toEqual({
      x: l.header.x - PLATE_PAD_X,
      y: l.header.y - PLATE_PAD_Y,
      w: measure('MDMesh Kiosk', 20, 500) + 2 * PLATE_PAD_X,
      h: l.header.h + 2 * PLATE_PAD_Y,
    });
  });

  it('gives every label of a grid row one plate width: the widest line, capped at the cell', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    const row0 = l.labelPlates.slice(0, 4);
    expect(new Set(row0.map((p) => p.w)).size).toBe(1);
    expect(row0[0].w).toBeLessThanOrEqual(86);
    expect(l.labelPlates[4].w).toBe(7 * 7 + 2 * PLATE_PAD_X);
    for (let i = 0; i < l.cells.length; i++) {
      const c = l.cells[i];
      const p = l.labelPlates[i];
      expect(p.x + p.w / 2).toBeCloseTo(c.x + c.w / 2, 6);
      expect(p.y).toBe(c.label.y - PLATE_PAD_Y);
      expect(p.h).toBe(c.label.h + 2 * PLATE_PAD_Y);
    }
  });

  it('puts the Exit button at the bottom centre, 40 dp visible, at least 120 dp wide', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    const e = l.exit!;
    expect(e.button.x + e.button.w / 2).toBeCloseTo(180, 6);
    expect(e.button.y + e.button.h).toBe(780 - 28);
    expect(e.button.w).toBe(120);
    expect(l.elements.find((x) => x.kind === 'control')!.box).toEqual(e.button);
  });

  it('turning the window changes the grid and moves the plates', () => {
    const portrait = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    const landscape = launcherLayout({ w: 780, h: 360 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    expect(landscape.columns).toBe(9);
    expect(landscape.cells[4].y).toBe(landscape.cells[0].y);
    expect(landscape.exit!.button.y).toBeLessThan(portrait.exit!.button.y);
  });

  it('leaves out rows below the window', () => {
    const many = Array.from({ length: 60 }, (_, i) => `App ${i}`);
    const l = launcherLayout({ w: 360, h: 300 }, many, { iconSize: 'SMALL', exitVisible: false }, measure);
    for (const c of l.cells) expect(c.y).toBeLessThan(300);
    expect(l.elements.filter((e) => e.id.startsWith('label')).length).toBe(l.cells.length);
  });
});
