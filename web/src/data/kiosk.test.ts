import { describe, expect, it } from 'vitest';
import type { CommandHistoryItem } from '../api/commands';
import type { DeviceEvent } from '../api/events';
import { configuredKioskState } from './kiosk';

const start = (ts: number): DeviceEvent => ({ id: ts, type: 'configuredKioskStart', ts });
const command = (type: string, createdAt: number): CommandHistoryItem => ({
  id: createdAt,
  type,
  status: 'done',
  createdAt,
});

describe('configuredKioskState', () => {
  const state = (events: DeviceEvent[], history: CommandHistoryItem[] = [], kioskActive = true, inSync = true) =>
    configuredKioskState({ events, history, kioskActive, inSync, configuredIds: new Set() });

  it('recognizes a newer on-device configured start once telemetry and configuration agree', () => {
    expect(state([start(200)], [command('kiosk.exit', 100)])).toBe('active');
  });

  it('keeps recognizing a configured kiosk command sent by this console', () => {
    expect(
      configuredKioskState({
        events: [],
        history: [command('kiosk.enter', 200)],
        kioskActive: true,
        inSync: true,
        configuredIds: new Set(['200']),
      }),
    ).toBe('active');
  });

  it('keeps Return available when a newer custom kiosk command replaces the local configured kiosk', () => {
    expect(state([start(100)], [command('kiosk.enter', 200)])).toBe('ready');
  });

  it('keeps Return available while kiosk telemetry or configuration reconciliation disagrees', () => {
    expect(state([start(200)], [], false)).toBe('ready');
    expect(state([start(200)], [], true, false)).toBe('ready');
  });
});
