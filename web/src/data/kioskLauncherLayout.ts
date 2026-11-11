// The kiosk launcher's layout in dp, as the agent lays it out (KioskLauncherActivity), with the plate geometry of
// docs/kiosk-background-scrim.md (version 3). The console's preview draws from it and computes the dim and the plates
// over exactly these boxes; the agent measures its real views instead.
import type { Box, LegibilityElement } from './kioskScrim';

/** Plate padding: 8 dp left and right, 4 dp top and bottom. */
export const PLATE_PAD_X = 8;
export const PLATE_PAD_Y = 4;
/** Plate corner radius: the console's small radius. */
export const PLATE_RADIUS = 10;

const HEADER_SP = 20;
const LABEL_SP = 12;
const EXIT_SP = 14;
/** Line height of a TextView, from its text size. */
const lineHeight = (sp: number) => Math.round(sp * 1.33);
const ICON: Record<string, number> = { SMALL: 56, MEDIUM: 72, LARGE: 96 };

/** Width in dp of `text` at `size` sp and `weight`. */
export type Measure = (text: string, size: number, weight: number) => number;

export interface LauncherCell {
  x: number;
  y: number;
  w: number;
  h: number;
  name: string;
  icon: Box;
  label: Box;
}

export interface LauncherLayout {
  window: { w: number; h: number };
  header: Box & { text: string; size: number };
  headerPlate: Box;
  columns: number;
  cells: LauncherCell[];
  /** One per cell, same order. */
  labelPlates: Box[];
  labelSize: number;
  /** The Exit button: its own fill, no plate; its box is its region for the dim. */
  exit: { button: Box; size: number } | null;
  /** What kioskLegibility protects: the header, each label, and the Exit button. */
  elements: LegibilityElement[];
}

const pad = (b: Box, x: number, y: number): Box => ({ x: b.x - x, y: b.y - y, w: b.w + 2 * x, h: b.h + 2 * y });

export function launcherLayout(
  window: { w: number; h: number },
  apps: readonly string[],
  opts: { iconSize?: string | null; exitVisible: boolean },
  measure: Measure,
): LauncherLayout {
  const { w: W, h: H } = window;
  const headerText = 'MDMesh Kiosk';
  const header = {
    x: 24,
    y: 28,
    w: Math.min(measure(headerText, HEADER_SP, 700), W - 48),
    h: lineHeight(HEADER_SP),
    text: headerText,
    size: HEADER_SP,
  };
  const headerPlate = pad(header, PLATE_PAD_X, PLATE_PAD_Y);

  const icon = ICON[(opts.iconSize ?? 'SMALL').toUpperCase()] ?? ICON.SMALL;
  const cellW = icon + 24;
  const labelH = lineHeight(LABEL_SP);
  const cellH = 12 + icon + 6 + labelH + 12;
  const columns = Math.max(2, Math.floor((W - 24) / cellW));
  const gridX = 24 + 12;
  const gridY = header.y + header.h + 16;
  const cells: LauncherCell[] = [];
  apps.forEach((name, i) => {
    const x = gridX + (i % columns) * cellW;
    const y = gridY + Math.floor(i / columns) * cellH;
    if (y >= H) return;
    const textW = Math.min(measure(name, LABEL_SP, 400), icon);
    cells.push({
      x,
      y,
      w: cellW,
      h: cellH,
      name,
      icon: { x: x + 12, y: y + 12, w: icon, h: icon },
      label: { x: x + cellW / 2 - textW / 2, y: y + 12 + icon + 6, w: textW, h: labelH },
    });
  });
  // One width per grid row: the widest label plus padding, capped at the cell width; centred on each cell.
  const labelPlates: Box[] = [];
  for (let r = 0; r * columns < cells.length; r++) {
    const row = cells.slice(r * columns, (r + 1) * columns);
    const width = Math.min(Math.max(...row.map((c) => c.label.w)) + 2 * PLATE_PAD_X, cellW);
    for (const c of row) {
      labelPlates.push({ x: c.x + cellW / 2 - width / 2, y: c.label.y - PLATE_PAD_Y, w: width, h: labelH + 2 * PLATE_PAD_Y });
    }
  }

  let exit: LauncherLayout['exit'] = null;
  if (opts.exitVisible) {
    const bw = measure('Exit kiosk', EXIT_SP, 500) + 32;
    const bh = 40;
    const button = { x: W / 2 - bw / 2, y: H - 24 - bh, w: bw, h: bh };
    exit = { button, size: EXIT_SP };
  }

  const elements: LegibilityElement[] = [{ id: 'header', kind: 'text', box: headerPlate }];
  labelPlates.forEach((p, i) => elements.push({ id: `label-${i}`, kind: 'text', box: p }));
  if (exit) elements.push({ id: 'exit', kind: 'control', box: exit.button });
  return { window, header, headerPlate, columns, cells, labelPlates, labelSize: LABEL_SP, exit, elements };
}

/** Text width from the font when the browser can measure it, else an estimate (jsdom). */
export function browserMeasure(): Measure {
  let ctx: OffscreenCanvasRenderingContext2D | null = null;
  try {
    ctx = typeof OffscreenCanvas === 'function' ? new OffscreenCanvas(1, 1).getContext('2d') : null;
  } catch {
    ctx = null;
  }
  return (text, size, weight) => {
    if (!ctx) return Math.ceil(text.length * size * 0.55);
    // Android's system font; the preview draws with the same stack.
    ctx.font = `${weight} ${size}px Roboto, "IBM Plex Sans", system-ui, sans-serif`;
    return Math.ceil(ctx.measureText(text).width);
  };
}
