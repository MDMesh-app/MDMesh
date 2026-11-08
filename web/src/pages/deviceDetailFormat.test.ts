import { describe, expect, it } from 'vitest';
import { LOCATION_HISTORY_LIMIT } from '../api/deviceLocations';
import { LOCATION_RANGES, fixesCount, locationSince, rangePhrase } from './deviceDetailFormat';

describe('the location history range', () => {
  const now = Date.UTC(2026, 10, 4, 12);

  it('asks for every fix for All, and for the fixes since `hours` ago otherwise', () => {
    expect(locationSince(0, now)).toBe(0);
    expect(locationSince(1, now)).toBe(now - 3600 * 1000);
    expect(locationSince(168, now)).toBe(now - 7 * 24 * 3600 * 1000);
  });

  it('names each range in a sentence, and none for All', () => {
    expect(LOCATION_RANGES.map((r) => rangePhrase(r.value))).toEqual([
      'the last hour',
      'the last 24 hours',
      'the last 7 days',
      undefined,
    ]);
  });

  it('counts the fixes, and says when the server’s cap cut them', () => {
    expect(fixesCount(0, 1)).toBe('0 fixes');
    expect(fixesCount(1, 0)).toBe('1 fix');
    expect(fixesCount(LOCATION_HISTORY_LIMIT - 1, 24)).toBe('499 fixes');
    expect(fixesCount(LOCATION_HISTORY_LIMIT, 24)).toBe('newest 500 in this range');
    expect(fixesCount(LOCATION_HISTORY_LIMIT, 0)).toBe('newest 500 fixes');
  });
});
