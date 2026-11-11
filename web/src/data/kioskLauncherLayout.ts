// The kiosk launcher's grid screen in dp, as the agent lays it out (agent ui/KioskRenderer, AppGridLayout, KioskViews),
// with the plate geometry of docs/kiosk-background-scrim.md (version 3). The console's preview draws from it and
// computes the dim and the plates over exactly these boxes, so a label is cut where the device cuts it.
import type { Box, LegibilityElement } from './kioskScrim';

/** Plate padding: 8 dp left and right, 4 dp top and bottom. */
export const PLATE_PAD_X = 8;
export const PLATE_PAD_Y = 4;
/** Plate corner radius: the console's small radius. */
export const PLATE_RADIUS = 10;

const TITLE_SP = 20;
const LABEL_SP = 14;
const EXIT_SP = 14;
/** App labels: two lines at the default font size. */
export const LABEL_LINES = 2;
const LABEL_GAP = 8;
const CELL_PAD_H = 8;
const CELL_PAD_V = 12;
const ICON_SIDE_SPACE = 24;
const LABEL_MIN = 80;
const BUTTON_MIN_WIDTH = 120;
const BUTTON_PAD_H = 16;
const BUTTON_HEIGHT = 40;
const BUTTON_INSET = 4;
/** A TextView's line height, from its text size. */
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
  /** The label's text view (cell width less padding, its laid-out lines). */
  label: Box;
  lines: string[];
  /** The widest laid-out line. */
  lineWidth: number;
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
  labelLineHeight: number;
  /**
   * The Exit button, outlined: background-colour fill, 1 dp border and label in the text colour. No plate; its box is
   * its region for the dim, which brings the border to 3:1.
   */
  exit: { button: Box; size: number } | null;
  /** What kioskLegibility protects: the header, each label, and the Exit button. */
  elements: LegibilityElement[];
}

/**
 * `text` in at most `maxLines` lines of `width` dp, broken at spaces (a word wider than a line is broken by
 * character), the last line ended with "…" when the text doesn't fit (TextView's END ellipsize).
 */
export function wrapLines(text: string, width: number, maxLines: number, measure: (s: string) => number): string[] {
  const words = text.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let current = '';
  const pushWord = (word: string) => {
    const candidate = current ? `${current} ${word}` : word;
    if (measure(candidate) <= width) {
      current = candidate;
      return;
    }
    if (current) lines.push(current);
    current = '';
    // A word wider than the line: break it by character.
    let rest = word;
    while (measure(rest) > width && rest.length > 1) {
      let n = rest.length - 1;
      while (n > 1 && measure(rest.slice(0, n)) > width) n--;
      lines.push(rest.slice(0, n));
      rest = rest.slice(n);
    }
    current = rest;
  };
  for (const w of words) pushWord(w);
  if (current) lines.push(current);
  if (lines.length <= maxLines) return lines;
  const kept = lines.slice(0, maxLines);
  let last = `${kept[maxLines - 1]} ${lines.slice(maxLines).join(' ')}`;
  while (last.length > 1 && measure(`${last}…`) > width) last = last.slice(0, -1).trimEnd();
  kept[maxLines - 1] = `${last}…`;
  return kept;
}

