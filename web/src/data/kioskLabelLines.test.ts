import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ELLIPSIS, LABEL_MAX_CODE_POINTS, cachedLabelLines, capLabel, ellipsize, labelLines, normalizeLabel } from './kioskLabelLines';

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

describe('label lines vectors (3.3, shared with the agent)', () => {
  it('has all 17 cases', () => {
    expect(VECTORS.labelLines).toHaveLength(17);
  });
  it.each(VECTORS.labelLines.map((c) => [c.name, c] as const))('%s', (_, c) => {
    expect(c.widths).toHaveLength(c.text.length);
    expect(labelLines(c.text, c.box, c.maxLines, vectorMeasure(c))).toEqual(c.expected);
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
    const family = '\u{1F468}‍\u{1F469}';
    const heart = '❤️';
    const m = (s: string) => [...s].filter((ch) => ch !== '‍' && ch !== '️').length * 10;
    expect(ellipsize(`ab${family}cd`, 36, m)).toBe(`ab${ELLIPSIS}`);
    expect(ellipsize(`ab${heart}cd`, 36, m)).toBe(`ab${ELLIPSIS}`);
    expect(ellipsize(`ab${heart}cd`, 46, m)).toBe(`ab${heart}${ELLIPSIS}`);
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

describe('bounded work for long labels', () => {
  const long: [string, string][] = [
    ['one word', 'a'.repeat(100_000)],
    ['many words', 'word '.repeat(20_000)],
    ['CJK', '在庫'.repeat(50_000)],
    ['combining marks', 'é'.repeat(50_000)],
    ['hyphens', 'a-'.repeat(50_000)],
    ['surrogate pairs', '\u{1F600}'.repeat(50_000)],
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
