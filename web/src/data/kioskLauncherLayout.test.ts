import { describe, expect, it } from 'vitest';
import { PLATE_GAP, PLATE_PAD_X, PLATE_PAD_Y, launcherLayout, rowPlates, type Measure } from './kioskLauncherLayout';

/** Half the size per character: 7 dp per character at 14 sp. */
const measure: Measure = (text, size) => text.length * (size / 2);

describe('labels (docs/kiosk-background-scrim.md, Label lines)', () => {
  const phone = (names: string[]) => launcherLayout({ w: 360, h: 780 }, names, { iconSize: 'SMALL', exitVisible: true }, measure);
  it('breaks between words only, never inside one', () => {
    // Text box 86 - 24 = 62 dp, 7 dp per character.
    const l = phone(['Signage Player', 'Timesheets', 'Maintenance Requests', 'Point-of-Sale', 'Supercalifragilistic Expialidocious']);
    expect(l.cells[0].lines).toEqual(['Signage', 'Player']);
    expect(l.cells[1].lines).toEqual(['Timeshe…']);
    expect(l.cells[2].lines).toEqual(['Mainten…', 'Requests']);
    expect(l.cells[3].lines).toEqual(['Point-', 'of-Sale']);
    expect(l.cells[4].lines).toEqual(['Superca…', 'Expiali…']);
    for (const c of l.cells) for (const line of c.lines) expect(measure(line, 14, 400)).toBeLessThanOrEqual(c.label.w);
  });
  it('gives each label one direction, from its first strong character, for every line', () => {
    const he = String.fromCodePoint(0x5e0, 0x5d9, 0x5d4, 0x5d5, 0x5dc);
    const l = phone([`${he} POS Terminal`, 'POS Terminal']);
    expect(l.cells[0].dir).toBe('rtl');
    expect(l.cells[0].lines.length).toBe(2);
    expect(l.cells[1].dir).toBe('ltr');
  });
  it('is the same at every scale: it works in dp', () => {
    expect(phone(['Signage Player Pro']).cells[0].lines).toEqual(phone(['Signage Player Pro']).cells[0].lines);
  });
});

describe('rowPlates', () => {
  it('centres under the icon, capped at the cell less the gap, clamped to the content edges', () => {
    expect(rowPlates([50, 130], [20, 30], 80, 10, 170)).toEqual([{ x: 27, w: 46 }, { x: 107, w: 46 }]);
    // Too wide: capped at 72; the first is clamped right to the content edge, the last left.
    expect(rowPlates([40, 120], [200, 10], 80, 10, 170)).toEqual([{ x: 10, w: 72 }, { x: 84, w: 72 }]);
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

  it('gives every label of a grid row one plate width: the widest line, at most the cell less 8 dp', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    const row0 = l.labelPlates.slice(0, 4);
    expect(new Set(row0.map((p) => p.w)).size).toBe(1);
    expect(row0[0].w).toBeLessThanOrEqual(86 - PLATE_GAP);
    expect(l.labelPlates[4].w).toBe(7 * 7 + 2 * PLATE_PAD_X);
    for (let i = 0; i < l.cells.length; i++) {
      const c = l.cells[i];
      const p = l.labelPlates[i];
      expect(p.x + p.w / 2).toBeCloseTo(c.icon.x + c.icon.w / 2, 6);
      expect(p.y).toBe(c.label.y - PLATE_PAD_Y);
      expect(p.h).toBe(c.label.h + 2 * PLATE_PAD_Y);
      // The text box is the plate less its padding.
      expect(c.label.w).toBe(86 - PLATE_GAP - 2 * PLATE_PAD_X);
    }
  });

  it('keeps at least 8 dp between neighbouring plates in a 7-column landscape row with one long label', () => {
    const row = ['Scanner', 'Notes', 'Inventory management tool', 'Maps', 'Mail', 'Docs', 'Chat'];
    const l = launcherLayout({ w: 640, h: 360 }, row, { iconSize: 'SMALL', exitVisible: false }, measure);
    expect(l.columns).toBe(7);
    const plates = l.labelPlates.slice(0, 7);
    expect(new Set(plates.map((p) => p.w)).size).toBe(1);
    for (let i = 1; i < 7; i++) expect(plates[i].x - (plates[i - 1].x + plates[i - 1].w)).toBeGreaterThanOrEqual(PLATE_GAP - 1e-9);
    const left = l.cells[0].x;
    const right = l.cells[6].x + l.cells[6].w;
    for (const p of plates) {
      expect(p.x).toBeGreaterThanOrEqual(left);
      expect(p.x + p.w).toBeLessThanOrEqual(right);
    }
    expect(l.cells[2].lines.length).toBe(2);
  });

  it('groups the labels of each grid row, for one plate step per row', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    const rows = l.elements.filter((e) => e.id.startsWith('label')).map((e) => e.row);
    expect(rows).toEqual(['row-0', 'row-0', 'row-0', 'row-0', 'row-1']);
    expect(l.elements.find((e) => e.id === 'header')!.row).toBeUndefined();
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

  it('ends the scrolling content with room for the Exit button: its 48 dp height + 24 + 8 dp', () => {
    const many = Array.from({ length: 40 }, (_, i) => `App ${i}`);
    const top = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: true }, measure);
    const last = top.cells[top.cells.length - 1];
    expect(top.contentHeight).toBe(last.y + last.h + 80);
    expect(top.maxScroll).toBe(top.contentHeight - 640);
    expect(top.scrollY).toBe(0);
    const end = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: true, scrollY: 1e6 }, measure);
    expect(end.scrollY).toBe(top.maxScroll);
    const endLast = end.cells[end.cells.length - 1];
    const target = end.exit!.button.y - 4; // the 48 dp touch target's top
    expect(target - (endLast.y + endLast.h)).toBe(8);
    for (const p of end.labelPlates) expect(p.y + p.h).toBeLessThanOrEqual(end.exit!.button.y);
    // Without an Exit button the grid ends 24 dp below its last row.
    const none = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: false }, measure);
    expect(none.contentHeight).toBe(last.y + last.h + 24);
    // A grid that fits doesn't scroll.
    expect(launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true, scrollY: 50 }, measure).scrollY).toBe(0);
  });

  it('scrolls everything but the image and the Exit button, and protects only what is on screen, clipped', () => {
    const many = Array.from({ length: 40 }, (_, i) => `App ${i}`);
    const top = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: true }, measure);
    for (const e of top.elements) {
      expect(e.box.y).toBeGreaterThanOrEqual(0);
      expect(e.box.y + e.box.h).toBeLessThanOrEqual(640);
    }
    expect(top.elements.filter((e) => e.id.startsWith('label')).length).toBeLessThan(top.cells.length);
    const mid = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: true, scrollY: 100 }, measure);
    expect(mid.header.y).toBe(top.header.y - 100);
    expect(mid.cells[0].y).toBe(top.cells[0].y - 100);
    expect(mid.labelPlates[0].y).toBe(top.labelPlates[0].y - 100);
    expect(mid.exit).toEqual(top.exit);
    expect(mid.elements.find((e) => e.id === 'header')).toBeUndefined();
    // A plate partly above the window is protected over its visible part.
    const cut = launcherLayout({ w: 360, h: 640 }, many, { iconSize: 'SMALL', exitVisible: true, scrollY: top.labelPlates[0].y + 5 }, measure);
    const p0 = cut.labelPlates[0];
    expect(p0.y).toBe(-5);
    expect(cut.elements.find((e) => e.id === 'label-0')!.box).toEqual({ x: p0.x, y: 0, w: p0.w, h: p0.h - 5 });
  });
});