export function launcherLayout(
  window: { w: number; h: number },
  apps: readonly string[],
  opts: { iconSize?: string | null; exitVisible: boolean },
  measure: Measure,
): LauncherLayout {
  const { w: W, h: H } = window;
  // values-sw600dp: tablets get the wider gutter.
  const gutter = Math.min(W, H) >= 600 ? 24 : 16;
  const icon = ICON[(opts.iconSize ?? 'SMALL').toUpperCase()] ?? ICON.SMALL;
  const slot = Math.max(icon + ICON_SIDE_SPACE, LABEL_MIN);
  const side = gutter - 8;
  const inner = Math.max(0, W - 2 * side);
  const columns = Math.max(2, Math.floor(inner / slot));
  const cellW = Math.floor(inner / columns);
  const cellLeft = (col: number) => side + Math.floor((inner - cellW * columns) / 2) + col * cellW;
  const iconStart = cellLeft(0) + Math.floor((cellW - icon) / 2);

  const headerText = 'MDMesh Kiosk';
  const header = {
    x: iconStart,
    y: 24,
    w: Math.min(measure(headerText, TITLE_SP, 500), W - 2 * iconStart),
    h: lineHeight(TITLE_SP),
    text: headerText,
    size: TITLE_SP,
  };
  const headerPlate = { x: header.x - PLATE_PAD_X, y: header.y - PLATE_PAD_Y, w: header.w + 2 * PLATE_PAD_X, h: header.h + 2 * PLATE_PAD_Y };

  const labelW = cellW - 2 * CELL_PAD_H;
  const labelLine = lineHeight(LABEL_SP);
  const measureLabel = (s: string) => measure(s, LABEL_SP, 400);
  const laid = apps.map((name) => {
    const lines = wrapLines(name, labelW, LABEL_LINES, measureLabel);
    return { name, lines, lineWidth: Math.min(labelW, Math.max(0, ...lines.map(measureLabel))) };
  });
  const gridY = header.y + header.h + 8;
  const cells: LauncherCell[] = [];
  let rowY = gridY;
  for (let r = 0; r * columns < laid.length; r++) {
    const row = laid.slice(r * columns, (r + 1) * columns);
    const rowH = Math.max(...row.map((a) => CELL_PAD_V + icon + LABEL_GAP + a.lines.length * labelLine + CELL_PAD_V));
    if (rowY >= H) break;
    row.forEach((a, c) => {
      const x = cellLeft(c);
      cells.push({
        x,
        y: rowY,
        w: cellW,
        h: rowH,
        name: a.name,
        icon: { x: x + Math.floor((cellW - icon) / 2), y: rowY + CELL_PAD_V, w: icon, h: icon },
        label: { x: x + CELL_PAD_H, y: rowY + CELL_PAD_V + icon + LABEL_GAP, w: labelW, h: a.lines.length * labelLine },
        lines: a.lines,
        lineWidth: a.lineWidth,
      });
    });
    rowY += rowH;
  }
  // One width per grid row: the widest label line plus padding, capped at the cell width; centred on each cell.
  const labelPlates: Box[] = [];
  for (let r = 0; r * columns < cells.length; r++) {
    const row = cells.slice(r * columns, (r + 1) * columns);
    const width = Math.min(Math.max(...row.map((c) => c.lineWidth)) + 2 * PLATE_PAD_X, cellW);
    for (const c of row) {
      labelPlates.push({ x: c.x + cellW / 2 - width / 2, y: c.label.y - PLATE_PAD_Y, w: width, h: c.label.h + 2 * PLATE_PAD_Y });
    }
  }

  let exit: LauncherLayout['exit'] = null;
  if (opts.exitVisible) {
    const bw = Math.max(BUTTON_MIN_WIDTH, measure('Exit kiosk', EXIT_SP, 500) + 2 * BUTTON_PAD_H);
    // 40 dp visible inside the 48 dp target, 24 dp above the bottom edge.
    const button = { x: W / 2 - bw / 2, y: H - 24 - BUTTON_INSET - BUTTON_HEIGHT, w: bw, h: BUTTON_HEIGHT };
    exit = { button, size: EXIT_SP };
  }

  const elements: LegibilityElement[] = [{ id: 'header', kind: 'text', box: headerPlate }];
  labelPlates.forEach((p, i) => elements.push({ id: `label-${i}`, kind: 'text', row: `row-${Math.floor(i / columns)}`, box: p }));
  if (exit) elements.push({ id: 'exit', kind: 'control', box: exit.button });
  return { window, header, headerPlate, columns, cells, labelPlates, labelSize: LABEL_SP, labelLineHeight: labelLine, exit, elements };
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
