// The kiosk launcher's adaptive scrim, the console side: how much of the kiosk background colour the agent lays over a
// background image so the launcher's text stays at 4.5:1 or more. The agent computes the same thing on the device;
// both are tested against proto/vectors/kiosk-scrim.json. Algorithm and rationale: docs/kiosk-background-scrim.md.
//
// Colours are opaque sRGB as 0xRRGGBB numbers. Everything here is pure except sampleCrop, which needs a canvas.

/** The scrim's alpha moves in 1/50 steps: 0 (the image as is) to 50 (the solid background colour). */
export const SCRIM_STEPS = 50;
/** WCAG AA for body text; the launcher has no large text. */
export const TEXT_MIN = 4.5;
/** Up to this share of the samples (rounded down) may stay under TEXT_MIN: a few bright specks don't dim a photo. */
export const ALLOWANCE_PERCENT = 2;
/** The visible crop is sampled at most this many pixels along its long edge. */
export const SAMPLE_EDGE = 48;

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

/**
 * The contrast `text` keeps over the scrimmed samples, all but the allowance: the (allowance+1)-th lowest ratio.
 * Infinity without samples.
 */
export function coveredRatio(samples: readonly number[], scrim: number, text: number, step: number): number {
  if (samples.length === 0) return Infinity;
  const allowed = Math.floor((samples.length * ALLOWANCE_PERCENT) / 100);
  const ratios = samples.map((s) => contrastRatio(text, blendStep(scrim, s, step))).sort((a, b) => a - b);
  return ratios[allowed];
}

export interface Scrim {
  /** 0 to 50. */
  step: number;
  /** step / 50: the scrim colour's opacity over the image. */
  alpha: number;
  /** The lowest covered ratio of the launcher's text colours with the scrim (Infinity without samples). */
  ratio: number;
  /** The same without any scrim: what an agent without the scrim shows. */
  ratioWithoutScrim: number;
}

/** The least scrim at which every text colour reaches 4.5:1 over the samples (the allowance aside). */
export function scrimFor(samples: readonly number[], colours: KioskColours): Scrim {
  const texts = [colours.text, colours.secondary];
  const worst = (step: number) => Math.min(...texts.map((t) => coveredRatio(samples, colours.background, t, step)));
  const ratioWithoutScrim = worst(0);
  for (let step = 0; step <= SCRIM_STEPS; step++) {
    const ratio = step === 0 ? ratioWithoutScrim : worst(step);
    if (ratio >= TEXT_MIN) return { step, alpha: step / SCRIM_STEPS, ratio, ratioWithoutScrim };
  }
  // Not reached: at 50 the samples are the background colour, which the text colours reach 4.5:1 on.
  return { step: SCRIM_STEPS, alpha: 1, ratio: worst(SCRIM_STEPS), ratioWithoutScrim };
}

/** The part of an `iw` x `ih` image a `fw` x `fh` window shows, centre-cropped (Android's CENTER_CROP). */
export function centreCrop(iw: number, ih: number, fw: number, fh: number): { x: number; y: number; w: number; h: number } {
  const scale = Math.max(fw / iw, fh / ih);
  const w = Math.min(iw, fw / scale);
  const h = Math.min(ih, fh / scale);
  return { x: (iw - w) / 2, y: (ih - h) / 2, w, h };
}

/** The sample grid of a crop: SAMPLE_EDGE along its long edge, at least 1 along the short one. */
export function sampleGrid(w: number, h: number): { cols: number; rows: number } {
  const long = Math.max(w, h);
  return {
    cols: Math.max(1, Math.round((SAMPLE_EDGE * w) / long)),
    rows: Math.max(1, Math.round((SAMPLE_EDGE * h) / long)),
  };
}

/**
 * Samples the part of `image` a `fw` x `fh` window shows: the centre crop, scaled down to the sample grid over the
 * background colour (transparent pixels show it on the device too). Null when the browser can't read the pixels.
 */
export function sampleCrop(
  image: CanvasImageSource,
  iw: number,
  ih: number,
  fw: number,
  fh: number,
  background: number,
): number[] | null {
  const crop = centreCrop(iw, ih, fw, fh);
  const { cols, rows } = sampleGrid(crop.w, crop.h);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = cols;
    canvas.height = rows;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.fillStyle = toHex(background);
    ctx.fillRect(0, 0, cols, rows);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(image, crop.x, crop.y, crop.w, crop.h, 0, 0, cols, rows);
    const data = ctx.getImageData(0, 0, cols, rows).data;
    const samples: number[] = [];
    for (let i = 0; i < data.length; i += 4) samples.push((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
    return samples;
  } catch {
    // A tainted canvas (cross-origin image) or no canvas support.
    return null;
  }
}
