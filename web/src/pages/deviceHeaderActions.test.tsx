// The device header is a shortcut to the Control tab, not a copy of it: Sync now and Lock as buttons, a "…" menu of
// Ring and Reboot that ends with "All actions", which opens the Control tab at Remote actions. Rendered through the
// real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';

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

afterEach(() => vi.restoreAllMocks());

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
    await settled();
  });

  it('without the Remote actions card, All actions leaves focus on the Control tab, not on the page', async () => {
    stubApi(api);
    const byId = document.getElementById.bind(document);
    vi.spyOn(document, 'getElementById').mockImplementation((id) => (id === 'v2-device-remote-actions' ? null : byId(id)));
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1?tab=activity' });
    const bar = within(await screen.findByRole('toolbar', { name: 'Quick actions' }, LOAD));
    fireEvent.click(bar.getByRole('button', { name: 'More actions' }));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: /All actions/ }));
    const tab = screen.getByRole('tab', { name: /Control/ });
    await waitFor(() => expect(document.activeElement).toBe(tab), LOAD);
    await settled();
  });

  it('while a command is sending, keeps All actions (navigation) and waits only the commands', async () => {
    const fetch = stubApi(api);
    const base = fetch.getMockImplementation()!;
    let answer = () => {};
    const held = new Promise<void>((r) => (answer = r));
    fetch.mockImplementation(async (input, init) => {
      if (String(input).endsWith('/sync') && init?.method === 'POST') await held;
      return base(input, init);
    });
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1?tab=activity' });
    const bar = within(await screen.findByRole('toolbar', { name: 'Quick actions' }, LOAD));
    fireEvent.click(bar.getByRole('button', { name: 'Sync now' }));
    await waitFor(() => expect(bar.getByRole('button', { name: 'Sync now' }).hasAttribute('disabled')).toBe(true), LOAD);
    fireEvent.click(bar.getByRole('button', { name: 'More actions' }));
    const menu = within(await screen.findByRole('menu'));
    const disabled = Object.fromEntries(
      menu.getAllByRole('menuitem').map((m) => [m.textContent, m.getAttribute('aria-disabled') === 'true']),
    );
    expect(disabled).toEqual({ Ring: true, Reboot: true, 'All actions →': false });
    fireEvent.click(menu.getByRole('menuitem', { name: /All actions/ }));
    await waitFor(() => expect(screen.getByRole('tab', { name: /Control/ }).getAttribute('aria-selected')).toBe('true'), LOAD);
    // The sync answers: Sync now is back, and nothing is left loading.
    answer();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sync now' }).hasAttribute('disabled')).toBe(false), LOAD);
    await settled();
  });
});
