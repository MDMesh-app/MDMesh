// How an app label breaks into lines: docs/kiosk-background-scrim.md, "Label lines" (3.4). Word boundaries only (and
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

// ---- grapheme cuts (Label lines, 5) ----

interface GraphemeSegmenter {
  segment(s: string): Iterable<{ index: number }>;
}
type SegmenterCtor = new (locale: string | undefined, options: { granularity: 'grapheme' }) => GraphemeSegmenter;

let segmenter: GraphemeSegmenter | null | undefined;
/** UAX #29 extended grapheme clusters from the browser (`Intl.Segmenter`), or null where it has none. */
function platformSegmenter(): GraphemeSegmenter | null {
  if (segmenter === undefined) {
    const Ctor = (Intl as unknown as { Segmenter?: SegmenterCtor }).Segmenter;
    try {
      segmenter = typeof Ctor === 'function' ? new Ctor(undefined, { granularity: 'grapheme' }) : null;
    } catch {
      segmenter = null;
    }
  }
  return segmenter;
}

/** Cluster ends (offsets after each cluster) from the browser's UAX #29 segmentation. */
function segmentedEnds(s: string, seg: GraphemeSegmenter): number[] {
  const out: number[] = [];
  for (const { index } of seg.segment(s)) if (index > 0) out.push(index);
  out.push(s.length);
  return out;
}

/**
 * Without `Intl.Segmenter` (Firefox before 125): every code point ends a cluster, and the guards below alone keep
 * marks, ZWJ sequences, skin tones, tags, variation selectors and flags whole. What this gives up: clusters UAX #29
 * makes from rules the guards don't restate. Conjoining Hangul jamo (a syllable spelled as L V T code points) and
 * prepended or spacing characters of some Indic scripts may be cut between their parts; precomposed Hangul (the usual
 * form) and every Latin, Cyrillic, Greek, Arabic and Hebrew label with its accents are cut exactly as with it.
 */
export function codePointEnds(s: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < s.length; ) {
    i += isHigh(s.charCodeAt(i)) && i + 1 < s.length && isLow(s.charCodeAt(i + 1)) ? 2 : 1;
    out.push(i);
  }
  return out;
}

const ZWJ = 0x200d;
const isRegional = (cp: number) => cp >= 0x1f1e6 && cp <= 0x1f1ff;
const MARK = /^\p{M}/u;
/** Joins the code point before it: a combining mark, ZWJ, VS16, a skin tone or a tag. A cut never goes before one. */
function joinsPrevious(cp: number): boolean {
  return cp === ZWJ || cp === 0xfe0f || (cp >= 0x1f3fb && cp <= 0x1f3ff) || (cp >= 0xe0020 && cp <= 0xe007f) || MARK.test(String.fromCodePoint(cp));
}
function codePointBefore(s: string, i: number): number {
  const lo = s.charCodeAt(i - 1);
  return isLow(lo) && i >= 2 && isHigh(s.charCodeAt(i - 2)) ? s.codePointAt(i - 2)! : lo;
}

/**
 * The spec's conditions on cluster ends: none right after U+200D; none before a combining mark, U+200D, U+FE0F, a skin
 * tone or a tag; inside a run of regional indicators a cut after every second one and nowhere else. On current
 * Unicode data they change nothing but a ZWJ before a non-emoji; they make older data (and no segmenter) cut the same.
 */
function guard(s: string, clusterEnds: readonly number[]): number[] {
  const out = new Set<number>();
  for (const e of clusterEnds) {
    if (e >= s.length) continue;
    if (e <= 0 || (isLow(s.charCodeAt(e)) && isHigh(s.charCodeAt(e - 1)))) continue;
    if (codePointBefore(s, e) === ZWJ || joinsPrevious(s.codePointAt(e)!)) continue;
    out.add(e);
  }
  // Regional indicators pair from the start of their run.
  let run = 0;
  for (let i = 0; i < s.length; ) {
    const cp = s.codePointAt(i)!;
    i += cp > 0xffff ? 2 : 1;
    run = isRegional(cp) ? run + 1 : 0;
    if (run > 0 && i < s.length && isRegional(s.codePointAt(i)!)) {
      if (run % 2 === 0) out.add(i);
      else out.delete(i);
    }
  }
  out.add(s.length);
  return [...out].sort((a, b) => a - b);
}

