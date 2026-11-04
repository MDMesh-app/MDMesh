import { describe, expect, it } from 'vitest';
import type { ConfigSyncSummary } from '../api/configSync';
import type { DeviceView } from '../api/devices';
import { presenceOf, suspectDevices } from './fleetHealth';
import { ONLINE_WINDOW_MS } from './presence';

const NOW = 1_800_000_000_000;
const RECENT = NOW - 60_000;
const QUIET = NOW - ONLINE_WINDOW_MS - 60_000;

describe('presenceOf', () => {
  it('is offline, attention or online by the last check-in and a configuration issue', () => {
    expect(presenceOf(QUIET, false, NOW)).toBe('offline');
    expect(presenceOf(undefined, true, NOW)).toBe('offline');
    expect(presenceOf(RECENT, true, NOW)).toBe('attention');
    expect(presenceOf(RECENT, false, NOW)).toBe('online');
  });

  // #137: the phone erases itself before it can report, so its last check-in says nothing.
  it('is wiped once the server delivered a factory reset, whatever the last check-in and configuration say', () => {
    const wipedAt = NOW - 2 * 3600_000;
    expect(presenceOf(RECENT, false, NOW, wipedAt)).toBe('wiped');
    expect(presenceOf(RECENT, true, NOW, wipedAt)).toBe('wiped');
    expect(presenceOf(QUIET, false, NOW, wipedAt)).toBe('wiped');
    expect(presenceOf(undefined, false, NOW, wipedAt)).toBe('wiped');
    expect(presenceOf(RECENT, false, NOW, undefined)).toBe('online');
  });
});

describe('suspectDevices', () => {
  const summary: ConfigSyncSummary[] = [
    { configurationId: 3, total: 3, inSync: 1, outOfSync: 2, unsupported: 0, neverSeen: 0 },
  ];
  const device = (id: number, extra: Partial<DeviceView> = {}): DeviceView => ({
    id,
    number: `WH-${id}`,
    configurationId: 3,
    lastUpdate: RECENT,
    ...extra,
  });

  it('leaves out wiped devices, even one whose last check-in is recent: they can’t need attention', () => {
    const list = [device(1), device(2, { wipedAt: RECENT }), device(3, { lastUpdate: QUIET })];
    expect(suspectDevices(list, summary, NOW).map((d) => d.id)).toEqual([1]);
  });
});
