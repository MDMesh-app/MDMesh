import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import {
  ELLIPSIS,
  LABEL_MAX_CODE_POINTS,
  cachedLabelLines,
  capLabel,
  codePointEnds,
  ellipsize,
  graphemeCuts,
  iconLetter,
  labelDirection,
  labelLines,
  normalizeLabel,
} from './kioskLabelLines';
import { BIDI_UNICODE_VERSION, strongBidiClass } from './kioskBidi';

interface LabelCase {
  name: string;
  note: string;
  text: string;
  widths: number[];
  ellipsisWidth: number;
  box: number;
  maxLines: number;
  expected: string[];
}

const VECTORS = JSON.parse(readFileSync(resolve(import.meta.dirname, '../../../proto/vectors/kiosk-scrim.json'), 'utf8')) as {
  labelLines: LabelCase[];
  direction: { name: string; text: string; expected: 'ltr' | 'rtl' }[];
  iconLetter: { name: string; label: string; expected: string | null }[];
};

/** The vectors' measure: the sum of the per-character widths (the same character always has the same width). */
function vectorMeasure(c: LabelCase): (s: string) => number {
  const width = new Map<string, number>();
  for (let i = 0; i < c.text.length; i++) width.set(c.text[i], c.widths[i]);
  width.set(ELLIPSIS, c.ellipsisWidth);
  return (s) => {
    let sum = 0;
    for (let i = 0; i < s.length; i++) {
      const w = width.get(s[i]);
      if (w === undefined) throw new Error(`no width for ${JSON.stringify(s[i])} in ${c.name}`);
      sum += w;
    }
    return sum;
  };
}

describe('label lines vectors (3.5, shared with the agent)', () => {
  it('has all 29 cases', () => {
    expect(VECTORS.labelLines).toHaveLength(29);
  });
  it.each(VECTORS.labelLines.map((c) => [c.name, c] as const))('%s', (_, c) => {
    expect(c.widths).toHaveLength(c.text.length);
    expect(labelLines(c.text, c.box, c.maxLines, vectorMeasure(c))).toEqual(c.expected);
  });
});

describe('direction vectors (3.5, UAX #9 P2, shared with the agent)', () => {
  it('has all 16 cases', () => {
    expect(VECTORS.direction).toHaveLength(16);
  });
  it.each(VECTORS.direction.map((c) => [c.name, c] as const))('%s', (_, c) => {
    expect(labelDirection(c.text)).toBe(c.expected);
  });
});

describe('placeholder letter vectors (3.6, Label lines 7, shared with the agent)', () => {
  it('has all 14 cases', () => {
    expect(VECTORS.iconLetter).toHaveLength(14);
  });
  it.each(VECTORS.iconLetter.map((c) => [c.name, c] as const))('%s', (_, c) => {
    expect(iconLetter(c.label)).toBe(c.expected);
  });
  it('ignores the viewer\'s locale (a Turkish one would make i into a dotted I)', () => {
    const toLocale = vi.spyOn(String.prototype, 'toLocaleUpperCase');
    expect(iconLetter('inventory')).toBe('I');
    expect(toLocale).not.toHaveBeenCalled();
    toLocale.mockRestore();
  });
  it('is the same without Intl.Segmenter for these cases', () => {
    for (const c of VECTORS.iconLetter) expect(iconLetter(c.label, false), c.name).toBe(c.expected);
  });
});

describe('bidi table', () => {
  it('is the Unicode version the spec vectors follow', () => {
    const doc = readFileSync(resolve(import.meta.dirname, '../../../docs/kiosk-background-scrim.md'), 'utf8');
    const assumed = /The expected results follow UAX #29 of Unicode (\d+\.\d+)/.exec(doc)?.[1];
    expect(assumed).toBe('15.1');
    expect(BIDI_UNICODE_VERSION).toBe(`${assumed}.0`);
  });

  it('has strong classes only, and unassigned code points in none', () => {
    expect(strongBidiClass(0x41)).toBe('L'); // A
    expect(strongBidiClass(0x5d0)).toBe('R'); // Hebrew alef
    expect(strongBidiClass(0x627)).toBe('R'); // Arabic alef (AL)
    expect(strongBidiClass(0x200f)).toBe('R'); // RLM
    expect(strongBidiClass(0x200e)).toBe('L'); // LRM
    expect(strongBidiClass(0x31)).toBeNull(); // a digit
    expect(strongBidiClass(0x202b)).toBeNull(); // RLE: embeddings aren't strong
    expect(strongBidiClass(0x378)).toBeNull(); // unassigned
    expect(strongBidiClass(0x10ffff)).toBeNull();
    expect(strongBidiClass(0x2160)).toBe('L'); // Roman numeral one
  });
});

