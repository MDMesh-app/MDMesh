// The device page's Apps tab: the latest scan from the polled command history, the search and system-apps filter,
// Scan now (edit_devices only) followed through that same history, and its states. Rendered through the real routes,
// against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CommandHistoryItem } from '../api/commands';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 3000 };
const DEVICE = { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: Date.now() };

const scan = (id: number, status: string, apps?: unknown, detail?: string): CommandHistoryItem => ({
  id,
  type: 'apps.scan',
  status,
  detail: detail ?? (apps ? JSON.stringify({ apps }) : null),
  createdAt: Date.now() - 60_000,
  completedAt: status === 'done' ? Date.now() - 60_000 : undefined,
});

const APPS = [
  { pkg: 'com.acme.scanner', label: 'Scanner', versionName: '2.1', versionCode: 21 },
  { pkg: 'com.android.settings', label: 'Settings', system: true },
  // What a broken agent might send: no label, a number for one, an object for a version.
  { pkg: 'com.acme.nolabel' },
  { pkg: 'com.acme.odd', label: 5, versionName: { v: 1 } },
];

/**
 * DEVICE's API with `history` as its command history. A queued apps.scan (id 42) is answered with `answer` in the
 * next history read, if given.
 */
function openApps({
  history = [] as CommandHistoryItem[],
  answer = undefined as CommandHistoryItem | undefined,
  user = ROLES.superAdmin,
} = {}) {
  let rows = history;
  const fetch = stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    const post = init?.method === 'POST';
    if (p === '/devices/search') return { devices: { items: [DEVICE], totalItemsCount: 1 }, configurations: {} };
    if (p === `/devices/number/${DEVICE.number}`) return DEVICE;
    if (p === `/agent/v1/devices/${DEVICE.number}/commands`) {
      if (!post) return rows;
      if (answer) rows = [answer, ...rows];
      return { id: 42 };
    }
    return null;
  });
  renderInConsole(<AppRoutes />, { user, path: `/devices/${DEVICE.number}?tab=apps` });
  return fetch;
}

const panel = () => screen.getByRole('tabpanel');
const appNames = () =>
  within(panel())
    .getAllByRole('row')
    .slice(1)
    .map((r) => r.querySelector('td')?.textContent);

