// axe-core over every console route, rendered through the real routes against a small stubbed API: structure, names,
// roles, states, landmarks and ARIA (WCAG 2.0-2.2 A/AA rules). jsdom has no layout, so contrast, target size and
// reflow are left to the token test (tokenContrast.test.ts) and the browser sweep (scripts/shots/a11y.mjs).
import { fireEvent, screen, waitFor } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, stubApi } from '../test/console';

const NOW = Date.now();
const DEVICES = [
  { id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW, info: { model: 'TC52', androidVersion: '13', batteryLevel: 84 } },
  { id: 2, number: 'WH-2', description: 'Back office', configurationId: 3, lastUpdate: NOW - 2 * 3600_000 },
];
const CONFIG = { id: 3, name: 'Warehouse', description: 'Scanners', kioskMode: false, type: 0, baseUrl: 'https://mdm.example' };
const APPS = [
  { id: 11, name: 'Chrome', pkg: 'com.android.chrome', version: '120.0', versionCode: 120, type: 'app', latestVersion: 21, url: 'https://mdm.example/files/chrome.apk', hasApkHash: true },
  { id: 12, name: 'Scanner', pkg: 'com.example.scan', version: '2.1', versionCode: 21, type: 'app', latestVersion: 22, url: 'https://cdn.example/scan.apk' },
];
const LOCATIONS = [
  { id: 1, lat: 37.7793, lon: -122.4192, accuracy: 12, provider: 'fused', capturedAt: NOW - 60_000 },
  { id: 2, lat: 37.779, lon: -122.418, accuracy: 30, provider: 'gps', capturedAt: NOW - 3600_000 },
];

/** Answers for every call these pages make, with data, so each page renders its loaded state (not an error). */
function api(path: string, init?: RequestInit): unknown {
  const url = new URL(path, 'http://console');
  const p = url.pathname.replace(/^\/rest/, '');
  const post = init?.method === 'POST';
  if (p === '/public/auth/options') return { recovery: false };
  if (p === '/private/users/current') return ROLES.superAdmin;
  if (p === '/private/devices/search') return { devices: { items: DEVICES, totalItemsCount: DEVICES.length }, configurations: { 3: CONFIG } };
  const byNumber = /^\/private\/devices\/number\/([^/]+)$/.exec(p);
  if (byNumber) return DEVICES.find((d) => d.number === decodeURIComponent(byNumber[1])) ?? null;
  if (p === '/private/configurations/search') return [CONFIG];
  if (p === '/private/configurations/list') return [{ id: 3, name: 'Warehouse' }];
  if (p === '/private/configurations/3') return { ...CONFIG, applications: [] };
  if (/^\/private\/configurations\/applications\/\d+$/.test(p)) return [];
  if (p.startsWith('/private/applications/search')) return APPS;
  if (/^\/private\/applications\/\d+\/versions$/.test(p)) return [];
  if (p.startsWith('/private/fdroid/search')) return [];
  if (p.endsWith('/syncSummary')) return [{ configurationId: 3, total: 2, inSync: 2, outOfSync: 0, unsupported: 0, neverSeen: 0, deferred: 0 }];
  if (p.endsWith('/configStatus')) return { configurationId: 3, currentRevision: 'a', appliedRevision: 'a', inSync: true, supported: true };
  if (p.endsWith('/telemetry')) return { dynamic: { batteryLevel: 84, charging: true }, hardware: { model: 'TC52' }, identity: { serial: 'S1' } };
  if (p.endsWith('/state')) return { screenOn: true, locked: false, kioskActive: false };
  if (p.endsWith('/locations')) return LOCATIONS;
  if (p.endsWith('/events') || (p.endsWith('/commands') && !post)) return [];
  if (p === '/private/agent/v1/token' && post) return { token: 'A11YTOKEN1234', expiresAt: NOW + 86_400_000, maxUses: 1 };
  if (p.endsWith('/rollout/active')) return null;
  if (post) return {};
  return null;
}

/** The page loaded its data: no error callout (a role="alert" in the page itself, not a toast). */
function noLoadError() {
  const alerts = Array.from(document.querySelectorAll('main [role=alert]')).map((a) => a.textContent);
  expect(alerts).toEqual([]);
}

/** WCAG A/AA rules only; colour contrast needs layout, which jsdom lacks. */
async function violations(): Promise<string[]> {
  const res = await axe.run(document.body, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  });
  return res.violations.map((v) => `${v.id}: ${v.help} — ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
}

/** Each route, its h1, and text that shows only once its data loaded. */
const ROUTES: Array<[path: string, heading: RegExp, loaded: RegExp]> = [
  ['/', /Overview|fleet/i, /Warehouse/],
  ['/devices', /Devices/, /WH-2/],
  ['/devices/WH-1', /WH-1|Front desk/, /TC52/],
  ['/configurations', /Configurations/, /Scanners/],
  ['/configurations/3', /Warehouse/, /Scanners/],
  ['/apps', /Apps/, /^Chrome$/],
  ['/enroll', /Enroll/, /Warehouse/],
  ['/settings', /Settings/, /Appearance/],
];

describe('axe: every signed-in route', () => {
  it.each(ROUTES)('%s has no WCAG A/AA violations', async (path, heading, loaded) => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: ROLES.superAdmin, path });
    await screen.findByRole('heading', { level: 1, name: heading });
    expect((await screen.findAllByText(loaded)).length).toBeGreaterThan(0);
    // Let the page's data settle (lists, counts) before the scan.
    await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull());
    // 2.4.2: every page names itself in the tab.
    await waitFor(() => expect(document.title).toMatch(/^.+ · MDMesh$/));
    noLoadError();
    expect(await violations()).toEqual([]);
  });

  it('the device Location tab with its list open has no violations', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: ROLES.superAdmin, path: '/devices/WH-1?tab=location' });
    const summary = await screen.findByText(/Locations as a list/);
    const details = summary.closest('details') as HTMLDetailsElement;
    details.open = true;
    fireEvent(details, new Event('toggle'));
    expect(await screen.findByRole('table', { name: 'Reported locations' })).toBeTruthy();
    expect(screen.getAllByRole('row')).toHaveLength(LOCATIONS.length + 1);
    noLoadError();
    expect(await violations()).toEqual([]);
  });
});

describe('axe: signed-out pages', () => {
  it('/login has no WCAG A/AA violations', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: null, path: '/login' });
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    await waitFor(() => expect(document.title).toBe('Sign in · MDMesh'));
    expect(await violations()).toEqual([]);
  });

  it('a device that is not there still has a heading and a title', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { user: ROLES.superAdmin, path: '/devices/NOPE' });
    await screen.findByRole('heading', { level: 1, name: 'Device NOPE' });
    await waitFor(() => expect(document.title).toBe('Device NOPE · MDMesh'));
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
