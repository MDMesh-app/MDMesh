// The device header is a shortcut to the Control tab, not a copy of it: Sync now and Lock as buttons, a "…" menu of
// Ring and Reboot that ends with "All actions", which opens the Control tab at Remote actions. Rendered through the
// real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const DEVICE = { id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: Date.now() };

function api(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  if (p === '/devices/search') return { devices: { items: [DEVICE], totalItemsCount: 1 }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
  if (p === '/devices/number/WH-1') return DEVICE;
  if (p === '/configurations/3') return { id: 3, name: 'Warehouse', kioskMode: false };
  if (p.endsWith('/state')) return { screenOn: true, locked: false, kioskActive: false };
  if (p.endsWith('/events') || p.endsWith('/locations') || (p.endsWith('/commands') && init?.method !== 'POST')) return [];
  return null;
}

describe('device header actions', () => {
  it('keeps Sync now and Lock as buttons; its menu is Ring, Reboot, then All actions', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1?tab=activity' });
    const bar = within(await screen.findByRole('toolbar', { name: 'Quick actions' }, LOAD));
    expect(bar.getAllByRole('button').map((b) => b.getAttribute('aria-label') ?? b.textContent)).toEqual([
      'Sync now',
      'Lock',
      'More actions',
    ]);
    fireEvent.click(bar.getByRole('button', { name: 'More actions' }));
    const menu = within(await screen.findByRole('menu'));
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Ring', 'Reboot', 'All actions →']);

    fireEvent.click(menu.getByRole('menuitem', { name: /All actions/ }));
    await waitFor(() => expect(screen.getByRole('tab', { name: /Control/ }).getAttribute('aria-selected')).toBe('true'), LOAD);
    const card = await screen.findByRole('region', { name: 'Remote actions' }, LOAD);
    await waitFor(() => expect(document.activeElement).toBe(card), LOAD);
  });
});
