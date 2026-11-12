import { describe, expect, it } from 'vitest';
import type { EnrolledDevice } from '../api/enroll';
import { ARRIVAL_LABEL, arrivalChanges, arrivalNews, arrivalsSince, SETTING_UP_MS, type Arrival } from './arrivals';

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

describe('arrivalChanges', () => {
  const a = (number: string, state: Arrival['state']): Arrival => ({ number, name: number, enrollTime: 1, state });

  it('is every device on the first look', () => {
    expect(arrivalChanges([], [a('d1', 'settingUp'), a('d2', 'online')]).map((x) => x.number)).toEqual(['d1', 'd2']);
  });

  it('is only new devices and those whose state moved', () => {
    const prev = [a('d1', 'settingUp'), a('d2', 'online')];
    const next = [a('d3', 'settingUp'), a('d1', 'online'), a('d2', 'online')];
    expect(arrivalChanges(prev, next).map((x) => `${x.number}:${x.state}`)).toEqual(['d3:settingUp', 'd1:online']);
  });

  it('is nothing when a look changed nothing', () => {
    const list = [a('d1', 'online')];
    expect(arrivalChanges(list, [a('d1', 'online')])).toEqual([]);
  });
});

describe('arrivalNews', () => {
  const a = (number: string, state: Arrival['state'], name = number): Arrival => ({ number, name, enrollTime: NOW, state });

  it('names a single device and its state, in a whole sentence', () => {
    expect(arrivalNews([], [a('n1', 'settingUp', 'Front desk')])).toEqual(['Front desk: Enrolled, getting its configuration….']);
    expect(arrivalNews([a('n1', 'settingUp')], [a('n1', 'online')])).toEqual(['n1: Online.']);
  });

  it('counts devices that enroll in one look instead of naming each', () => {
    const three = [a('n1', 'settingUp'), a('n2', 'settingUp'), a('n3', 'online')];
    expect(arrivalNews([], three)).toEqual(['3 devices enrolled.']);
  });

  it('counts devices that change state together, by state', () => {
    const before = [a('n1', 'settingUp'), a('n2', 'settingUp'), a('n3', 'settingUp'), a('n4', 'settingUp')];
    const after = [a('n1', 'online'), a('n2', 'online'), a('n3', 'silent'), a('n4', 'settingUp')];
    expect(arrivalNews(before, after)).toEqual(['2 devices are online.', 'n3: Enrolled, hasn’t checked in yet.']);
  });

  it('says both when devices enroll and others change state in the same look', () => {
    const before = [a('n1', 'settingUp')];
    const after = [a('n1', 'online'), a('n2', 'settingUp'), a('n3', 'settingUp')];
    expect(arrivalNews(before, after)).toEqual(['2 devices enrolled.', 'n1: Online.']);
  });

  it('is nothing when a look changed nothing', () => {
    expect(arrivalNews([a('n1', 'online')], [a('n1', 'online')])).toEqual([]);
  });

  it('ends every state label as a whole phrase (the pill and the announcement read it)', () => {
    for (const label of Object.values(ARRIVAL_LABEL)) expect(label).not.toMatch(/\b(since|but|and|from)$/);
    expect(ARRIVAL_LABEL.offline).toBe('Enrolled, but not heard from in the last 10 minutes');
  });
});
