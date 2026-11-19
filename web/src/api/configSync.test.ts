import { describe, expect, it } from 'vitest';
import {
  DEVICE_RESET_LABEL,
  DEVICE_RESET_REVISION,
  STALE_CAUSE_WINDOW_MS,
  staleCause,
  summarizeStatus,
  type ConfigStatus,
} from './configSync';

// The agent reports 64 zeros when its configuration is out of date on the device: the Wi-Fi radio self-heal, storage
// recovery, a wipe that lifted Factory reset: Block, an unsaved newer configuration, a failed re-apply (#222, #220).
describe('a configuration out of date on the device', () => {
  const status = {
    configurationId: 3,
    currentRevision: 'b'.repeat(64),
    appliedRevision: DEVICE_RESET_REVISION,
    inSync: false,
    supported: true,
  } as ConfigStatus;

  it('gets a neutral label, not the Wi-Fi self-heal', () => {
    expect(DEVICE_RESET_LABEL).toBe('Out of date on the device');
    expect(summarizeStatus(status)).toEqual({ tone: 'warn', label: 'Out of date on the device' });
  });

  // When the device first reported the configuration out of date (server time).
  const appliedAt = 10_000_000;

  it('names the cause from the latest event that marks it, ignoring other events', () => {
    expect(
      staleCause(
        [
          { id: 1, type: 'storageRecovered', ts: appliedAt - 3000 },
          { id: 2, type: 'wipeNotStarted', ts: appliedAt - 1000 },
          { id: 3, type: 'boot', ts: appliedAt },
          { id: 4, type: 'configReapplyFailed', ts: appliedAt - 2000 },
        ],
        appliedAt,
      ),
    ).toBe('A factory reset didn’t start, so the device went back to its settings.');
    expect(staleCause([{ id: 1, type: 'storageRecovered', ts: appliedAt }], appliedAt)).toBe(
      'The device restored settings its storage had damaged.',
    );
    expect(staleCause([{ id: 1, type: 'configReapplyFailed', ts: appliedAt + 5000 }], appliedAt)).toBe(
      'The device couldn’t fully apply its configuration again after a restart.',
    );
  });

  it('names a cause recorded shortly before the report (device clock skew, the check-in after it)', () => {
    const fresh = { id: 1, type: 'storageRecovered', ts: appliedAt - STALE_CAUSE_WINDOW_MS };
    expect(staleCause([fresh], appliedAt)).toBe('The device restored settings its storage had damaged.');
  });

  it('names no cause from an event older than the report: it would mislabel today’s state', () => {
    const old = { id: 1, type: 'storageRecovered', ts: appliedAt - STALE_CAUSE_WINDOW_MS - 1 };
    expect(staleCause([old], appliedAt)).toBeNull();
    expect(staleCause([{ id: 1, type: 'storageRecovered', ts: appliedAt }], null)).toBeNull();
  });

  it('names no cause when no such event is known', () => {
    expect(staleCause([], appliedAt)).toBeNull();
    expect(staleCause([{ id: 1, type: 'boot', ts: appliedAt }], appliedAt)).toBeNull();
    expect(staleCause(undefined, appliedAt)).toBeNull();
  });
});
