import { describe, expect, it } from 'vitest';
import { locationServicesAction } from './configurationRules';

describe('locationServicesAction', () => {
  it('marks every explicit editor selection so the server can distinguish it from an older client omission', () => {
    expect(locationServicesAction(true)).toBe('on');
    expect(locationServicesAction(false)).toBe('off');
    expect(locationServicesAction(null)).toBe('notManaged');
  });
});