describe('the Apps tab', () => {
  it('lists the latest scan by name, user apps first, a missing or odd label as its package', async () => {
    openApps({ history: [scan(9, 'done', APPS), scan(3, 'done', [{ pkg: 'com.old', label: 'Old' }])] });
    await within(await screen.findByRole('tabpanel')).findByRole('table');
    expect(appNames()).toEqual(['com.acme.nolabel', 'com.acme.odd', 'Scanner']);
    expect(within(panel()).getByText('3 apps', { exact: false })).toBeTruthy();
    expect(within(panel()).getByText('2.1 (21)')).toBeTruthy();

    fireEvent.click(within(panel()).getByRole('checkbox', { name: 'Show system apps' }));
    expect(appNames()).toEqual(['com.acme.nolabel', 'com.acme.odd', 'Scanner', 'Settings']);
    expect(within(panel()).getByText('4 apps', { exact: false })).toBeTruthy();
    fireEvent.change(within(panel()).getByRole('searchbox', { name: 'Search apps' }), { target: { value: 'android' } });
    expect(appNames()).toEqual(['Settings']);
    fireEvent.change(within(panel()).getByRole('searchbox', { name: 'Search apps' }), { target: { value: 'nothing' } });
    expect(within(panel()).getByText('No apps match.')).toBeTruthy();
  });

  it('says there is no recent scan rather than never scanned, and offers Scan now to a role with edit_devices', async () => {
    openApps({ history: [scan(5, 'pending'), scan(4, 'failed', undefined, 'Result too large')] });
    expect(await screen.findByText('No recent scan')).toBeTruthy();
    expect(within(panel()).getByRole('button', { name: 'Scan now' })).toBeTruthy();
    expect((within(panel()).getByRole('button', { name: 'Export CSV' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the list without Scan now to a role without edit_devices', async () => {
    openApps({ user: ROLES.observer, history: [scan(9, 'done', APPS)] });
    await within(await screen.findByRole('tabpanel')).findByRole('table');
    expect(within(panel()).queryByRole('button', { name: 'Scan now' })).toBeNull();
    expect((within(panel()).getByRole('button', { name: 'Export CSV' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('Scan now queues apps.scan and shows its result from the history', async () => {
    const fetch = openApps({ answer: fullScan(42, [{ pkg: 'com.fresh', label: 'Fresh' }]) });
    fireEvent.click(await screen.findByRole('button', { name: 'Scan now' }, LOAD));
    await waitFor(() => expect(appNames()).toEqual(['Fresh']), LOAD);
    const posts = fetch.mock.calls.filter(
      ([input, init]) => String(input).endsWith(`/devices/${DEVICE.number}/commands`) && init?.method === 'POST',
    );
    expect(posts.map(([, init]) => JSON.parse(String(init?.body)))).toEqual([
      { type: 'apps.scan', payload: JSON.stringify({ includeAll: true }) },
    ]);
  });

  it('Scan now shows why the device gave no list', async () => {
    openApps({ answer: scan(42, 'failed', undefined, 'Scan refused') });
    fireEvent.click(await screen.findByRole('button', { name: 'Scan now' }));
    expect((await within(panel()).findByRole('alert')).textContent).toBe('Scan refused');
    expect(within(panel()).getByRole('button', { name: 'Scan now' })).toBeTruthy();
  });
});

function fullScan(id: number, apps: unknown): CommandHistoryItem {
  return scan(id, 'done', apps, JSON.stringify({ apps, includeAll: true, appActions: true }));
}
const commandPosts = (fetch: ReturnType<typeof stubApi>) =>
  fetch.mock.calls
    .filter(([input, init]) => String(input).endsWith(`/devices/${DEVICE.number}/commands`) && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init?.body)));

describe('per-app management', () => {
  it('shows Uninstall and Disable on a full action-capable scan; Cancel sends nothing', async () => {
    const fetch = openApps({ history: [fullScan(9, [{ pkg: 'com.acme.scanner', label: 'Scanner' }])] });
    const uninstall = await screen.findByRole('button', { name: 'Uninstall' }, LOAD);
    expect((uninstall as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(uninstall);
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toContain(DEVICE.number);
    expect(dialog.textContent).toContain('com.acme.scanner');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(commandPosts(fetch)).toEqual([]);
  });
  it('a confirmed removal sends the selected package with the dedicated capability', async () => {
    const fetch = openApps({ history: [fullScan(9, [{ pkg: 'com.acme.scanner', label: 'Scanner' }])] });
    fireEvent.click(await screen.findByRole('button', { name: 'Uninstall' }, LOAD));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(commandPosts(fetch)).toEqual([
        { type: 'app.uninstall', requiresCapability: 'app.silentUninstall', payload: JSON.stringify({ packageName: 'com.acme.scanner' }) },
      ]),
    );
  });
  it.each([false, true])('hiding/restoring an app requires confirmation (hidden=%s)', async (hidden) => {
    const fetch = openApps({ history: [fullScan(9, [{ pkg: 'com.acme.scanner', label: 'Scanner', hidden }])] });
    fireEvent.click(await screen.findByRole('button', { name: hidden ? 'Enable' : 'Disable' }, LOAD));
    expect(commandPosts(fetch)).toEqual([]);
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(commandPosts(fetch)).toEqual([
        {
          type: 'app.setHidden',
          requiresCapability: 'app.setHidden',
          payload: JSON.stringify({ packageName: 'com.acme.scanner', hidden: !hidden }),
        },
      ]),
    );
  });
  it('an observer sees the list without mutation controls', async () => {
    openApps({ user: ROLES.observer, history: [fullScan(9, APPS)] });
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('table', undefined, LOAD);
    expect(screen.queryByRole('button', { name: 'Uninstall' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Disable' })).toBeNull();
  });
  it('protects device-marked packages and disables management for older scans', async () => {
    openApps({ history: [fullScan(9, [{ pkg: 'com.mdmesh.agent', label: 'MDMesh', protectedReason: 'Management agent' }])] });
    expect(((await screen.findByRole('button', { name: 'Uninstall' }, LOAD)) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Disable' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('an older agent response is labelled partial and cannot enable app actions', async () => {
    openApps({ history: [scan(9, 'done', [{ pkg: 'com.acme.scanner', label: 'Scanner' }])] });
    expect(((await screen.findByRole('button', { name: 'Uninstall' }, LOAD)) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText('Launcher apps from an older scan.', { exact: false })).toBeTruthy();
  });
});
