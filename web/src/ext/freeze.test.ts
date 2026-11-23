import { describe, expect, it } from 'vitest';
import { deepFreeze } from './freeze';

describe('deepFreeze', () => {
  it('freezes every object and array in a value', () => {
    const v = deepFreeze({ a: { b: [1, { c: 2 }] } });
    expect(Object.isFrozen(v)).toBe(true);
    expect(Object.isFrozen(v.a)).toBe(true);
    expect(Object.isFrozen(v.a.b)).toBe(true);
    expect(Object.isFrozen(v.a.b[1])).toBe(true);
  });

  it('leaves a typed array as it is, and still freezes the rest around it', () => {
    const v = { a: { x: 1 }, t: new Uint8Array([1]), b: { y: 2 } };
    expect(() => deepFreeze(v)).not.toThrow();
    expect(Object.isFrozen(v)).toBe(true);
    expect(Object.isFrozen(v.a)).toBe(true);
    expect(Object.isFrozen(v.b)).toBe(true);
    expect(Object.isFrozen(v.t)).toBe(false);
    v.t[0] = 2;
    expect(v.t[0]).toBe(2);
  });
});
