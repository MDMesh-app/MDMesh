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
  const state = (
    generation: number | null,
    returnGeneration: number | null,
    history: CommandHistoryItem[] = [],
    kioskActive = true,
    inSync = true,
  ) => configuredKioskState({
    history,
    kioskActive,
    inSync,
    kioskGeneration: generation,
    kioskReturnGeneration: returnGeneration,
    configuredIds: new Set(),
  });

  it('recognizes an on-device return at the current server-owned generation', () => {
    expect(state(2, 2, [command('kiosk.exit', 100)])).toBe('active');
  });

  it('keeps Return available after a later kiosk command advances the generation', () => {
    expect(state(3, 2, [command('kiosk.enter', 200)])).toBe('ready');
  });

  it('requires current kiosk telemetry and configuration reconciliation', () => {
    expect(state(2, 2, [], false)).toBe('ready');
    expect(state(2, 2, [], true, false)).toBe('ready');
  });

  it('keeps recognizing a configured kiosk command sent by this console', () => {
    expect(configuredKioskState({
      history: [command('kiosk.enter', 200)],
      kioskActive: true,
      inSync: true,
      kioskGeneration: null,
      kioskReturnGeneration: null,
      configuredIds: new Set(['200']),
    })).toBe('active');
  });
});