/**
 * Where `s` may be cut (grapheme cuts, shortest first, always ending with `s.length`): UAX #29 cluster ends with the
 * spec's conditions. `useSegmenter: false` gives the fallback without `Intl.Segmenter` ({@link codePointEnds}).
 */
export function graphemeCuts(s: string, useSegmenter = true): number[] {
  if (!s) return [];
  const seg = useSegmenter ? platformSegmenter() : null;
  return guard(s, seg ? segmentedEnds(s, seg) : codePointEnds(s));
}

/** Lookahead for the cap's clusters: a cluster longer than this at the cap is cut at a code point (bounded work). */
const CAP_LOOKAHEAD = 64;

/**
 * The first {@link LABEL_MAX_CODE_POINTS} code points, moved back to the last grapheme cut at or before that, so the cap
 * never splits a cluster (or a surrogate pair). Only the clusters around the cut are looked at.
 */
export function capLabel(text: string, useSegmenter = true): string {
  let i = 0;
  let n = 0;
  while (i < text.length && n < LABEL_MAX_CODE_POINTS) {
    i += isHigh(text.charCodeAt(i)) && i + 1 < text.length && isLow(text.charCodeAt(i + 1)) ? 2 : 1;
    n++;
  }
  if (i >= text.length) return text;
  const cuts = graphemeCuts(text.slice(0, Math.min(text.length, i + CAP_LOOKAHEAD)), useSegmenter);
  let end = i;
  for (let k = cuts.length - 1; k >= 0; k--) {
    if (cuts[k] <= i) {
      end = cuts[k];
      break;
    }
  }
  return text.slice(0, end);
}

/**
 * The label's one direction (Label lines, 6): its first strong character's, left to right when it has none. Letters
 * of the right-to-left scripts (Hebrew, Arabic, Syriac, Thaana, N'Ko and the like, and their presentation forms) are
 * strong right to left, every other letter strong left to right; digits, marks, punctuation and symbols are not strong.
 */
export function labelDirection(text: string): 'ltr' | 'rtl' {
  const m = /\p{L}/u.exec(text);
  if (!m) return 'ltr';
  const cp = m[0].codePointAt(0)!;
  const rtl =
    (cp >= 0x0590 && cp <= 0x08ff) ||
    (cp >= 0xfb1d && cp <= 0xfdff) ||
    (cp >= 0xfe70 && cp <= 0xfeff) ||
    (cp >= 0x10800 && cp <= 0x10fff) ||
    (cp >= 0x1e800 && cp <= 0x1efff);
  return rtl ? 'rtl' : 'ltr';
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

const trimSpaces = (s: string) => s.replace(/ +$/, '');

/**
 * `s` when it is at most `box` wide; otherwise its longest prefix at a grapheme cut, trailing spaces removed, that
 * fits followed by the ellipsis (measured as one string, as it is drawn), then the ellipsis (alone when no prefix, or
 * only spaces, fit). Bisection over the cuts: a prefix's width grows with its length, so this measures O(log n).
 */
export function ellipsize(s: string, box: number, measure: LineMeasure, useSegmenter = true): string {
  if (measure(s) <= box) return s;
  const at = graphemeCuts(s, useSegmenter);
  const fits = (i: number) => measure(trimSpaces(s.slice(0, at[i])) + ELLIPSIS) <= box;
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
export function labelLines(label: string, box: number, maxLines: number, measure: LineMeasure, useSegmenter = true): string[] {
  const text = normalizeLabel(capLabel(label, useSegmenter));
  if (!text || maxLines < 1) return [];
  const ps = pieces(text);
  const out: string[] = [];
  let current = '';
  let i = 0;
  while (i < ps.length && out.length < maxLines - 1) {
    const p = ps[i];
    if (!current) {
      // An over-wide piece takes a line of its own, ellipsized; otherwise it starts the line.
      if (measure(p.text) > box) out.push(ellipsize(p.text, box, measure, useSegmenter));
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
  if (last) out.push(ellipsize(last, box, measure, useSegmenter));
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
