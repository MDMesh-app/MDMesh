import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, ROLES, settled, stubApi } from '@mdmesh/console/testing';
import policies from './index';

const POLICY_DATA = {
  v: 1,
  apps: { mode: 'remove', notify: true, exceptions: [] },
};

// The Policies server plugin's groups: Oppo family (Oppo, Realme) on Field staff.
const GROUPS = {
  groups: [{ id: 1, name: 'Oppo family', configurationId: 4, brands: ['oppo', 'realme'], devices: [], createdAt: 1 }],
  brands: [],
  deviceBrands: [],
};

const CONFIGS = [
  { id: 1, name: 'Managed Launcher' },
  { id: 4, name: 'Field staff', camera: false, disableFactoryReset: true, kioskMode: true, password: '4931' },
];

// Field staff's app matrix: every Library app, its own ones selected. Phone and WhatsApp are on the kiosk screen.
const MATRIX = [
  { id: 1, pkg: 'com.android.dialer', name: 'Phone', selected: true, action: 1, showIcon: true, usedVersionId: 10 },
  { id: 2, pkg: 'com.whatsapp', name: 'WhatsApp', selected: true, action: 1, showIcon: true, usedVersionId: 20 },
  { id: 3, pkg: 'com.android.systemui', name: 'System UI', selected: true, action: 1, showIcon: false },
  { id: 5, pkg: 'com.android.chrome', name: 'Chrome', selected: false },
];

// P-2's name doesn't start with its brand, so its brand can only come from its telemetry.
const DEVICES = [
  { id: 11, number: 'P-1', description: 'oppo A5x Sunny', configurationId: 4 },
  { id: 12, number: 'P-2', description: 'Front desk Aditi', configurationId: 4 },
];

const SCAN = JSON.stringify({ apps: [{ pkg: 'com.instagram.android', label: 'Instagram' }] });

function answer(path: string): unknown {
  if (path.endsWith('/configurations/search')) return CONFIGS;
  if (path.endsWith('/configurations/applications/4')) return MATRIX;
  if (/\/configurations\/4$/.test(path)) return { ...CONFIGS[1], extensions: { policies: POLICY_DATA } };
  if (/\/configurations\/1$/.test(path)) return { ...CONFIGS[0], extensions: {} };
  if (path.endsWith('/devices/search')) return { devices: { items: DEVICES, totalItemsCount: 2 } };
  // As the server: the agent endpoints know a device by its number, never by its internal id.
  const agent = path.match(/\/agent\/v1\/devices\/([^/?]+)\/([a-zA-Z]+)/);
  if (agent) {
    const device = DEVICES.find((d) => d.number === decodeURIComponent(agent[1]));
    if (!device) return null;
    if (agent[2] === 'telemetry') return { hardware: { brand: device.number === 'P-1' ? 'OPPO' : 'vivo' } };
    if (agent[2] === 'configStatus') return { configurationId: 4, inSync: true, supported: true };
    if (agent[2] === 'commands') return device.number === 'P-1' ? [{ id: 1, type: 'apps.scan', status: 'done', detail: SCAN }] : [];
    return null;
  }
  if (path.endsWith('/plugin/main/private/available')) return [];
  if (path.endsWith('/plugins/policies/private/groups')) return GROUPS;
  return null;
}

afterEach(() => vi.unstubAllGlobals());

