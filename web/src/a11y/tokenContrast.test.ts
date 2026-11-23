import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseColour, parseTokens, ratio, tokenRatio } from './contrast';
import { MIN, PAIRS } from './contrastPairs';

// Read from disk: Vitest hands CSS imports (even ?raw) to its CSS pipeline, which empties them.
const themes = parseTokens(readFileSync(resolve(import.meta.dirname, '../tokens.css'), 'utf8'));

describe('contrast maths', () => {
  it('matches the WCAG reference values', () => {
    expect(ratio(parseColour('#000'), parseColour('#fff'))).toBeCloseTo(21, 5);
    expect(ratio(parseColour('#767676'), parseColour('#fff'))).toBeCloseTo(4.54, 2);
  });
});

describe.each(['light', 'dark'] as const)('design tokens, %s theme', (theme) => {
  const failures = PAIRS.filter((p) => p.kind !== 'exempt')
    .map((p) => ({ ...p, ...tokenRatio(themes[theme], p.fg, p.bg, p.base) }))
    .filter((p) => p.ratio < MIN[p.kind])
    .map((p) => `${p.element}: --${p.fg} ${p.fg} on --${p.bg} ${p.bg} = ${p.ratio.toFixed(2)}:1, needs ${MIN[p.kind]}:1`);

  it('every pair reaches its minimum contrast', () => {
    expect(failures).toEqual([]);
  });
});
