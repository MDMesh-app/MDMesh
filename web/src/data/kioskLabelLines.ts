// How an app label breaks into lines: docs/kiosk-background-scrim.md, "Label lines" (3.6). Word boundaries only (and
// after a hyphen or slash inside a word, and between kana/Han characters), greedy, at most `maxLines`; a word wider
// than the box goes on its own line, ellipsized; the last line takes the rest, ellipsized. The agent's LabelLines
// follows the same text; the shared vectors (proto/vectors/kiosk-scrim.json, labelLines) hold both to it.

import { BIDI_UNICODE_VERSION, strongBidiClass } from './kioskBidi';

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
 * marks, ZWJ sequences, skin tones, tags, variation selectors, flags, prepended concatenation marks and the conjuncts
 * of the six Indic scripts the spec lists (Devanagari, Bengali, Gujarati, Oriya, Telugu, Malayalam) whole. What this
 * gives up: clusters UAX #29 makes from rules the guards don't restate. Conjoining Hangul jamo (a syllable spelled as
 * L V T code points) may be cut between its parts, and so may a conjunct of another Indic script (Tamil, Kannada,
 * Gurmukhi and the like, whose viramas aren't in the spec's table). Thai and Lao are unaffected: their vowel and tone
 * marks are combining marks (kept), and their leading vowels are cut before as UAX #29 does. Precomposed Hangul (the
 * usual form) and Latin, Cyrillic, Greek, Arabic, Hebrew and Thai labels with their marks cut exactly as with it.
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
/** Prepended concatenation marks (GB9b), the spec's fixed list: a cut never goes right after one. */
const PREPENDED = new Set([0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605, 0x06dd, 0x070f, 0x0890, 0x0891, 0x08e2, 0x110bd, 0x110cd]);
/** Conjunct linkers (GB9c): the viramas of Devanagari, Bengali, Gujarati, Oriya, Telugu and Malayalam. */
const LINKERS = new Set([0x094d, 0x09cd, 0x0acd, 0x0b4d, 0x0c4d, 0x0d4d]);
/** Conjunct consonants of the same six scripts, the spec's fixed table (inclusive ranges). */
const CONSONANTS: readonly (readonly [number, number])[] = [
  [0x0915, 0x0939], [0x0958, 0x095f], [0x0978, 0x097f],
  [0x0995, 0x09a8], [0x09aa, 0x09b0], [0x09b2, 0x09b2], [0x09b6, 0x09b9], [0x09dc, 0x09dd], [0x09df, 0x09df], [0x09f0, 0x09f1],
  [0x0a95, 0x0aa8], [0x0aaa, 0x0ab0], [0x0ab2, 0x0ab3], [0x0ab5, 0x0ab9], [0x0af9, 0x0af9],
  [0x0b15, 0x0b28], [0x0b2a, 0x0b30], [0x0b32, 0x0b33], [0x0b35, 0x0b39], [0x0b5c, 0x0b5d], [0x0b5f, 0x0b5f], [0x0b71, 0x0b71],
  [0x0c15, 0x0c28], [0x0c2a, 0x0c39], [0x0c58, 0x0c5a],
  [0x0d15, 0x0d3a],
];
const isConsonant = (cp: number) => CONSONANTS.some(([a, b]) => cp >= a && cp <= b);
/**
 * A conjunct extender: general category Mn or Me (the browser's own Unicode data), or U+200D. Unicode's own extenders
 * for GB9c also take a few spacing vowel signs; that differs only for a vowel sign right after a virama, which is
 * not valid text.
 */
