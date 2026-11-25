import { createIntl } from 'react-intl';
import { describe, expect, it } from 'vitest';
import { AGENT_UPDATE_NEEDED } from '../api/configSync';
import { syncLabel } from './devicesStatus';

const intl = createIntl({ locale: 'en' });

describe('the sync line of a device whose agent needs an update', () => {
  it('names the sections in the console’s words, an unknown one as is', () => {
    const of = (...sections: string[]) => syncLabel(intl, { tone: 'warn', label: AGENT_UPDATE_NEEDED, sections });
    expect(of('connectivity')).toBe('Agent update needed for: Connectivity');
    expect(of('kiosk', 'appPermissions')).toBe('Agent update needed for: Kiosk mode and App permissions');
    expect(of('policies', 'location', 'futureSection')).toBe('Agent update needed for: Policies, Location reporting, and futureSection');
  });
});