describe('label lines building blocks', () => {
  const per = (s: string) => [...s].length * 10;

  it('caps at 256 code points without splitting a surrogate pair', () => {
    const emoji = '\u{1F600}';
    const s = 'a'.repeat(255) + emoji + 'b';
    expect(capLabel(s)).toBe('a'.repeat(255) + emoji);
    expect([...capLabel(emoji.repeat(300))]).toHaveLength(LABEL_MAX_CODE_POINTS);
    expect(capLabel('short')).toBe('short');
  });

  it('normalizes only tab, line feed, carriage return and space', () => {
    expect(normalizeLabel(' \tPrice\r\n  Check ')).toBe('Price Check');
    expect(normalizeLabel('No break')).toBe('No break');
    expect(labelLines(' \n ', 100, 2, per)).toEqual([]);
  });

  it('never cuts a ZWJ sequence or a variation selector off its character', () => {
    const family = '\u{1F468}\u200d\u{1F469}';
    const heart = '\u2764\ufe0f';
    const m = (s: string) =>
      [...s].filter((ch) => ch !== '\u200d' && ch !== '\ufe0f' && ch !== ELLIPSIS).length * 10 + (s.endsWith(ELLIPSIS) ? 6 : 0);
    expect(ellipsize(`ab${family}cd`, 36, m)).toBe(`ab${ELLIPSIS}`);
    expect(ellipsize(`ab${family}cd`, 45, m)).toBe(`ab${ELLIPSIS}`); // the whole family or none of it
    expect(ellipsize(`ab${family}cd`, 46, m)).toBe(`ab${family}${ELLIPSIS}`);
    expect(ellipsize(`ab${heart}cd`, 35, m)).toBe(`ab${ELLIPSIS}`); // the heart with its VS16, or neither
    expect(ellipsize(`ab${heart}cd`, 36, m)).toBe(`ab${heart}${ELLIPSIS}`);
  });

  it('measures the ellipsis with the text before it (kerning)', () => {
    // A pair that kerns: "o…" is narrower than "o" plus "…".
    const m = (s: string) => [...s].length * 10 - (s.endsWith(`o${ELLIPSIS}`) ? 3 : 0);
    expect(ellipsize('Photos', 37, m)).toBe(`Pho${ELLIPSIS}`);
    expect(ellipsize('Photos', 36, m)).toBe(`Ph${ELLIPSIS}`);
  });

  it('keeps Indic conjuncts and prepended marks whole, with or without Intl.Segmenter', () => {
    const kshatriya = cp(0x915, 0x94d, 0x937, 0x924, 0x94d, 0x930, 0x93f, 0x92f); // a Hindi word: ksha-tri-ya
    for (const useSegmenter of [true, false]) {
      // The first cluster is the whole conjunct (ka, virama, ssa), never "ka, virama".
      expect(graphemeCuts(kshatriya, useSegmenter)[0]).toBe(3);
      expect(graphemeCuts(cp(0x600, 0x661, 0x662), useSegmenter)[0]).toBe(2); // the mark stays with its digit
    }
  });

  it('hard-cuts right after the 256th code point when no cut is at or before it', () => {
    const giant = 'a' + cp(0x301).repeat(400);
    expect(capLabel(giant)).toBe('a' + cp(0x301).repeat(255));
  });

  it('caps at a grapheme cut: never inside a flag or an emoji sequence', () => {
    const flag = '\u{1F1FA}\u{1F1F8}';
    // 255 letters, then a flag: the 256th code point is the flag's first half, so the cap moves back before it.
    expect(capLabel('a'.repeat(255) + flag + 'b')).toBe('a'.repeat(255));
    expect(capLabel('a'.repeat(254) + flag + 'b')).toBe('a'.repeat(254) + flag);
    const family = '\u{1F468}\u200d\u{1F469}\u200d\u{1F467}';
    expect(capLabel('a'.repeat(253) + family + 'b')).toBe('a'.repeat(253));
  });

  it('gives a label one direction, from its first strong character', () => {
    expect(labelDirection('ניהול')).toBe('rtl');
    expect(labelDirection('123 مخزن')).toBe('rtl');
    expect(labelDirection('POS ניהול')).toBe('ltr');
    expect(labelDirection('ְab')).toBe('ltr'); // a Hebrew point isn't strong
    expect(labelDirection('123 !')).toBe('ltr');
  });

  it('caches per font, size, box and label', () => {
    let calls = 0;
    const measure = (s: string) => {
      calls++;
      return s.length * 7;
    };
    const first = cachedLabelLines(measure, 14, 400, 'Maintenance Requests', 64, 2);
    const after = calls;
    expect(cachedLabelLines(measure, 14, 400, 'Maintenance Requests', 64, 2)).toBe(first);
    expect(calls).toBe(after);
    cachedLabelLines(measure, 14, 400, 'Maintenance Requests', 80, 2);
    expect(calls).toBeGreaterThan(after);
  });
});

