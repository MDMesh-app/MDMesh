// Kiosk background legibility, the console side (version 3): the image's per-cell colour bounds, a light dim for the
// launcher's controls capped at 30%, and a plate of the kiosk background colour behind each text that needs one (solid
// over texture), so text stays at 4.5:1 or more against every pixel under it. The agent computes the same on the device; both are tested against proto/vectors/kiosk-scrim.json. Rules and
// rationale: docs/kiosk-background-scrim.md.
//
// Colours are opaque sRGB as 0xRRGGBB numbers. Everything here is pure except sampleCrop, which needs a canvas.

/** Dim and plate alphas move in 1/50 steps: 0 (nothing) to 50 (the solid background colour). */
export const SCRIM_STEPS = 50;
/** The dim never goes past 15/50 (30%): plates carry the text, the image stays visible. */
export const DIM_MAX_STEP = 15;
/** WCAG AA for body text; the launcher has no large text. */
export const TEXT_MIN = 4.5;
/** WCAG 1.4.11 for controls (the Exit button). */
export const CONTROL_MIN = 3;
/** Over texture (a channel spanning more than this many levels under a text) a plate is solid. */
export const BUSY_SPAN = 48;
/** The image is kept as this many cells along its long edge, each with its per-channel minimum and maximum. */
export const CELLS_ON_LONG_EDGE = 120;

/** v2 dark `--app` and `--text`, the launcher's colours when the kiosk sets none (KioskPalette on the agent). */
export const DEFAULT_BACKGROUND = 0x0f1115;
export const DEFAULT_TEXT = 0xe7ebf1;
/** v2 light `--text`, the dark-on-light fallback. */
export const LIGHT_TEXT = 0x1b2230;

const channel = (v: number) => {
  const c = v / 255;
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};

/** WCAG relative luminance. */
export function luminance(rgb: number): number {
  return 0.2126 * channel((rgb >> 16) & 255) + 0.7152 * channel((rgb >> 8) & 255) + 0.0722 * channel(rgb & 255);
}

/** WCAG contrast ratio, 1 to 21; order doesn't matter. */
export function contrastRatio(a: number, b: number): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Android's colour names (Color.parseColor), lower case. */
const COLOUR_NAMES: Readonly<Record<string, number>> = {
  black: 0x000000, darkgray: 0x444444, gray: 0x888888, lightgray: 0xcccccc, white: 0xffffff, red: 0xff0000,
  green: 0x00ff00, blue: 0x0000ff, yellow: 0xffff00, cyan: 0x00ffff, magenta: 0xff00ff, aqua: 0x00ffff,
  fuchsia: 0xff00ff, darkgrey: 0x444444, grey: 0x888888, lightgrey: 0xcccccc, lime: 0x00ff00, maroon: 0x800000,
  navy: 0x000080, olive: 0x808000, purple: 0x800080, silver: 0xc0c0c0, teal: 0x008080,
};

/**
 * A stored kiosk colour as the agent reads it (docs/kiosk-background-scrim.md, Parsing): `#RRGGBB`, `#AARRGGBB`
 * (alpha dropped: the launcher draws opaque) or one of Android's colour names, any case. Anything else, `#RGB`, a
 * missing `#` and surrounding spaces included, is null: the default colour, as on the device.
 */
export function parseColor(value: string | null | undefined): number | null {
  if (value == null) return null;
  const hex = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.exec(value);
  if (hex) return parseInt(hex[1].slice(-6), 16);
  const name = value.toLowerCase();
  return Object.prototype.hasOwnProperty.call(COLOUR_NAMES, name) ? COLOUR_NAMES[name] : null;
}

export function toHex(rgb: number): string {
  return `#${rgb.toString(16).padStart(6, '0').toUpperCase()}`;
}

