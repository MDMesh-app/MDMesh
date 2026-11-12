import { describe, expect, it } from 'vitest';
import type { EnrolledDevice } from '../api/enroll';
import { arrivalsSince, SETTING_UP_MS } from './arrivals';

const NOW = 1_700_000_000_000;
const dev = (number: string, enrollTime: number, lastUpdate?: number, description?: string): EnrolledDevice => ({
  number,
  enrollTime,
  lastUpdate,
  description,
});

describe('arrivalsSince', () => {
  it('keeps only devices enrolled since the code was made, newest first', () => {
    const list = arrivalsSince([dev('old', NOW - 60_000), dev('a', NOW + 1_000), dev('b', NOW + 5_000)], NOW, NOW + 10_000);
    expect(list.map((a) => a.number)).toEqual(['b', 'a']);
  });

  it('tells a device still setting up from one that checked in', () => {
    expect(arrivalsSince([dev('a', NOW, NOW)], NOW, NOW + 1_000)[0].state).toBe('settingUp');
    expect(arrivalsSince([dev('a', NOW, NOW + 2_000)], NOW, NOW + 3_000)[0].state).toBe('online');
    expect(arrivalsSince([dev('a', NOW, NOW + 2_000)], NOW, NOW + 60 * 60_000)[0].state).toBe('offline');
  });

  it('says so when a device that enrolled never checked in', () => {
    expect(arrivalsSince([dev('a', NOW, NOW)], NOW, NOW + SETTING_UP_MS - 1)[0].state).toBe('settingUp');
    expect(arrivalsSince([dev('a', NOW, NOW)], NOW, NOW + SETTING_UP_MS + 1)[0].state).toBe('silent');
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