const EXTENDER = /^[\p{Mn}\p{Me}]/u;
const isExtender = (cp: number) => cp === ZWJ || EXTENDER.test(String.fromCodePoint(cp));
/** At `e`: consonant (extender|linker)* linker (extender|linker)* × consonant. */
function insideConjunct(s: string, e: number): boolean {
  if (!isConsonant(s.codePointAt(e)!)) return false;
  let k = e;
  let linked = false;
  while (k > 0) {
    const cp = codePointBefore(s, k);
    if (LINKERS.has(cp)) linked = true;
    else if (!isExtender(cp)) return linked && isConsonant(cp);
    k -= cp > 0xffff ? 2 : 1;
  }
  return false;
}
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
 * The spec's conditions on cluster ends: none right after U+200D or a prepended concatenation mark; none before a
 * combining mark, U+200D, U+FE0F, a skin tone or a tag; none inside an Indic conjunct (GB9c, fixed tables); between
 * two regional indicators a cut after every second one counted from the start of their run and nowhere else (after
 * the last one of a run, as any other boundary). On current Unicode data they change nothing but a ZWJ before a
 * non-emoji; they make older data (and no segmenter) cut the same.
 */
function guard(s: string, clusterEnds: readonly number[]): number[] {
  const out = new Set<number>();
  for (const e of clusterEnds) {
    if (e >= s.length) continue;
    if (e <= 0 || (isLow(s.charCodeAt(e)) && isHigh(s.charCodeAt(e - 1)))) continue;
    const before = codePointBefore(s, e);
    if (before === ZWJ || PREPENDED.has(before) || joinsPrevious(s.codePointAt(e)!) || insideConjunct(s, e)) continue;
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

/**
 * How much text after the cap point the cap segments. A boundary depends only on the text before it and the code point
 * after it, so the cuts at or before the cap point are exactly the full label's; the window only bounds the work.
 */
const CAP_LOOKAHEAD = 64;

/**
 * The first {@link LABEL_MAX_CODE_POINTS} code points, moved back to the last grapheme cut at or before that, so the cap
 * never splits a cluster (or a surrogate pair). With no grapheme cut at or before it (one cluster longer than the
 * cap), the spec's hard cut right after the 256th code point. Only the clusters around the cut are looked at.
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

const LRI = 0x2066;
const FSI = 0x2068;
const PDI = 0x2069;
/**
 * The label's one direction (Label lines, 6; UAX #9 rule P2): going through its code points, unassigned ones are
 * skipped; an isolate initiator (LRI, RLI, FSI) skips to its matching PDI (to the end when none); a stray PDI is
 * skipped; the first of bidi class L makes it left to right, R or AL right to left. Embeddings and overrides are not
 * strong. None: left to right. Bidi classes and unassigned code points come from one generated table
 * ({@link BIDI_UNICODE_VERSION}): a code point newer than it is never strong, so it can't change the answer, but a
 * label whose first strong letter is in a script newer than the table is skipped there too, and reads left to right.
 * The table is the same in every browser, so the answer is too.
 */
export function labelDirection(text: string): 'ltr' | 'rtl' {
  let depth = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    if (cp >= LRI && cp <= FSI) depth++;
    else if (cp === PDI) depth = Math.max(0, depth - 1);
    else if (depth === 0) {
      const c = strongBidiClass(cp);
      if (c) return c === 'R' ? 'rtl' : 'ltr';
    }
  }
  return 'ltr';
}

/** Format characters (Cf: LRM, RLM, ALM, isolates, embeddings, ZWJ, ZWNJ...), whitespace (Zs, Zl, Zp) and controls (Cc). */
const INVISIBLE = /^[\p{Cf}\p{Zs}\p{Zl}\p{Zp}\p{Cc}]$/u;

/**
 * The placeholder letter (Label lines, 7) for an app shown without its own icon: the capped label's first grapheme
 * (between the cuts of 5) that holds a visible code point, upper-cased with the default full case mapping
 * (`toUpperCase`, never the viewer's locale: `\u00df` becomes `SS` and `i` becomes `I` everywhere). Clusters of only
 * format characters, whitespace or controls are skipped. Null when nothing is visible: the tile stays blank.
 */
export function iconLetter(label: string, useSegmenter = true): string | null {
  const capped = capLabel(label, useSegmenter);
  let start = 0;
  for (const end of graphemeCuts(capped, useSegmenter)) {
    const cluster = capped.slice(start, end);
    start = end;
    for (const ch of cluster) if (!INVISIBLE.test(ch)) return cluster.toUpperCase();
  }
  return null;
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
