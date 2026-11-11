import { describe, expect, it } from 'vitest';
import { PLATE_PAD_X, PLATE_PAD_Y, launcherLayout, type Measure } from './kioskLauncherLayout';

/** 6 dp per character at 12 sp, scaled with the size: predictable widths. */
const measure: Measure = (text, size) => text.length * (size / 2);

describe('launcherLayout', () => {
  const apps = ['Scanner', 'Notes', 'Inventory management', 'Cam', 'Browser'];

  it('lays the launcher out as the agent does, in dp', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    // Header at the column's padding (24, 28), 20 sp.
    expect(l.header).toMatchObject({ x: 24, y: 28, text: 'MDMesh Kiosk' });
    // Cells of 56 + 24 dp: (360 - 24) / 80 = 4 columns.
    expect(l.columns).toBe(4);
    expect(l.cells).toHaveLength(5);
    expect(l.cells[0].x).toBe(36);
    expect(l.cells[1].x).toBe(116);
    expect(l.cells[4].y).toBeGreaterThan(l.cells[0].y);
  });

  it('wraps the header title only, with 8 / 4 dp padding', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    expect(l.headerPlate).toEqual({
      x: 24 - PLATE_PAD_X,
      y: l.header.y - PLATE_PAD_Y,
      w: l.header.w + 2 * PLATE_PAD_X,
      h: l.header.h + 2 * PLATE_PAD_Y,
    });
    expect(l.header.w).toBe(measure('MDMesh Kiosk', 20, 700));
  });

  it('gives every label of a grid row one plate width: the widest, capped at the cell', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: false }, measure);
    const row0 = l.labelPlates.slice(0, 4);
    const row1 = l.labelPlates.slice(4);
    // 'Inventory management' is ellipsized at the 56 dp label width: the whole row gets 56 + 16, within the 80 dp cell.
    expect(new Set(row0.map((p) => p.w))).toEqual(new Set([56 + 2 * PLATE_PAD_X]));
    expect(row0[0].w).toBeLessThanOrEqual(80);
    // Row 2 has 'Browser' only: 7 chars * 6 + 16.
    expect(row1[0].w).toBe(7 * 6 + 2 * PLATE_PAD_X);
    // Centred on its cell.
    for (let i = 0; i < l.cells.length; i++) {
      const c = l.cells[i];
      const p = l.labelPlates[i];
      expect(p.x + p.w / 2).toBeCloseTo(c.x + c.w / 2, 6);
      expect(p.y + p.h / 2).toBeCloseTo(c.label.y + c.label.h / 2, 6);
    }
  });

  it('puts the Exit button at the bottom centre; its box is its region for the dim', () => {
    const l = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    const e = l.exit!;
    expect(e.button.x + e.button.w / 2).toBeCloseTo(180, 6);
    expect(e.button.y + e.button.h).toBe(780 - 24);
    expect(l.elements.find((x) => x.kind === 'control')!.box).toEqual(e.button);
  });

  it('turning the window changes the grid and moves the plates', () => {
    const portrait = launcherLayout({ w: 360, h: 780 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    const landscape = launcherLayout({ w: 780, h: 360 }, apps, { iconSize: 'SMALL', exitVisible: true }, measure);
    expect(landscape.columns).toBe(9);
    expect(landscape.cells[4].y).toBe(landscape.cells[0].y);
    expect(landscape.exit!.button.y).toBeLessThan(portrait.exit!.button.y);
  });

  it('larger icons make wider cells', () => {
    const l = launcherLayout({ w: 1024, h: 768 }, apps, { iconSize: 'LARGE', exitVisible: false }, measure);
    expect(l.cells[0].w).toBe(96 + 24);
    expect(l.columns).toBe(Math.floor((1024 - 24) / 120));
  });

  it('leaves out what falls below the window', () => {
    const many = Array.from({ length: 60 }, (_, i) => `App ${i}`);
    const l = launcherLayout({ w: 360, h: 300 }, many, { iconSize: 'SMALL', exitVisible: false }, measure);
    for (const c of l.cells) expect(c.y).toBeLessThan(300);
    expect(l.elements.filter((e) => e.id.startsWith('label')).length).toBe(l.cells.length);
  });
});