describe('policies extension', () => {
  it('opens the first policy of your own beside the list, with its groups and app control', async () => {
    stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies' });
    expect(await screen.findByRole('heading', { name: 'Field staff' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Managed Launcher/ })).toBeTruthy();
    expect(await screen.findByText('Oppo family')).toBeTruthy();
    expect(screen.getByText(/Report \+ one-click remove/)).toBeTruthy();
    await settled();
  });

  it('opens a policy from an old /policies/:id link', async () => {
    stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies/1' });
    expect(await screen.findByRole('heading', { name: 'Managed Launcher' })).toBeTruthy();
    expect(screen.getByText(/ships with MDMesh/)).toBeTruthy();
    await settled();
  });

  it('flags the phone with an app outside the allowed list and the phone in no group', async () => {
    stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies?view=status' });
    expect(await screen.findByText('oppo A5x Sunny')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('button', { name: '1 app' })).toBeTruthy());
    expect(screen.getByText(/In no group/)).toBeTruthy();
    // Status and brand come from the phone's own endpoints, which name it by number.
    expect(screen.getAllByText('Applied')).toHaveLength(2);
    expect(screen.getByText('P-2').parentElement?.textContent).toContain('Vivo');
    expect(screen.queryByText(/from name/)).toBeNull();
    await settled();
  });

  it('shows one group of phones from the group tree', async () => {
    stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies?view=status' });
    expect(await screen.findByText('oppo A5x Sunny')).toBeTruthy();
    const tree = screen.getByRole('complementary', { name: 'Groups' });
    await waitFor(() => expect(within(tree).getByRole('button', { name: /No group/ })).toBeTruthy());
    fireEvent.click(within(tree).getByRole('button', { name: /No group/ }));
    await waitFor(() => expect(screen.queryByText('oppo A5x Sunny')).toBeNull());
    expect(screen.getByText('Front desk Aditi')).toBeTruthy();
    await settled();
  });

  it('loads every phone in one request when the Policies server plugin answers', async () => {
    const fleet = {
      statusAvailable: true,
      serverTime: 1,
      devices: [
        {
          id: 11,
          number: 'P-1',
          hardware: { brand: 'OPPO' },
          status: { configurationId: 4, inSync: true, supported: true },
          scan: { apps: [{ pkg: 'com.instagram.android', label: 'Instagram' }], scannedAt: 1 },
          scanPending: false,
          removing: [],
        },
        // No status from the plugin: only this phone's status is asked per phone.
        { id: 12, number: 'P-2', hardware: { brand: 'vivo' }, status: null, scan: null, scanPending: true, removing: [] },
      ],
    };
    const fetch = stubApi((path) => (path.endsWith('/plugins/policies/private/fleet') ? fleet : answer(path)));
    renderConsole({ extensions: [policies], path: '/policies?view=status' });
    expect(await screen.findByText('oppo A5x Sunny')).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('button', { name: '1 app' })).toBeTruthy());
    await waitFor(() => expect(screen.getAllByText('Applied')).toHaveLength(2));
    expect(screen.getByText('P-2').parentElement?.textContent).toContain('Vivo');
    const calls = fetch.mock.calls.map(([url]) => String(url));
    expect(calls.filter((u) => /\/(telemetry|commands)/.test(u))).toEqual([]);
    expect(calls.filter((u) => u.includes('/configStatus'))).toEqual([expect.stringContaining('/devices/P-2/configStatus')]);
    await settled();
  });

  it('sends a bulk action to many phones as one job and shows its progress', async () => {
    const fleet = {
      statusAvailable: true,
      serverTime: 1,
      devices: DEVICES.map((d) => ({ id: d.id, number: d.number, hardware: { brand: 'OPPO' }, status: { configurationId: 4, inSync: true, supported: true }, scan: null, scanPending: false, removing: [] })),
    };
    const job = { id: 7, action: 'scan', label: 'App scan queued · all 2 shown', createdAt: Date.now(), cancelled: false, phones: 2, wake: { queued: 2 }, commands: { pending: 2 }, phase: 'sending' };
    let posted: Record<string, unknown> | null = null;
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/plugins/policies/private/fleet')) return fleet;
      if (path.endsWith('/plugins/policies/private/jobs')) {
        if (init?.method === 'POST') {
          posted = JSON.parse(String(init.body)) as Record<string, unknown>;
          return job;
        }
        return posted ? [job] : [];
      }
      return answer(path);
    });
    renderConsole({ extensions: [policies], path: '/policies?view=status' });
    await waitFor(() => expect(screen.getAllByText('Applied')).toHaveLength(2));
    fireEvent.click(screen.getByRole('button', { name: /Client actions · all 2 shown/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Scan apps' }));
    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted).toMatchObject({ action: 'scan' });
    expect([...((posted as unknown as { deviceIds: number[] }).deviceIds)].sort()).toEqual([11, 12]);
    // The job shows with its progress; no per-phone or core bulk command was sent.
    expect(await screen.findByRole('progressbar', { name: /progress/ })).toBeTruthy();
    expect(screen.getByText('2 waiting to be woken')).toBeTruthy();
    const calls = fetch.mock.calls.map(([url]) => String(url));
    expect(calls.some((u) => u.includes('/bulk/commands') || /\/devices\/P-\d\/commands/.test(u))).toBe(false);
    await settled();
  });

  it('saves the automatic scans in the browser time zone, and leaves auto-remove to the server', async () => {
    const fleet = {
      statusAvailable: true,
      serverTime: 1,
      devices: DEVICES.map((d) => ({
        id: d.id,
        number: d.number,
        hardware: { brand: 'OPPO' },
        status: { configurationId: 4, inSync: true, supported: true },
        // An extra app on an auto-remove kiosk policy: without the plugin the page would remove it.
        scan: { apps: [{ pkg: 'com.game', label: 'Game' }], scannedAt: 1 },
        scanPending: false,
        removing: [],
      })),
    };
    const autoPolicy = { ...POLICY_DATA, apps: { mode: 'auto', notify: true, exceptions: [] } };
    let saved: Record<string, unknown> | null = null;
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/plugins/policies/private/fleet')) return fleet;
      if (path.endsWith('/plugins/policies/private/jobs')) return [];
      if (path.endsWith('/plugins/policies/private/automation')) {
        if (init?.method === 'PUT') {
          saved = JSON.parse(String(init.body)) as Record<string, unknown>;
          return { ...saved, autoRemoveSince: 1 };
        }
        return { automaticScans: false, windowStart: 1, windowEnd: 5, timeZone: 'UTC', autoRemoveSince: null };
      }
      if (/\/configurations\/4$/.test(path)) return { ...CONFIGS[1], extensions: { policies: autoPolicy } };
      return answer(path);
    });
    renderConsole({ extensions: [policies], path: '/policies?view=automation' });
    const toggle = await screen.findByRole('switch', { name: 'Automatic scans' });
    expect(await screen.findByText('Field staff')).toBeTruthy();   // listed under Auto-remove
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).not.toBeNull());
    expect(saved).toMatchObject({ automaticScans: true, windowStart: 1, windowEnd: 5, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone });
    // The server removes extra apps after each scan: the page sends no uninstall of its own.
    const sent = fetch.mock.calls.filter(([, init]) => init?.method === 'POST').map(([, init]) => String(init?.body));
    expect(sent.some((b) => b.includes('app.uninstall'))).toBe(false);
    await settled();
  });

  it('tells the server what to do with Location services when it changes (the server keeps it otherwise)', async () => {
    const fetch = stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies?policy=4&tab=device' });
    const group = await screen.findByRole('radiogroup', { name: 'Location services' });
    fireEvent.click(within(group).getByRole('radio', { name: 'Off' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save and apply' }));
    await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true));
    const body = JSON.parse(String(fetch.mock.calls.find(([, init]) => init?.method === 'PUT')![1]!.body)) as Record<string, unknown>;
    expect(body.locationServices).toBe(false);
    expect(body.locationServicesAction).toBe('off');
    await settled();
  });

  it('saves the allowed list into the configuration after asking', async () => {
    const fetch = stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies?policy=4&tab=apps' });
    const table = await screen.findByRole('table', { name: 'Apps' });
    await waitFor(() => expect(within(table).getByText('WhatsApp')).toBeTruthy());
    expect(within(table).queryByText('System UI')).toBeNull();
    fireEvent.click(within(table).getByRole('checkbox', { name: 'Allow WhatsApp' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save and apply' }));
    const dialog = await screen.findByRole('dialog', { name: /Apply Field staff to 2 phones/ });
    expect(within(dialog).getByText('1 allowed app')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save and apply' }));
    await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true));
    const put = fetch.mock.calls.find(([, init]) => init?.method === 'PUT')!;
    const body = JSON.parse(String(put[1]!.body)) as { applications: { pkg: string; showIcon?: boolean; selected?: boolean }[] };
    // WhatsApp leaves the kiosk screen but stays installed; nothing else changes, and the matrix flag isn't sent.
    expect(body.applications.map((a) => [a.pkg, a.showIcon])).toEqual([
      ['com.android.dialer', true],
      ['com.whatsapp', false],
      ['com.android.systemui', false],
    ]);
    expect(body.applications.some((a) => 'selected' in a)).toBe(false);
    await settled();
  });

  it('hides the page from a role without configurations', async () => {
    stubApi(answer);
    renderConsole({ extensions: [policies], path: '/policies', user: ROLES.observer });
    expect(await screen.findByText(/don.t have access/i)).toBeTruthy();
    await settled();
  });
});
