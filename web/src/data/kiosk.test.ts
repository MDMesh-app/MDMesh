import { describe, expect, it } from 'vitest';
import type { CommandHistoryItem } from '../api/commands';
import { configuredKioskState } from './kiosk';

const command = (type: string, createdAt: number): CommandHistoryItem => ({
  id: createdAt,
  type,
  status: 'done',
  createdAt,
});

describe('configuredKioskState', () => {
  const state = (generation: number | null, startGeneration: number | null, history: CommandHistoryItem[] = [], kioskActive = true, inSync = true) =>
    configuredKioskState({ history, kioskActive, inSync, kioskGeneration: generation, configuredKioskStartGeneration: startGeneration, configuredIds: new Set() });

  it('recognizes a local configured start at the current server-owned generation', () => {
    expect(state(2, 2, [command('kiosk.exit', 100)])).toBe('active');
  });

  it('keeps recognizing a configured kiosk command sent by this console', () => {
    expect(
      configuredKioskState({
        history: [command('kiosk.enter', 200)],
        kioskActive: true,
        inSync: true,
        kioskGeneration: null,
        configuredKioskStartGeneration: null,
        configuredIds: new Set(['200']),
      }),
    ).toBe('active');
  });

  it('keeps Return available when a newer custom kiosk command advances the generation', () => {
    expect(state(3, 2, [command('kiosk.enter', 200)])).toBe('ready');
  });

  it('keeps Return available while kiosk telemetry or configuration reconciliation disagrees', () => {
    expect(state(2, 2, [], false)).toBe('ready');
    expect(state(2, 2, [], true, false)).toBe('ready');
  });

  it('does not depend on device event timestamps or the event-page limit', () => {
    expect(state(42, 42)).toBe('active');
  });
});
