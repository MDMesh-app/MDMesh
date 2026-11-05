import { describe, expect, it } from 'vitest';
import type { Configuration } from '../api/configurations';
import { systemUpdateProblem } from './configurationRules';

const cfg = (c: Partial<Configuration>) => ({ name: 'Warehouse', ...c }) as Configuration;
const keyOf = (c: Partial<Configuration>) => systemUpdateProblem(cfg(c))?.key ?? null;

describe('the system update window, as the server checks it', () => {
  it('checks nothing but a daily window (2): the other types ignore the times', () => {
    for (const systemUpdateType of [0, 1, 3, undefined]) {
      expect(systemUpdateProblem(cfg({ systemUpdateType, systemUpdateFrom: null, systemUpdateTo: '25:00' }))).toBeNull();
    }
  });

  it('takes two different 24-hour HH:MM times, across midnight too', () => {
    expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: '01:00', systemUpdateTo: '05:30' })).toBeNull();
    expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: '22:00', systemUpdateTo: '04:00' })).toBeNull();
    expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: '00:00', systemUpdateTo: '23:59' })).toBeNull();
    // The server trims them.
    expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: ' 22:00', systemUpdateTo: '04:00 ' })).toBeNull();
  });

  it('points at a missing or malformed start, then end', () => {
    expect(systemUpdateProblem(cfg({ systemUpdateType: 2 }))).toEqual({
      key: 'systemUpdateFrom',
      message: 'Set the time the daily window starts.',
    });
    for (const bad of [null, '', '24:00', '9:00', '12:60', '12:00:00', 'noon', 540]) {
      expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: bad, systemUpdateTo: '04:00' })).toBe('systemUpdateFrom');
      expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: '22:00', systemUpdateTo: bad })).toBe('systemUpdateTo');
    }
    expect(systemUpdateProblem(cfg({ systemUpdateType: 2, systemUpdateFrom: '22:00' }))?.message).toBe(
      'Set the time the daily window ends.',
    );
  });

  it('refuses a window that ends when it starts', () => {
    expect(systemUpdateProblem(cfg({ systemUpdateType: 2, systemUpdateFrom: '03:00', systemUpdateTo: '03:00' }))).toEqual({
      key: 'systemUpdateTo',
      message: 'Pick an end time different from the start.',
    });
    expect(keyOf({ systemUpdateType: 2, systemUpdateFrom: '03:00 ', systemUpdateTo: '03:00' })).toBe('systemUpdateTo');
  });
});