/** `scrim` over `sample` at step/50, per channel, rounded half up, in integers: exactly what the agent computes. */
export function blendStep(scrim: number, sample: number, step: number): number {
  let out = 0;
  for (const shift of [16, 8, 0]) {
    const s = (scrim >> shift) & 255;
    const p = (sample >> shift) & 255;
    out |= Math.floor((s * step + p * (SCRIM_STEPS - step) + SCRIM_STEPS / 2) / SCRIM_STEPS) << shift;
  }
  return out >>> 0;
}

/** KioskPalette.blend: `fg` over `bg` at a float alpha, in 32-bit float arithmetic like the agent's Kotlin. */
function blendFloat(fg: number, bg: number, alpha: number): number {
  const a = Math.fround(alpha);
  const inv = Math.fround(1 - a);
  let out = 0;
  for (const shift of [16, 8, 0]) {
    const f = (fg >> shift) & 255;
    const b = (bg >> shift) & 255;
    const v = Math.fround(Math.fround(f * a) + Math.fround(b * inv));
    out |= Math.floor(Math.fround(v + 0.5)) << shift;
  }
  return out >>> 0;
}

/** The v2 text colour that reads best on `bg`, or black or white when neither reaches 4.5:1. */
export function mostReadableOn(bg: number): number {
  const v2 = contrastRatio(DEFAULT_TEXT, bg) >= contrastRatio(LIGHT_TEXT, bg) ? DEFAULT_TEXT : LIGHT_TEXT;
  if (contrastRatio(v2, bg) >= TEXT_MIN) return v2;
  return contrastRatio(0xffffff, bg) >= contrastRatio(0x000000, bg) ? 0xffffff : 0x000000;
}

export interface KioskColours {
  background: number;
  /** The admin's text colour while it reaches 4.5:1 on the background, else the most readable v2 colour. */
  text: number;
  /** Text at 80% over the background, never under 4.5:1 (then it is `text`). */
  secondary: number;
}

/** The launcher's colours from the configuration's (KioskPalette.of on the agent). */
export function kioskColours(backgroundColor?: string | null, textColor?: string | null): KioskColours {
  const bg = parseColor(backgroundColor) ?? DEFAULT_BACKGROUND;
  const wanted = parseColor(textColor) ?? DEFAULT_TEXT;
  const text = contrastRatio(wanted, bg) >= TEXT_MIN ? wanted : mostReadableOn(bg);
  const blended = blendFloat(text, bg, 0.8);
  const secondary = contrastRatio(blended, bg) >= TEXT_MIN ? blended : text;
  return { background: bg, text, secondary };
}

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** The image as cells: per cell, the per-channel minimum and maximum of its pixels over the background colour. */
export interface ImageCells {
  width: number;
  height: number;
  cellSize: number;
  cols: number;
  rows: number;
  /** Row-major, 0xRRGGBB. */
  min: ArrayLike<number>;
  max: ArrayLike<number>;
}

/**
 * A launcher element over the image, by its plate box (dp, in the window): `text` and `secondary` need 4.5:1 in that
 * colour; `control` (the Exit button, filled with the text colour) needs 3:1 and is only helped by the dim.
 */
export interface LegibilityElement {
  id: string;
  kind: 'text' | 'secondary' | 'control';
  box: Box;
}

export interface Plate {
  /** 0 (no plate) to 50 (solid background colour); always 0 for a control. */
  step: number;
  /** The worst contrast against any pixel under the box, with the plate. */
  ratio: number;
  /** The same on the dimmed image alone. */
  ratioWithoutPlate: number;
  /** Texture under the box: a channel spans more than BUSY_SPAN levels. */
  busy: boolean;
}

export interface Legibility {
  /** The image dim, 0 to DIM_MAX_STEP. */
  dim: number;
  plates: Record<string, Plate>;
  /** The lowest text contrast with the plates (Infinity without texts). */
  textRatio: number;
  /** Some text needs a plate. */
  onPlate: boolean;
}

/** The cell grid of an iw x ih image: square cells of ceil(long / 120) px. */
export function cellGrid(iw: number, ih: number): { cellSize: number; cols: number; rows: number } {
  const cellSize = Math.max(1, Math.ceil(Math.max(iw, ih) / CELLS_ON_LONG_EDGE));
  return { cellSize, cols: Math.ceil(iw / cellSize), rows: Math.ceil(ih / cellSize) };
}

