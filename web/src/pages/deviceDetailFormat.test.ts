import { describe, expect, it } from 'vitest';
import { LOCATION_HISTORY_LIMIT } from '../api/deviceLocations';
import { fmtDateTime } from '../ui/format';
import { LOCATION_RANGES, fixesCount, locationSince, pendingUpdateLabel, rangePhrase } from './deviceDetailFormat';

describe('a waiting OS update (telemetry security)', () => {
  const at = Date.UTC(2026, 10, 3, 9, 30);

  it('says since when it waits, and whether it is a security patch when the device knows', () => {
    const since = `Waiting since ${fmtDateTime(at)}`;
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: at, pendingSystemUpdateSecurityPatch: true })).toBe(`${since} (security patch)`);
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: at, pendingSystemUpdateSecurityPatch: false })).toBe(
      `${since} (not a security patch)`,
    );
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: at, pendingSystemUpdateSecurityPatch: null })).toBe(since);
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: at })).toBe(since);
  });

  it('says none was reported without a time, and leaves a time that is not a date to a dash', () => {
    expect(pendingUpdateLabel({})).toBe('None reported');
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: null, pendingSystemUpdateSecurityPatch: true })).toBe('None reported');
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: 'yesterday' })).toBeUndefined();
    expect(pendingUpdateLabel({ pendingSystemUpdateAt: -5 })).toBeUndefined();
  });
});

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
