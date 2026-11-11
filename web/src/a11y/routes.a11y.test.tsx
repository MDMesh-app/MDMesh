// axe-core over every console route, rendered through the real routes against a small stubbed API: structure, names,
// roles, states, landmarks and ARIA (WCAG 2.0-2.2 A/AA rules). jsdom has no layout, so contrast, target size and
// reflow are left to the token test (tokenContrast.test.ts) and the browser sweep (scripts/shots/a11y.mjs).
import { screen, waitFor } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, stubApi } from '../test/console';

const NOW = Date.now();
const DEVICES = [
  { id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW, info: { model: 'TC52', androidVersion: '13' } },
  { id: 2, number: 'WH-2', description: 'Back office', configurationId: 3, lastUpdate: NOW - 2 * 3600_000 },
];
const CONFIG = { id: 3, name: 'Warehouse', description: 'Scanners', kioskMode: false, type: 0 };

function api(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace(/^\/rest/, '');
  if (p === '/public/auth/options') return { recovery: false };
  if (p === '/private/devices/search') return { devices: { items: DEVICES, totalItemsCount: DEVICES.length }, configurations: { 3: CONFIG } };
  const byNumber = /^\/private\/devices\/number\/([^/]+)$/.exec(p);
  if (byNumber) return DEVICES.find((d) => d.number === decodeURIComponent(byNumber[1])) ?? null;
  if (p === '/private/configurations/search') return [CONFIG];
  if (p === '/private/configurations/list') return [{ id: 3, name: 'Warehouse' }];
  if (p === '/private/configurations/3') return CONFIG;
  if (p.endsWith('/syncSummary')) return [{ configurationId: 3, total: 2, inSync: 2, outOfSync: 0, unsupported: 0, neverSeen: 0, deferred: 0 }];
  if (init?.method === 'POST') return {};
  return null;
}

/** WCAG A/AA rules only; colour contrast needs layout, which jsdom lacks. */
async function violations(): Promise<string[]> {
  const res = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

const ROUTES: Array<[path: string, heading: RegExp]> = [
  ['/', /Overview|fleet/i],
  ['/devices', /Devices/],
  ['/devices/WH-1', /WH-1|Front desk/],
  ['/configurations', /Configurations/],
  ['/configurations/3', /Warehouse/],
  ['/apps', /Apps/],
  ['/enroll', /Enroll/],
  ['/settings', /Settings/],
];

describe('axe: every signed-in route', () => {
  it.each(ROUTES)('%s has no WCAG A/AA violations', async (path, heading) => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: ROLES.superAdmin, path });
    await screen.findByRole('heading', { level: 1, name: heading });
    // Let the page's data settle (lists, counts) before the scan.
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
    expect(await violations()).toEqual([]);
  });
});

describe('axe: signed-out pages', () => {
  it('/login has no WCAG A/AA violations', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: null, path: '/login' });
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    expect(await violations()).toEqual([]);
  });

  it('/login with both fields missing names the errors on the fields', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: null, path: '/login' });
    (await screen.findByRole('button', { name: 'Sign in' })).click();
    const login = await screen.findByLabelText('Login');
    await waitFor(() => expect(login.getAttribute('aria-invalid')).toBe('true'));
    expect(login.getAttribute('aria-describedby')).toBeTruthy();
    expect(await violations()).toEqual([]);
  });
});