/**
 * Cells of RGBA pixels (canvas ImageData order), each pixel first composited over `background`:
 * per channel floor((c * a + b * (255 - a) + 127) / 255).
 */
export function imageCells(rgba: ArrayLike<number>, iw: number, ih: number, background: number): ImageCells {
  const { cellSize, cols, rows } = cellGrid(iw, ih);
  const n = cols * rows;
  const lo = new Array<number>(n * 3).fill(255);
  const hi = new Array<number>(n * 3).fill(0);
  const bg = [(background >> 16) & 255, (background >> 8) & 255, background & 255];
  for (let y = 0; y < ih; y++) {
    const row = Math.floor(y / cellSize) * cols;
    for (let x = 0; x < iw; x++) {
      const p = (y * iw + x) * 4;
      const a = rgba[p + 3];
      const c = (row + Math.floor(x / cellSize)) * 3;
      for (let k = 0; k < 3; k++) {
        const v = a === 255 ? rgba[p + k] : Math.floor((rgba[p + k] * a + bg[k] * (255 - a) + 127) / 255);
        if (v < lo[c + k]) lo[c + k] = v;
        if (v > hi[c + k]) hi[c + k] = v;
      }
    }
  }
  const pack = (arr: number[], i: number) => (arr[i * 3] << 16) | (arr[i * 3 + 1] << 8) | arr[i * 3 + 2];
  return {
    width: iw,
    height: ih,
    cellSize,
    cols,
    rows,
    min: Array.from({ length: n }, (_, i) => pack(lo, i)),
    max: Array.from({ length: n }, (_, i) => pack(hi, i)),
  };
}

/** The per-channel bounds of the pixels under `box` (dp in a w x h window): its cells through the crop, plus one. */
export function boxBounds(cells: ImageCells, window: { w: number; h: number }, box: Box): { lo: number; hi: number } {
  const crop = centreCrop(cells.width, cells.height, window.w, window.h);
  const k = crop.w / window.w;
  const cs = cells.cellSize;
  const x0 = crop.x + box.x * k;
  const x1 = crop.x + (box.x + box.w) * k;
  const y0 = crop.y + box.y * k;
  const y1 = crop.y + (box.y + box.h) * k;
  const i0 = Math.max(0, Math.floor(x0 / cs) - 1);
  const i1 = Math.min(cells.cols - 1, Math.ceil(x1 / cs));
  const j0 = Math.max(0, Math.floor(y0 / cs) - 1);
  const j1 = Math.min(cells.rows - 1, Math.ceil(y1 / cs));
  const lo = [255, 255, 255];
  const hi = [0, 0, 0];
  for (let j = j0; j <= j1; j++) {
    for (let i = i0; i <= i1; i++) {
      const mn = cells.min[j * cells.cols + i];
      const mx = cells.max[j * cells.cols + i];
      for (let c = 0; c < 3; c++) {
        const shift = 16 - 8 * c;
        lo[c] = Math.min(lo[c], (mn >> shift) & 255);
        hi[c] = Math.max(hi[c], (mx >> shift) & 255);
      }
    }
  }
  return { lo: (lo[0] << 16) | (lo[1] << 8) | lo[2], hi: (hi[0] << 16) | (hi[1] << 8) | hi[2] };
}

const widen = (c: number, by: number) => {
  let out = 0;
  for (const shift of [16, 8, 0]) out |= Math.min(255, Math.max(0, ((c >> shift) & 255) + by)) << shift;
  return out;
};

/**
 * The lowest contrast of `colour` against any pixel whose channels lie between `lo` and `hi`, widened by one level
 * each way for the GPU's rounding: luminance only grows with each channel, so the pixels' luminances span
 * [lum(lo), lum(hi)]; 1 when the colour's own luminance is inside.
 */
