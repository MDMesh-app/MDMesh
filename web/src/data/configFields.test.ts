import { describe, expect, it } from 'vitest';
import { CONFIG_FIELDS } from './configFields';

describe('Factory reset field', () => {
  const field = CONFIG_FIELDS.find((f) => f.key === 'disableFactoryReset');

  it('says the console wipe lifts the block', () => {
    expect(field?.help).toContain('lifts the block first');
  });

  // #222: older agents wipe with the block on, and some vendor recoveries then stop mid-wipe.
  it('tells admins of agents before 0.5.1 to allow and sync before wiping', () => {
    expect(field?.more).toContain('Agents before 0.5.1');
    expect(field?.more).toContain('set this to Allow');
  });
});
