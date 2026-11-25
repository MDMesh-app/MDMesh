// The lines under a configuration, and the count the Delete dialog warns with. Wiped devices sit outside total, but the
// server's delete rule counts them.
import { createIntl } from 'react-intl';
import { describe, expect, it } from 'vitest';
import type { ConfigSyncSummary } from '../../api/configSync';
import { devicesBlockingDelete, syncLine, usedByLine, wipedOnly, wipedOnlyLine } from './sync';

const intl = createIntl({ locale: 'en' });

const row = (extra: Partial<ConfigSyncSummary> = {}): ConfigSyncSummary => ({
  configurationId: 3,
  total: 0,
  inSync: 0,
  outOfSync: 0,
  unsupported: 0,
  neverSeen: 0,
  ...extra,
});

describe('a configuration with only wiped devices', () => {
  it('says so instead of "Not used by any device yet"', () => {
    expect(syncLine(intl, row({ wiped: 1 }), false)).toEqual({ segments: [], text: 'Used only by 1 wiped device', tone: 'idle' });
    expect(syncLine(intl, row({ wiped: 2 }), false).text).toBe('Used only by 2 wiped devices');
  });

  it('says so even when the rest of the page could not be checked', () => {
    expect(syncLine(intl, row({ wiped: 1 }), true).text).toBe('Used only by 1 wiped device');
  });

  it('has no "Used by" line, so the editor falls to the wiped one', () => {
    expect(usedByLine(row({ wiped: 1 }))).toBeNull();
    expect(wipedOnly(row({ wiped: 1 }))).toBe(1);
    expect(wipedOnlyLine(row({ wiped: 1 }))).toBe('Used only by 1 wiped device');
  });
});

describe('a configuration with live devices', () => {
  const s = row({ total: 2, inSync: 2, wiped: 1 });

  it('reads as its live devices, wiped ones aside', () => {
    expect(syncLine(intl, s, false).text).toBe('All 2 in sync');
    expect(usedByLine(s)).toEqual({ count: 2, rest: ', both in sync.' });
    expect(wipedOnly(s)).toBe(0);
    expect(wipedOnlyLine(s)).toBeNull();
  });
});

describe('devices whose agent needs an update for a section the configuration sets', () => {
  it('are named next to the sync counts, which they stay part of', () => {
    expect(syncLine(intl, row({ total: 2, inSync: 2, agentUpdateNeeded: 1 }), false)).toMatchObject({
      text: 'All 2 in sync · 1 agent update needed',
      tone: 'warn',
    });
    expect(syncLine(intl, row({ total: 3, inSync: 2, neverSeen: 1, agentUpdateNeeded: 2 }), false)).toMatchObject({
      text: '2 of 3 in sync · 2 agent update needed · 1 not reported yet',
      tone: 'warn',
    });
  });
});

describe('an unused configuration, and an older server that sends no wiped count', () => {
  it('still reads as unused', () => {
    expect(syncLine(intl, row(), false).text).toBe('Not used by any device yet');
    expect(syncLine(intl, row({ wiped: 0 }), false).text).toBe('Not used by any device yet');
    expect(syncLine(intl, undefined, false).text).toBe('Not used by any device yet');
    expect(syncLine(intl, undefined, true).text).toBe('Sync status unavailable');
    expect(wipedOnlyLine(row())).toBeNull();
    expect(wipedOnlyLine(undefined)).toBeNull();
  });
});

describe('devicesBlockingDelete', () => {
  it('counts what the server counts: live and wiped', () => {
    expect(devicesBlockingDelete(row({ wiped: 1 }))).toBe(1);
    expect(devicesBlockingDelete(row({ total: 2, inSync: 2, wiped: 1 }))).toBe(3);
  });

  it('is the total on an older server, and unknown with no row', () => {
    expect(devicesBlockingDelete(row({ total: 2, inSync: 2 }))).toBe(2);
    expect(devicesBlockingDelete(row())).toBe(0);
    expect(devicesBlockingDelete(undefined)).toBeUndefined();
  });
});