export function worstRatio(colour: number, lo: number, hi: number): number {
  const lt = luminance(colour);
  const a = luminance(widen(lo, -1));
  const b = luminance(widen(hi, 1));
  if (lt >= a && lt <= b) return 1;
  const other = lt < a ? a : b;
  return (Math.max(lt, other) + 0.05) / (Math.min(lt, other) + 0.05);
}

/** Texture: a channel spans more than BUSY_SPAN levels. */
export function isBusy(lo: number, hi: number): boolean {
  for (const shift of [16, 8, 0]) if (((hi >> shift) & 255) - ((lo >> shift) & 255) > BUSY_SPAN) return true;
  return false;
}

/** The dim, then a plate per text (docs/kiosk-background-scrim.md, version 3). */
export function kioskLegibility(
  cells: ImageCells,
  window: { w: number; h: number },
  colours: KioskColours,
  elements: readonly LegibilityElement[],
): Legibility {
  const bg = colours.background;
  const colourOf = (kind: LegibilityElement['kind']) => (kind === 'secondary' ? colours.secondary : colours.text);
  const bounds = elements.map((e) => boxBounds(cells, window, e.box));
  const controls = bounds.filter((_, i) => elements[i].kind === 'control');
  let dim = 0;
  if (controls.length) {
    dim = DIM_MAX_STEP;
    for (let s = 0; s <= DIM_MAX_STEP; s++) {
      if (controls.every((b) => worstRatio(colours.text, blendStep(bg, b.lo, s), blendStep(bg, b.hi, s)) >= CONTROL_MIN)) {
        dim = s;
        break;
      }
    }
  }
  const plates: Record<string, Plate> = {};
  let textRatio = Infinity;
  let onPlate = false;
  elements.forEach((e, i) => {
    const colour = colourOf(e.kind);
    const lo = blendStep(bg, bounds[i].lo, dim);
    const hi = blendStep(bg, bounds[i].hi, dim);
    const busy = isBusy(lo, hi);
    const ratioWithoutPlate = worstRatio(colour, lo, hi);
    if (e.kind === 'control') {
      plates[e.id] = { step: 0, ratio: ratioWithoutPlate, ratioWithoutPlate, busy };
      return;
    }
    let step = 0;
    if (ratioWithoutPlate < TEXT_MIN) {
      step = SCRIM_STEPS;
      if (!busy) {
        for (let s = 1; s <= SCRIM_STEPS; s++) {
          if (worstRatio(colour, blendStep(bg, lo, s), blendStep(bg, hi, s)) >= TEXT_MIN) {
            step = s;
            break;
          }
        }
      }
    }
    const ratio = step === 0 ? ratioWithoutPlate : worstRatio(colour, blendStep(bg, lo, step), blendStep(bg, hi, step));
    plates[e.id] = { step, ratio, ratioWithoutPlate, busy };
    textRatio = Math.min(textRatio, ratio);
    if (step > 0) onPlate = true;
  });
  return { dim, plates, textRatio, onPlate };
}

/** The part of an `iw` x `ih` image a `fw` x `fh` window shows, centre-cropped (Android's CENTER_CROP). */
export function centreCrop(iw: number, ih: number, fw: number, fh: number): { x: number; y: number; w: number; h: number } {
  const scale = Math.max(fw / iw, fh / ih);
  const w = Math.min(iw, fw / scale);
  const h = Math.min(ih, fh / scale);
  return { x: (iw - w) / 2, y: (ih - h) / 2, w, h };
}

/** The cells of a loaded image, read at its full size. Null when the browser can't read the pixels. */
export function readImageCells(image: CanvasImageSource, iw: number, ih: number, background: number): ImageCells | null {
  try {
    const canvas = document.createElement('canvas');
    canvas.width = iw;
    canvas.height = ih;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.drawImage(image, 0, 0, iw, ih);
    return imageCells(ctx.getImageData(0, 0, iw, ih).data, iw, ih, background);
  } catch {
    // A tainted canvas (cross-origin image) or no canvas support.
    return null;
  }
}
