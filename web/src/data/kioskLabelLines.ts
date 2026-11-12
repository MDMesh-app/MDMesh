// How an app label breaks into lines: docs/kiosk-background-scrim.md, "Label lines" (3.3). Word boundaries only (and
// after a hyphen or slash inside a word, and between kana/Han characters), greedy, at most `maxLines`; a word wider
// than the box goes on its own line, ellipsized; the last line takes the rest, ellipsized. The agent's LabelLines
// follows the same text; the shared vectors (proto/vectors/kiosk-scrim.json, labelLines) hold both to it.

/** Only this many code points of a label are laid out: far more than 3 lines show, and it bounds the work. */
export const LABEL_MAX_CODE_POINTS = 256;
export const ELLIPSIS = '…';

/** Width of a string with the label's paint, in dp. */
export type LineMeasure = (s: string) => number;

const isHigh = (c: number) => c >= 0xd800 && c <= 0xdbff;
const isLow = (c: number) => c >= 0xdc00 && c <= 0xdfff;

/** The first {@link LABEL_MAX_CODE_POINTS} code points, never cut inside a surrogate pair. */
export function capLabel(text: string): string {
  let i = 0;
  let n = 0;
  while (i < text.length && n < LABEL_MAX_CODE_POINTS) {
    i += isHigh(text.charCodeAt(i)) && i + 1 < text.length && isLow(text.charCodeAt(i + 1)) ? 2 : 1;
    n++;
  }
  return i === text.length ? text : text.slice(0, i);
}

/** Every run of tab, line feed, carriage return and space becomes one space; trimmed. Other spaces are word characters. */
export function normalizeLabel(text: string): string {
  return text.replace(/[\t\n\r ]+/g, ' ').replace(/^ /, '').replace(/ $/, '');
}

/** Kana and Han, written without spaces: a line may break before and after each. */
export function isCjk(c: number): boolean {
  return (c >= 0x3040 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xf900 && c <= 0xfaff);
}

interface Piece {
  text: string;
  /** The first piece of a word: it follows a space; a later piece of the same word follows nothing. */
  startsWord: boolean;
}

function pieces(text: string): Piece[] {
  const out: Piece[] = [];
  for (const word of text.split(' ')) {
    let start = 0;
    let first = true;
    const flush = (end: number) => {
      if (end > start) {
        out.push({ text: word.slice(start, end), startsWord: first });
        first = false;
        start = end;
      }
    };
    for (let j = 0; j < word.length; j++) {
      const c = word.charCodeAt(j);
      if (isCjk(c)) {
        flush(j); // before
        flush(j + 1); // and after
      } else if ((c === 0x2d || c === 0x2f) && j < word.length - 1) {
        flush(j + 1); // after a hyphen or slash with more of the word after it
      }
    }
    flush(word.length);
  }
  return out;
}

/** Joins the previous code point: a combining mark (category M), ZWJ or VS16. A cut never goes before one. */
const JOINS = /^[\p{M}‍️]/u;

/** Where a prefix may end: code-point boundaries (1..length) not followed by a joining code point. */
function cuts(s: string): number[] {
  const out: number[] = [];
  for (let k = 1; k <= s.length; k++) {
    if (k < s.length && isLow(s.charCodeAt(k)) && isHigh(s.charCodeAt(k - 1))) continue;
    if (k < s.length && JOINS.test(s.slice(k, k + 2))) continue;
    out.push(k);
  }
  return out;
}

const trimSpaces = (s: string) => s.replace(/ +$/, '');

/**
 * `s` when it is at most `box` wide; otherwise its longest prefix whose width (trailing spaces removed) plus the
 * ellipsis' is at most `box`, then the ellipsis (alone when not even one character fits). Bisection: a prefix's width
 * grows with its length, so this measures O(log n) prefixes.
 */
export function ellipsize(s: string, box: number, measure: LineMeasure): string {
  if (measure(s) <= box) return s;
  const ell = measure(ELLIPSIS);
  const at = cuts(s);
  const fits = (i: number) => measure(trimSpaces(s.slice(0, at[i]))) + ell <= box;
  let lo = -1; // the longest cut known to fit (-1: none)
  let hi = at.length; // the shortest cut known not to fit
  while (hi - lo > 1) {
    const mid = (lo + hi) >>> 1;
    if (fits(mid)) lo = mid;
    else hi = mid;
  }
  return lo < 0 ? ELLIPSIS : trimSpaces(s.slice(0, at[lo])) + ELLIPSIS;
}

/** The lines of `label` in a text box `box` wide, at most `maxLines`, in logical order. */
export function labelLines(label: string, box: number, maxLines: number, measure: LineMeasure): string[] {
  const text = normalizeLabel(capLabel(label));
  if (!text || maxLines < 1) return [];
  const ps = pieces(text);
  const out: string[] = [];
  let current = '';
  let i = 0;
  while (i < ps.length && out.length < maxLines - 1) {
    const p = ps[i];
    if (!current) {
      // An over-wide piece takes a line of its own, ellipsized; otherwise it starts the line.
      if (measure(p.text) > box) out.push(ellipsize(p.text, box, measure));
      else current = p.text;
      i++;
      continue;
    }
    const joined = current + (p.startsWord ? ' ' : '') + p.text;
    if (measure(joined) <= box) {
      current = joined;
      i++;
    } else {
      out.push(current); // the piece is tried again on a new line
      current = '';
    }
  }
  // The last line: the current line and everything left, ellipsized.
  let last = current;
  for (; i < ps.length; i++) last += (last && ps[i].startsWord ? ' ' : '') + ps[i].text;
  if (last) out.push(ellipsize(last, box, measure));
  return out;
}

const caches = new WeakMap<object, Map<string, string[]>>();
const CACHE_SIZE = 2048;

/**
 * {@link labelLines}, cached per measure (the font), size, weight, box, line count and capped label: a preview lays
 * out the same labels for every view and on every render.
 */
export function cachedLabelLines(
  measure: (text: string, size: number, weight: number) => number,
  size: number,
  weight: number,
  label: string,
  box: number,
  maxLines: number,
): string[] {
  let cache = caches.get(measure);
  if (!cache) caches.set(measure, (cache = new Map()));
  const capped = capLabel(label);
  const key = `${size}|${weight}|${box}|${maxLines}|${capped}`;
  const hit = cache.get(key);
  if (hit) {
    cache.delete(key); // most recently used last
    cache.set(key, hit);
    return hit;
  }
  const lines = labelLines(capped, box, maxLines, (s) => measure(s, size, weight));
  cache.set(key, lines);
  if (cache.size > CACHE_SIZE) cache.delete(cache.keys().next().value as string);
  return lines;
}
