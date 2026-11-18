import { describe, expect, it } from 'vitest';
import type { Configuration } from '../api/configurations';
import { EXIT_PIN_RULE, exitPinProblem, exitPinValid, hasLegacyPin, locationServicesAction } from './configurationRules';

describe('locationServicesAction', () => {
  it('marks every explicit editor selection so the server can distinguish it from an older client omission', () => {
    expect(locationServicesAction(true)).toBe('on');
    expect(locationServicesAction(false)).toBe('off');
    expect(locationServicesAction(null)).toBe('notManaged');
  });
});

describe('the exit PIN rule (as the server checks it)', () => {
  it('takes 6 to 12 digits, or no PIN', () => {
    for (const ok of [null, undefined, '', '482139', '000000', '123456789012']) expect(exitPinValid(ok)).toBe(true);
  });

  it('refuses anything else', () => {
    const bad = ['12345', '1234567890123', '48213a', '482 139', ' 482139', '482139 ', '４８２１３９', '١٢٣٤٥٦', '+482139',
      '48.2139', 'letmeout', ' ', '482139\n', '1e6123'];
    for (const pin of bad) expect(exitPinValid(pin)).toBe(false);
  });

  it('lets the PIN already saved stay as it is, but not a change to another invalid one', () => {
    expect(exitPinProblem('4931', '4931')).toBeNull();
    expect(exitPinProblem('4932', '4931')).toBe(EXIT_PIN_RULE);
    expect(exitPinProblem('482139', '4931')).toBeNull();
    expect(exitPinProblem('', '4931')).toBeNull();
    expect(exitPinProblem('4931', null)).toBe(EXIT_PIN_RULE);
    expect(exitPinProblem('4931', undefined)).toBe(EXIT_PIN_RULE);
  });

  it('marks a kiosk configuration whose PIN predates the rule', () => {
    const c = (password: string | undefined, kioskMode = true) => ({ id: 1, name: 'x', kioskMode, password }) as Configuration;
    expect(hasLegacyPin(c('4931'))).toBe(true);
    expect(hasLegacyPin(c('let me out'))).toBe(true);
    expect(hasLegacyPin(c('482139'))).toBe(false);
    expect(hasLegacyPin(c(undefined))).toBe(false);
    expect(hasLegacyPin(c('4931', false))).toBe(false);
  });
});
