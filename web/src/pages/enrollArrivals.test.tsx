// Enroll: the device the admin just set up shows on the page as it enrolls and checks in, without leaving it.
// Through the real route, against a stubbed API.
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const CREATED = 1_700_000_000_000;

function api(devices: () => unknown[]) {
  return (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/configurations/search') return [];
    if (p === '/agent/v1/token' && init?.method === 'POST')
      return { token: 'tok-1', createdAt: CREATED, expiresAt: Date.now() + 3600_000, maxUses: 1 };
    if (p === '/agent/v1/devices/enrolled') {
      const q = new URL(path, 'http://console').searchParams;
      expect(q.get('since')).toBe(String(CREATED));
      return { serverTime: Date.now(), devices: devices() };
    }
    return null;
  };
}

describe('Enroll: devices enrolled while the page is open', () => {
  it('lists a device that enrolled with the code, not one enrolled before it', async () => {
    stubApi(
      api(() => [
        { number: 'new-1', enrollTime: Date.now(), lastUpdate: Date.now() },
        { number: 'old-1', enrollTime: CREATED - 60_000, lastUpdate: Date.now() },
      ]),
    );
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));

    const link = await main.findByRole('link', { name: 'new-1' }, LOAD);
    expect(link.closest('li')?.textContent).toContain('Enrolled, getting its configuration');
    expect(main.queryByRole('link', { name: 'old-1' })).toBeNull();
  });

  it('says it is waiting while no device has enrolled', async () => {
    stubApi(api(() => []));
    renderInConsole(<AppRoutes />, { path: '/enroll' });
    const main = within(await screen.findByRole('main', undefined, LOAD));
    await waitFor(() => expect(main.getByText(/No device yet/)).toBeTruthy(), LOAD);
  });
});
