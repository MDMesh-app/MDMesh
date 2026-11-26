// App schedules: open an app on a policy's phones (whole policy, a group, or picked phones) at set times.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderConsole, settled, stubApi } from '@mdmesh/console/testing';
import policies from './index';

const CONFIGS = [{ id: 4, name: 'Field staff', kioskMode: true }];
const MATRIX = [{ id: 2, pkg: 'com.whatsapp', name: 'WhatsApp', selected: true, action: 1, showIcon: true, usedVersionId: 20 }];
const DEVICES = [
  { id: 11, number: 'P-1', description: 'oppo A5x Sunny', configurationId: 4 },
  { id: 12, number: 'P-2', description: 'Front desk Aditi', configurationId: 4 },
];
const FLEET = {
  statusAvailable: true,
  serverTime: 1,
  devices: [
    { id: 11, number: 'P-1', hardware: { brand: 'OPPO' }, status: { configurationId: 4, inSync: true, supported: true }, scan: null, scanPending: false, removing: [], canLaunch: true },
    { id: 12, number: 'P-2', hardware: { brand: 'vivo' }, status: { configurationId: 4, inSync: true, supported: true }, scan: null, scanPending: false, removing: [], canLaunch: false },
  ],
};
const GROUPS = {
  groups: [{ id: 7, name: 'Oppo family', configurationId: 4, brands: ['oppo'], devices: [], createdAt: 1 }],
  brands: [],
  deviceBrands: [],
};

afterEach(() => vi.unstubAllGlobals());

describe('app schedules', () => {
  it('opens an app on a group of the policy at set times', async () => {
    let saved: unknown = null;
    const schedules: unknown[] = [];
    stubApi((path, init) => {
      const method = init?.method ?? 'GET';
      if (path.includes('/plugins/policies/private/schedules/4')) {
        if (method === 'POST') {
          saved = JSON.parse(String(init?.body));
          const row = { id: 1, configurationId: 4, lastSlot: null, nextRun: Date.now() + 3600_000, ...(saved as object) };
          schedules.push(row);
          return row;
        }
        return schedules;
      }
      if (path.endsWith('/plugins/policies/private/fleet')) return FLEET;
      if (path.endsWith('/plugins/policies/private/groups')) return GROUPS;
      if (path.endsWith('/plugins/policies/private/jobs')) return [];
      if (path.endsWith('/configurations/search')) return CONFIGS;
      if (path.endsWith('/configurations/applications/4')) return MATRIX;
      if (/\/configurations\/4$/.test(path)) return { ...CONFIGS[0], extensions: {} };
      if (path.endsWith('/devices/search')) return { devices: { items: DEVICES, totalItemsCount: 2 } };
      if (path.endsWith('/plugin/main/private/available')) return [];
      return null;
    });
    renderConsole({ extensions: [policies], path: '/policies?policy=4&tab=schedules' });
    const add = await screen.findByRole('button', { name: 'Add schedule' });
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false));
    expect(screen.getByText(/1 phone of 2 can open apps now/)).toBeTruthy();
    fireEvent.click(add);
    const dialog = await screen.findByRole('dialog', { name: 'Add schedule' });
    // The console's Select is not a native one: open it, then press the option, as a user would.
    fireEvent.click(within(dialog).getByRole('combobox', { name: 'App' }));
    fireEvent.click(await screen.findByRole('option', { name: 'WhatsApp (com.whatsapp)' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: 'A group' }));
    expect(within(dialog).getByText(/Reaches 1 phone now; 1 phone of them can open apps/)).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add time' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toMatchObject({
      pkg: 'com.whatsapp',
      appName: 'WhatsApp',
      target: 'group',
      groupId: 7,
      devices: [],
      days: 127,
      times: ['09:00'],
      lateMinutes: 15,
      enabled: true,
    }));
    expect(await screen.findByText('Group: Oppo family')).toBeTruthy();
    await settled();
  });
});
