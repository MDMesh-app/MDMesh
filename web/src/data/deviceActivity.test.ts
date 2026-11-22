import { describe, expect, it } from 'vitest';
import type { CommandHistoryItem } from '../api/commands';
import { EVENT_LABELS, EVENT_VERBS, eventDetail } from './deviceActivity';

const cmd = (c: Partial<CommandHistoryItem> & { id: number | string; type: string }): CommandHistoryItem => ({ status: 'done', ...c });
const commands = [
  cmd({ id: 1234, type: 'device.lock' }),
  cmd({ id: 1300, type: 'app.install', status: 'failed', subject: 'com.acme.scan', detail: 'checksum mismatch' }),
  cmd({ id: 1301, type: 'x.custom' }),
  cmd({ id: 1302, type: 'config.sync' }),
];

describe('eventDetail: commandResult', () => {
  it('names the command and its result, not "1234:done"', () => {
    expect(eventDetail({ type: 'commandResult', detail: '1234:done' }, { commands })).toBe('Lock: done');
  });

  it('adds the package and the failure reason when the result is the command’s final one', () => {
    expect(eventDetail({ type: 'commandResult', detail: '1300:failed' }, { commands })).toBe(
      'Install app: failed · com.acme.scan · checksum mismatch',
    );
  });

  it('leaves the final detail off an earlier step of the same command', () => {
    expect(eventDetail({ type: 'commandResult', detail: '1300:accepted' }, { commands })).toBe(
      'Install app: running · com.acme.scan',
    );
  });

  it('says the step in words', () => {
    expect(eventDetail({ type: 'commandResult', detail: '1234:unsupported' }, { commands })).toBe('Lock: not supported');
    expect(eventDetail({ type: 'commandResult', detail: '1234:expired' }, { commands })).toBe('Lock: expired');
  });

  it('names a command the console has no name for by its type', () => {
    expect(eventDetail({ type: 'commandResult', detail: '1301:done' }, { commands })).toBe('x.custom: done');
    expect(eventDetail({ type: 'commandResult', detail: '1302:done' }, { commands })).toBe('Sync configuration: done');
  });

  it('without the command in the history: its number and the result', () => {
    expect(eventDetail({ type: 'commandResult', detail: '99:done' }, { commands })).toBe('Command #99: done');
    expect(eventDetail({ type: 'commandResult', detail: '99:failed' })).toBe('Command #99: failed');
  });

  it('keeps a detail it can’t parse as it is', () => {
    expect(eventDetail({ type: 'commandResult', detail: 'odd text' }, { commands })).toBe('odd text');
    expect(eventDetail({ type: 'commandResult', detail: null })).toBeUndefined();
  });
});

describe('eventDetail: device events', () => {
  it('app installs and removals: the library name, else the package', () => {
    const appNames = { 'com.acme.scan': 'Scanner' };
    expect(eventDetail({ type: 'appInstalled', detail: 'com.acme.scan' }, { appNames })).toBe('Scanner');
    expect(eventDetail({ type: 'appUninstalled', detail: ' com.other ' }, { appNames })).toBe('com.other');
  });

  it('network and low battery: the detail when the agent sends one, else nothing', () => {
    expect(eventDetail({ type: 'connectivityChange', detail: 'Wi-Fi' })).toBe('Wi-Fi');
    expect(eventDetail({ type: 'lowBattery', detail: '14%' })).toBe('14%');
    expect(eventDetail({ type: 'connectivityChange', detail: null })).toBeUndefined();
    expect(eventDetail({ type: 'lowBattery', detail: '  ' })).toBeUndefined();
  });

  it('kiosk exit and return: says where it happened in words', () => {
    expect(eventDetail({ type: 'kioskExit', detail: 'exited on-device' })).toBe('With the exit PIN, on the device');
    expect(eventDetail({ type: 'kioskReturn', detail: 'returned on-device' })).toBe('From the device');
  });

  it('anything else: the agent’s detail', () => {
    expect(eventDetail({ type: 'kioskPinLockout', detail: '5 wrong exit PINs; exit locked for 5 min' })).toBe(
      '5 wrong exit PINs; exit locked for 5 min',
    );
    expect(eventDetail({ type: 'somethingNew', detail: 'x' })).toBe('x');
    expect(eventDetail({ type: 'boot' })).toBeUndefined();
  });
});

describe('event labels and verbs', () => {
  it('cover the kiosk exit and return', () => {
    expect(EVENT_LABELS.kioskExit).toBe('Left kiosk mode');
    expect(EVENT_LABELS.kioskReturn).toBe('Returned to kiosk mode');
    expect(EVENT_VERBS.kioskExit).toBe('left kiosk mode');
    expect(EVENT_VERBS.kioskReturn).toBe('returned to kiosk mode');
  });

  it('give every labelled event a verb, and the other way round', () => {
    expect(Object.keys(EVENT_VERBS).sort()).toEqual(Object.keys(EVENT_LABELS).sort());
  });
});
