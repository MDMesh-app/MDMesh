// WCAG 2.x contrast for the design tokens in tokens.css: parse each theme's custom properties, resolve var()
// references and composite translucent colours over their background. Used by tokenContrast.test.ts.

export type Theme = 'light' | 'dark';
type Rgba = [number, number, number, number];

/** The `--name: value` declarations of the light block (`.v2 {`) and the dark block, dark layered over light. */
export function parseTokens(css: string): Record<Theme, Record<string, string>> {
  const block = (selector: RegExp) => {
    const m = css.match(selector);
    if (!m) throw new Error(`tokens.css: no block for ${selector}`);
    const body = css.slice(m.index! + m[0].length, css.indexOf('}', m.index! + m[0].length));
    const out: Record<string, string> = {};
    for (const d of body.matchAll(/--([a-z0-9-]+)\s*:\s*([^;]+);/g)) out[d[1]] = d[2].trim();
    return out;
  };
  const light = block(/^\.v2 \{/m);
  const dark = { ...light, ...block(/^\[data-theme='dark'\] \.v2,\s*\n\.v2\[data-theme='dark'\] \{/m) };
  return { light, dark };
}

/** A token's value with var() references followed, as a colour. */
export function resolve(tokens: Record<string, string>, name: string, seen: string[] = []): Rgba {
  if (seen.includes(name)) throw new Error(`token cycle: ${[...seen, name].join(' -> ')}`);
  const raw = tokens[name];
  if (raw === undefined) throw new Error(`no token --${name}`);
  const ref = raw.match(/^var\(--([a-z0-9-]+)\)$/);
  if (ref) return resolve(tokens, ref[1], [...seen, name]);
  return parseColour(raw);
}

export function parseColour(v: string): Rgba {
  const s = v.trim().toLowerCase();
  let m = s.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/);
  if (m) {
    const h = m[1].length === 3 ? m[1].replace(/./g, (c) => c + c) : m[1];
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1];
  }
  m = s.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/);
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])];
  throw new Error(`not a colour: ${v}`);
}

/** `fg` painted over an opaque `bg`. */
export function over(fg: Rgba, bg: Rgba): Rgba {
  const a = fg[3];
  return [fg[0] * a + bg[0] * (1 - a), fg[1] * a + bg[1] * (1 - a), fg[2] * a + bg[2] * (1 - a), 1];
}

function luminance([r, g, b]: Rgba): number {
  const lin = (c: number) => {
    const x = c / 255;
    return x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

export function ratio(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

export const hex = (c: Rgba) => '#' + c.slice(0, 3).map((x) => Math.round(x).toString(16).padStart(2, '0')).join('');

/**
 * Contrast of token `fg` on token `bg` in one theme. `bg` may itself sit on `base` (a translucent fill); `bg` and `fg`
 * are composited in that order.
 */
export function tokenRatio(
  tokens: Record<string, string>,
  fg: string,
  bg: string,
  base = 'surface',
): { ratio: number; fg: string; bg: string } {
  const ground = resolve(tokens, base);
  const b = over(resolve(tokens, bg), ground);
  const f = over(resolve(tokens, fg), b);
  return { ratio: ratio(f, b), fg: hex(f), bg: hex(b) };
}
