import { describe, expect, it } from 'vitest';
import type { DeviceView } from '../api/devices';
import { arrivalsSince } from './arrivals';

const NOW = 1_700_000_000_000;
const dev = (number: string, enrollTime?: number, lastUpdate?: number, description?: string): DeviceView => ({
  id: 1,
  number,
  enrollTime,
  lastUpdate,
  description,
});

describe('arrivalsSince', () => {
  it('keeps only devices enrolled since the code was made, newest first', () => {
    const list = arrivalsSince(
      [dev('old', NOW - 60_000), dev('a', NOW + 1_000), dev('legacy'), dev('b', NOW + 5_000)],
      NOW,
      NOW + 10_000,
    );
    expect(list.map((a) => a.number)).toEqual(['b', 'a']);
  });

  it('tells a device still setting up from one that checked in', () => {
    const [settingUp] = arrivalsSince([dev('a', NOW, NOW)], NOW, NOW + 1_000);
    expect(settingUp.state).toBe('settingUp');
    const [online] = arrivalsSince([dev('a', NOW, NOW + 2_000)], NOW, NOW + 3_000);
    expect(online.state).toBe('online');
    const [offline] = arrivalsSince([dev('a', NOW, NOW + 2_000)], NOW, NOW + 60 * 60_000);
    expect(offline.state).toBe('offline');
  });

  it('names a device by its description, else its number', () => {
    expect(arrivalsSince([dev('n1', NOW, NOW, ' Front desk ')], NOW)[0].name).toBe('Front desk');
    expect(arrivalsSince([dev('n1', NOW, NOW, '  ')], NOW)[0].name).toBe('n1');
  });

  it('copes with no answer', () => {
    expect(arrivalsSince(null, NOW)).toEqual([]);
    expect(arrivalsSince(undefined, NOW)).toEqual([]);
  });
});
