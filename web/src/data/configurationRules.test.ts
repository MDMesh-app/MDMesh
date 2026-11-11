import { describe, expect, it } from 'vitest';
import type { Configuration } from '../api/configurations';
import { locationServicesAction, systemUpdateProblem } from './configurationRules';

const cfg = (c: Partial<Configuration>) => ({ name: 'Warehouse', ...c }) as Configuration;
const keyOf = (c: Partial<Configuration>) => systemUpdateProblem(cfg(c))?.key ?? null;

describe('the system update setting, as the server checks it', () => {
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

  it('refuses a type outside 0..3, and points at the type', () => {
    expect(systemUpdateProblem(cfg({ systemUpdateType: 5 }))).toEqual({
      key: 'systemUpdateType',
      message: 'Choose how devices install system updates.',
    });
    expect(keyOf({ systemUpdateType: -1 })).toBe('systemUpdateType');
  });

  it('against a stored row, refuses only what the save changes', () => {
    const blank = cfg({ systemUpdateType: 2, systemUpdateFrom: '', systemUpdateTo: null });
    const check = (c: Partial<Configuration>, stored: Configuration) => systemUpdateProblem(cfg(c), stored)?.key ?? null;

    // Unchanged, however bad; a blank time and null are the same, and a cleared field gives null.
    expect(check({ ...blank, description: 'Back room' }, blank)).toBeNull();
    expect(check({ systemUpdateType: 2, systemUpdateFrom: null, systemUpdateTo: ' ' }, blank)).toBeNull();
    const type5 = cfg({ systemUpdateType: 5 });
    expect(check({ systemUpdateType: 5 }, type5)).toBeNull();

    // Changed: the whole setting is checked again.
    expect(check({ systemUpdateType: 2, systemUpdateFrom: '22:00', systemUpdateTo: null }, blank)).toBe('systemUpdateTo');
    expect(check({ systemUpdateType: 2 }, cfg({ systemUpdateType: 0 }))).toBe('systemUpdateFrom');
    expect(check({ systemUpdateType: 4 }, type5)).toBe('systemUpdateType');
    // Off a daily window, its times don't matter.
    expect(check({ systemUpdateType: 0 }, blank)).toBeNull();
  });
});

describe('locationServicesAction', () => {
  it('marks every explicit editor selection so the server can distinguish it from an older client omission', () => {
    expect(locationServicesAction(true)).toBe('on');
    expect(locationServicesAction(false)).toBe('off');
    expect(locationServicesAction(null)).toBe('notManaged');
  });
});
