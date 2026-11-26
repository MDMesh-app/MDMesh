// The device-group workflow: enroll phones, group them (brands or picked phones), give the group a policy, Apply.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, settled, stubApi } from '@mdmesh/console/testing';
import policies from './index';

const CONFIGS = [
  { id: 1, name: 'Managed Launcher' },
  { id: 4, name: 'Field staff' },
  { id: 5, name: 'Office' },
];

const DEVICES = [
  { id: 11, number: 'P-1', description: 'oppo A5x Sunny', configurationId: 5 },
  { id: 12, number: 'P-2', description: 'Front desk Aditi', configurationId: 5 },
];

const EMPTY = { groups: [], brands: [], deviceBrands: [] };
const OPPO_ON_FIELD = {
  groups: [{ id: 1, name: 'Oppo family', configurationId: 4, brands: ['oppo'], devices: [], createdAt: 1 }],
  brands: [],
  deviceBrands: [],
};

interface Call {
  method: string;
  path: string;
  body: unknown;
}

/** A fake server: core's endpoints, and the plugin's groups starting from `start`. Records every write. */
function server(start: unknown) {
  const writes: Call[] = [];
  let groups = start;
  const fetch = stubApi((path, init) => {
    const method = init?.method ?? 'GET';
    // Searches are POSTs that change nothing: not writes.
    if (method !== 'GET' && !path.endsWith('/search')) writes.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : null });
    if (path.includes('/plugins/policies/private/groups')) {
      if (method === 'POST' && path.endsWith('/groups')) {
        const b = JSON.parse(String(init?.body)) as { name: string; configurationId: number | null; brands: string[]; devices: number[] };
        groups = { groups: [{ id: 9, createdAt: 2, ...b }], brands: [], deviceBrands: [] };
      }
      if (path.endsWith('/groups/brands') && method === 'POST') groups = { ...(groups as object), brands: [{ brand: 'nothing', label: 'Nothing' }] };
      if (path.endsWith('/groups/device-brands')) groups = { ...(groups as object), deviceBrands: [{ deviceId: 12, brand: 'nothing' }] };
      return groups;
    }
    if (path.endsWith('/configurations/search')) return CONFIGS;
    if (path.endsWith('/configurations/copy')) return null;
    if (/\/configurations\/applications\/\d+$/.test(path)) return [];
    const config = path.match(/\/configurations\/(\d+)$/);
    if (config) return { ...CONFIGS.find((c) => c.id === Number(config[1])), extensions: {} };
    if (path.endsWith('/devices/search')) return { devices: { items: DEVICES, totalItemsCount: 2 } };
    if (path.endsWith('/private/devices')) return null;
    const agent = path.match(/\/agent\/v1\/devices\/([^/?]+)\/([a-zA-Z]+)/);
    if (agent) {
      if (agent[2] === 'telemetry') return { hardware: { brand: agent[1] === 'P-1' ? 'OPPO' : 'vivo' } };
      if (agent[2] === 'configStatus') return { configurationId: 5, inSync: true, supported: true };
      return [];
    }
    if (path.endsWith('/plugin/main/private/available')) return [];
    return null;
  });
  return { fetch, writes };
}

afterEach(() => vi.unstubAllGlobals());

describe('device groups', () => {
  it('makes a group of a picked phone with no policy yet', async () => {
    const { writes } = server(EMPTY);
    renderConsole({ extensions: [policies], path: '/policies?view=groups' });
    const create = await screen.findByRole('button', { name: 'New group' });
    await waitFor(() => expect((create as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(create);
    const dialog = await screen.findByRole('dialog', { name: 'New group' });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name' }), { target: { value: 'Front desk' } });
    await waitFor(() => expect(within(dialog).getByRole('checkbox', { name: /Front desk Aditi/ })).toBeTruthy());
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /Front desk Aditi/ }));
    expect(within(dialog).getByText('1 phone in this group: 0 by brand, 1 picked.')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create group' }));
    await waitFor(() => expect(writes).toContainEqual({
      method: 'POST',
      path: expect.stringContaining('/plugins/policies/private/groups'),
      body: { name: 'Front desk', configurationId: null, brands: [], devices: [12] },
    }));
    expect(await screen.findByText('No policy yet')).toBeTruthy();
    await settled();
  });

  it('applies a group policy to its phones on another policy, with core bulk move', async () => {
    const { writes } = server(OPPO_ON_FIELD);
    renderConsole({ extensions: [policies], path: '/policies?view=groups' });
    const apply = await screen.findByRole('button', { name: 'Apply' });
    fireEvent.click(apply);
    const dialog = await screen.findByRole('dialog', { name: /Put Oppo family on Field staff/ });
    expect(within(dialog).getByText('oppo A5x Sunny')).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply to 1 phone' }));
    await waitFor(() => expect(writes).toContainEqual({ method: 'PUT', path: expect.stringMatching(/\/private\/devices$/), body: { ids: [11], configurationId: 4 } }));
    await settled();
  });

  it('sets a new brand on phones sent from the Devices list', async () => {
    const { writes } = server(OPPO_ON_FIELD);
    renderConsole({ extensions: [policies], path: '/policies?view=groups&do=brand&assign=12' });
    const dialog = await screen.findByRole('dialog', { name: 'Set the brand of 1 phone' });
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Brand' })).toBeTruthy());
    // The console's Select is not a native one: open it, then press the option, as a user would.
    fireEvent.click(within(dialog).getByRole('combobox', { name: 'Brand' }));
    fireEvent.click(await screen.findByRole('option', { name: 'New brand…' }));
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'New brand' }), { target: { value: 'Nothing' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Set on 1 phone' }));
    await waitFor(() => expect(writes.map((w) => [w.method, w.path.replace(/^.*\/groups/, '/groups'), w.body])).toEqual([
      ['POST', '/groups/brands', { label: 'Nothing' }],
      ['PUT', '/groups/device-brands', { deviceIds: [12], brand: 'nothing' }],
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await settled();
  });

  it('gives a new policy its groups when it is made', async () => {
    const { writes } = server({ ...OPPO_ON_FIELD, groups: [{ ...OPPO_ON_FIELD.groups[0], configurationId: null }] });
    renderConsole({ extensions: [policies], path: '/policies' });
    const newPolicy = await screen.findByRole('button', { name: 'New policy' });
    await waitFor(() => expect((newPolicy as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(newPolicy);
    const dialog = await screen.findByRole('dialog', { name: 'New policy' });
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Name' }), { target: { value: 'Delivery' } });
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /Oppo family/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create policy' }));
    // The copy can't be found by this fake server (its search never lists it), so the dialog says so; what matters
    // here is the order: the copy first, and the groups only for a policy that exists.
    await waitFor(() => expect(writes[0]).toMatchObject({ method: 'PUT', path: expect.stringContaining('/configurations/copy'), body: { id: 4, name: 'Delivery' } }));
    expect(writes.some((w) => w.path.includes('/groups/policy'))).toBe(false);
    await settled();
  });
});
