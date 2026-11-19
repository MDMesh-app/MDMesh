import { describe, expect, it } from 'vitest';
import { DEVICE_RESET_LABEL, DEVICE_RESET_REVISION, staleCause, summarizeStatus, type ConfigStatus } from './configSync';

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

  it('names the cause from the latest event that marks it, ignoring other events', () => {
    expect(
      staleCause([
        { id: 1, type: 'storageRecovered', ts: 100 },
        { id: 2, type: 'wipeNotStarted', ts: 300 },
        { id: 3, type: 'boot', ts: 400 },
        { id: 4, type: 'configReapplyFailed', ts: 200 },
      ]),
    ).toBe('A factory reset didn’t start, so the device went back to its settings.');
    expect(staleCause([{ id: 1, type: 'storageRecovered', ts: 1 }])).toBe(
      'The device restored settings its storage had damaged.',
    );
    expect(staleCause([{ id: 1, type: 'configReapplyFailed', ts: 1 }])).toBe(
      'The device couldn’t fully apply its configuration again after a restart.',
    );
  });

  it('names no cause when no such event is known', () => {
    expect(staleCause([])).toBeNull();
    expect(staleCause([{ id: 1, type: 'boot', ts: 1 }])).toBeNull();
    expect(staleCause(undefined)).toBeNull();
  });
});
