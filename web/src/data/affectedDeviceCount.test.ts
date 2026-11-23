import { describe, expect, it } from 'vitest';
import type { ConfigSyncSummary } from '../api/configSync';
import { affectedDeviceCount } from './configurationRules';

const row = (total: number, unsupported: number, wiped?: number) =>
  ({ configurationId: 1, total, inSync: 0, outOfSync: 0, unsupported, neverSeen: 0, deferred: 0, wiped }) as ConfigSyncSummary;

describe('affectedDeviceCount', () => {
  it('leaves out agents too old to re-apply', () => {
    expect(affectedDeviceCount(row(3, 1))).toBe(2);
  });

  it('fails closed without a summary', () => {
    expect(affectedDeviceCount(undefined)).toBeNull();
  });

  // A restricted admin may see only the wiped device of a configuration with live devices they can't see.
  it('fails closed when the only visible devices are wiped', () => {
    expect(affectedDeviceCount(row(0, 0, 1))).toBeNull();
  });
});
