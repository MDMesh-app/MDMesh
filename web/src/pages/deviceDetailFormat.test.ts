import { describe, expect, it } from 'vitest';
import { DEVICE_RESET_REVISION } from '../api/configSync';
import { LOCATION_HISTORY_LIMIT } from '../api/deviceLocations';
import { EVENT_LABELS, EVENT_VERBS } from '../data/deviceActivity';
import { LOCATION_RANGES, eventTone, fixesCount, locationSince, rangePhrase, shortRevision } from './deviceDetailFormat';

describe('the agent’s storage events (#220)', () => {
  it('are named and shown as warnings', () => {
    expect(EVENT_LABELS.storageRecovered).toBe('Storage recovered');
    expect(EVENT_LABELS.configReapplyFailed).toBe('Config re-apply failed');
    expect(EVENT_VERBS.storageRecovered).toBe('recovered damaged storage');
    expect(EVENT_VERBS.configReapplyFailed).toBe('failed to re-apply its configuration');
    expect(eventTone('storageRecovered')).toBe('warn');
    expect(eventTone('configReapplyFailed')).toBe('warn');
    expect(eventTone('kioskCrashLoop')).toBe('warn');
    expect(eventTone('wipeNotStarted')).toBe('warn');
    expect(eventTone('boot')).toBe('idle');
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

describe('a configuration revision for display', () => {
  it('shows 7 characters, the agent’s out-of-date marker in words, and a dash for none', () => {
    expect(shortRevision('8b04d7aa91c3e5f0')).toBe('8b04d7a');
    expect(shortRevision(DEVICE_RESET_REVISION)).toBe('Out of date');
    expect(shortRevision(null)).toBe('—');
    expect(shortRevision('')).toBe('—');
  });
});

describe('an eventsDropped event', () => {
  it('reads as a warning with its own wording', () => {
    expect(eventTone('eventsDropped')).toBe('warn');
    expect(EVENT_LABELS.eventsDropped).toBe('Events dropped');
    expect(EVENT_VERBS.eventsDropped).toBe('dropped events it could not send');
  });
});