// ---- grapheme cuts, with and without Intl.Segmenter ----

/** A tiny random generator (mulberry32), so a failure can be replayed by its seed. */
function rng(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const cp = (...c: number[]) => String.fromCodePoint(...c);
const PARTS = [
  'a', 'b', cp(0xe9), cp(0x65, 0x301), cp(0x6e, 0x303, 0x323), ' ', ' ', '-', '/', cp(0x5728), cp(0x5e9), cp(0x628),
  cp(0x1f1fa), cp(0x1f1f8), cp(0x1f1ef, 0x1f1f5), // regional indicators, alone and paired
  cp(0x1f44d, 0x1f3fd), cp(0x1f3ff), // a skin tone on its emoji, and a stray one
  cp(0x1f468, 0x200d, 0x1f469, 0x200d, 0x1f467), cp(0x200d), cp(0x2764, 0xfe0f), // a ZWJ family, a stray ZWJ, VS16
  cp(0x1f3f4, 0xe0067, 0xe0062, 0xe0073, 0xe0063, 0xe0074, 0xe007f), // a tag sequence (Scotland)
  cp(0x1100, 0x1161, 0x11a8), cp(0x1100, 0x1161), cp(0x11a8), // conjoining Hangul jamo: LVT, LV, a stray T
  cp(0xac01), // a precomposed syllable
  // Indic (3.5): consonants, viramas (linkers), a nukta (extender), a vowel sign, ZWJ inside a conjunct.
  cp(0x915), cp(0x937), cp(0x924), cp(0x94d), cp(0x93c), cp(0x93f), cp(0x915, 0x94d, 0x200d, 0x937),
  cp(0x995, 0x9cd, 0x9b7), cp(0x9cd), cp(0xd15, 0xd4d, 0xd37), cp(0xa95, 0xacd), cp(0xb15, 0xb4d, 0xb37), cp(0xc15, 0xc4d, 0xc37),
  cp(0x600), cp(0x6dd), cp(0x661, 0x662), cp(0x110bd), // prepended concatenation marks, then digits
  cp(0xe01, 0xe31), cp(0xe40), cp(0xe01, 0xe48), // Thai: a vowel mark, a leading vowel, a tone mark
];
/**
 * Left out of the fallback's equality check: conjoining jamo, which it doesn't restate (see codePointEnds); and a
 * skin tone that follows no emoji, which newer Unicode (16 on, as Node's ICU) counts as a conjunct extender where the
 * spec's table (Mn, Me, ZWJ) doesn't: a sequence the vectors don't cover, which may differ between versions.
 */
const FALLBACK_GAPS = /[\u1100-\u11FF]|(?<!\u{1F44D})[\u{1F3FB}-\u{1F3FF}]/gu;
const randomLabel = (r: () => number, n: number) => Array.from({ length: n }, () => PARTS[Math.floor(r() * PARTS.length)]).join('');
/** Widths that grow with length and differ per code point, with a kerning pair before the ellipsis. */
const widthOf = (s: string) => {
  let w = 0;
  for (const ch of s) w += ch === ELLIPSIS ? 6 : /\p{M}|\u200d|\ufe0f/u.test(ch) ? 0 : 4 + (ch.codePointAt(0)! % 7);
  return w - (s.endsWith(`a${ELLIPSIS}`) ? 1 : 0);
};
const RI = /[\u{1F1E6}-\u{1F1FF}]/u;
const insideRegionalRun = (s: string, c: number) => RI.test(s.slice(Math.max(0, c - 2), c)) && RI.test(s.slice(c, c + 2));

describe('grapheme cuts', () => {
  // The test environment's own UAX #29 (Node's ICU); the app's tsconfig lib predates Intl.Segmenter's types.
  type Seg = { segment(s: string): Iterable<{ index: number; segment: string }> };
  const seg = new (Intl as unknown as { Segmenter: new (l: undefined, o: { granularity: 'grapheme' }) => Seg }).Segmenter(undefined, {
    granularity: 'grapheme',
  });
  const clusterEnds = (s: string) => new Set([...seg.segment(s)].map((g) => g.index + g.segment.length));

  it('are UAX #29 cluster ends: never inside a flag, family, skin tone, tag sequence or syllable', () => {
    const r = rng(1);
    for (let n = 0; n < 2000; n++) {
      const s = randomLabel(r, 1 + Math.floor(r() * 12));
      const ends = clusterEnds(s);
      for (const c of graphemeCuts(s)) {
        // Every cut is a cluster end, except the pair cuts inside a run of regional indicators.
        if (!insideRegionalRun(s, c)) expect(ends.has(c), `${JSON.stringify(s)} cut at ${c}`).toBe(true);
        if (c < s.length) {
          expect(s.codePointAt(c - 1)).not.toBe(0x200d);
          expect(/^[\p{M}\u200d\ufe0f\u{1F3FB}-\u{1F3FF}\u{E0020}-\u{E007F}]/u.test(s.slice(c))).toBe(false);
        }
      }
    }
  });

  it('pair regional indicators from the start of their run', () => {
    for (let n = 1; n <= 7; n++) {
      const cuts = graphemeCuts(`x${'\u{1F1FA}'.repeat(n)}y`);
      const inside = cuts.filter((c) => c > 1 && c < 1 + 2 * n);
      expect(inside).toEqual(Array.from({ length: Math.floor((n - 1) / 2) }, (_, k) => 1 + 4 * (k + 1)));
    }
  });

  it('the ellipsis is the longest prefix at a cut that fits: bisection is exact', () => {
    const r = rng(2);
    for (let n = 0; n < 2000; n++) {
      const s = normalizeLabel(randomLabel(r, 2 + Math.floor(r() * 14)));
      if (!s) continue;
      const box = 6 + Math.floor(r() * 60);
      for (const useSegmenter of [true, false]) {
        let want = s;
        if (widthOf(s) > box) {
          want = ELLIPSIS;
          for (const c of graphemeCuts(s, useSegmenter)) {
            const p = s.slice(0, c).replace(/ +$/, '') + ELLIPSIS;
            if (widthOf(p) <= box) want = p;
          }
        }
        expect(ellipsize(s, box, widthOf, useSegmenter), `${JSON.stringify(s)} in ${box}`).toBe(want);
      }
    }
  });

  it('the cap never splits a cluster', () => {
    const r = rng(3);
    for (let n = 0; n < 300; n++) {
      const s = randomLabel(r, 150 + Math.floor(r() * 200));
      const capped = capLabel(s);
      expect([...capped].length).toBeLessThanOrEqual(LABEL_MAX_CODE_POINTS);
      if (capped.length < s.length) expect(graphemeCuts(s.slice(0, capped.length + 64))).toContain(capped.length);
    }
  });

  it('without Intl.Segmenter: the guards alone keep marks, flags, ZWJ sequences, skin tones and tags whole', () => {
    const r = rng(4);
    for (let n = 0; n < 2000; n++) {
      // Conjoining jamo aside (below), the fallback cuts exactly where Intl.Segmenter does.
      const s = randomLabel(r, 1 + Math.floor(r() * 12)).replace(FALLBACK_GAPS, 'k');
      expect(graphemeCuts(s, false), JSON.stringify(s)).toEqual(graphemeCuts(s));
    }
    // What it gives up: conjoining jamo, which UAX #29 joins into one syllable, can be cut between its parts.
    expect(graphemeCuts('각')).toEqual([3]);
    expect(graphemeCuts('각', false)).toEqual([1, 2, 3]);
    expect(codePointEnds('a\u{1F600}b')).toEqual([1, 3, 4]);
  });
});

describe('bounded work for long labels', () => {
  const long: [string, string][] = [
    ['one word', 'a'.repeat(100_000)],
    ['many words', 'word '.repeat(20_000)],
    ['CJK', '在庫'.repeat(50_000)],
    ['combining marks', 'é'.repeat(50_000)],
    ['hyphens', 'a-'.repeat(50_000)],
    ['surrogate pairs', '\u{1F600}'.repeat(50_000)],
    ['flags', '\u{1F1FA}\u{1F1F8}'.repeat(25_000)],
    ['ZWJ families', '\u{1F468}\u200d\u{1F469}\u200d\u{1F467}'.repeat(12_500)],
    ['one giant cluster', 'e' + '́'.repeat(100_000)],
  ];
  it.each(long)('%s: 100k characters', (_, label) => {
    expect(label.length).toBeGreaterThanOrEqual(100_000);
    let calls = 0;
    let measured = 0;
    const measure = (s: string) => {
      calls++;
      measured += s.length;
      return [...s].length * 8;
    };
    const t0 = performance.now();
    const lines = labelLines(label, 72, 2, measure);
    const ms = performance.now() - t0;
    expect(lines.length).toBeGreaterThan(0);
    expect(lines.length).toBeLessThanOrEqual(2);
    for (const l of lines) expect(measure(l)).toBeLessThanOrEqual(72);
    // Only the first 256 code points are ever measured, a bounded number of times.
    expect(calls).toBeLessThan(600);
    expect(measured).toBeLessThan(600 * 2 * LABEL_MAX_CODE_POINTS);
    expect(ms).toBeLessThan(200);
  });
});
