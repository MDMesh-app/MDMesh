// A table wrapper that scrolls sideways is a Tab stop, named after the table it scrolls.
import { describe, expect, it } from 'vitest';
import { scrollStopProps } from './Table';

describe('scrollStopProps', () => {
  it('is nothing while the table fits', () => {
    expect(scrollStopProps(false, 'Devices')).toEqual({});
  });

  it('names the region after the table’s aria-label', () => {
    expect(scrollStopProps(true, 'Devices')).toEqual({ tabIndex: 0, role: 'region', 'aria-label': 'Scrollable: Devices' });
  });

  it('points at the table’s label when the table is named by aria-labelledby', () => {
    expect(scrollStopProps(true, undefined, 'apps-heading')).toEqual({ tabIndex: 0, role: 'region', 'aria-labelledby': 'apps-heading' });
  });

  it('falls back to a generic name', () => {
    expect(scrollStopProps(true)).toEqual({ tabIndex: 0, role: 'region', 'aria-label': 'Scrollable table' });
  });
});
