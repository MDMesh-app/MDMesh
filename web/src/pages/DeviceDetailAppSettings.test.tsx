// The device page's Application settings (Apps tab): the configuration's values read only, the device's overrides
// edited and saved as the device's whole list, an override visibly winning. Rendered through the real routes,
// against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeAll, describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { ROLES, renderInConsole, stubApi } from '../test/console';

const DEVICE = { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: Date.now() };

const setting = (applicationId: number, name: string, value: string, extra: Record<string, unknown> = {}) => ({
  applicationId,
  name,
  type: 'STRING',
  value,
  readonly: false,
  variable: false,
  lastUpdate: 1,
  ...extra,
});

const CONFIG_SETTINGS = [setting(1, 'serverUrl', 'https://vhp.example.hr'), setting(1, 'kioskMode', 'false', { type: 'BOOLEAN' })];
const APPS = [
  { id: 1, name: 'Tablet', pkg: 'hr.example.tablet', action: 1, selected: true },
  { id: 2, name: 'Old tool', pkg: 'hr.example.old', action: 0, selected: true },
];

function openSettings({ device = [] as unknown[], user = ROLES.superAdmin } = {}) {
  const posts: unknown[] = [];
  let settingsReads = 0;
  let stored = device;
  stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/devices/search') return { devices: { items: [DEVICE], totalItemsCount: 1 }, configurations: {} };
    if (p === `/devices/number/${DEVICE.number}`) return DEVICE;
    if (p === '/configurations/3') return { id: 3, name: 'Warehouse', applicationSettings: CONFIG_SETTINGS };
    if (p === '/configurations/applications/3') return APPS;
    if (p === `/devices/${DEVICE.id}/applicationSettings`) {
      if (init?.method === 'POST') {
        stored = JSON.parse(String(init.body)) as unknown[];
        posts.push(stored);
        return null;
      }
      settingsReads += 1;
      return stored;
    }
    if (p === `/agent/v1/devices/${DEVICE.number}/commands`) return [];
    return null;
  });
  renderInConsole(<AppRoutes />, { user, path: `/devices/${DEVICE.number}?tab=apps` });
  return { posts, reads: () => settingsReads };
}

const card = async () => {
  const heading = await screen.findByRole('heading', { name: 'Application settings' });
  return heading.closest('section, .v2-device-appsettings') as HTMLElement;
};
const app = async (name: string) => within(await card()).findByRole('region', { name });

describe('device application settings', () => {
  beforeAll(() => import('./DeviceDetailPage'), 30_000);

  it('lists each app the configuration installs with the configuration’s values, read only', async () => {
    openSettings();
    const tablet = await app('Tablet');
    expect(within(tablet).getByText('https://vhp.example.hr')).toBeTruthy();
    expect(within(tablet).getByText('Off')).toBeTruthy();
    expect(within(tablet).queryByRole('textbox', { name: 'Value' })).toBeNull();
    expect(within(await card()).queryByRole('region', { name: 'Old tool' })).toBeNull();
  });

  it('an override strikes the configuration’s value and says it wins', async () => {
    openSettings({ device: [setting(1, 'serverUrl', 'https://staging.example.hr', { id: 9, extRefId: 7 })] });
    const tablet = await app('Tablet');
    const configured = within(tablet).getByText('https://vhp.example.hr');
    expect(configured.tagName).toBe('S');
    expect(within(tablet).getByText('Overridden on this device')).toBeTruthy();
    expect(within(within(tablet).getByRole('group', { name: 'Setting serverUrl' })).getByRole<HTMLInputElement>('textbox', { name: 'Value' }).value)
      .toBe('https://staging.example.hr');
    expect(within(tablet).getByText('Replaces the configuration’s value on this device.')).toBeTruthy();
  });

  it('overrides a key and adds a device-only one, then saves the whole list, rows it does not show included', async () => {
    const hidden = setting(2, 'legacy', 'kept', { id: 4, extRefId: 7, comment: 'Headwind', readonly: true });
    const { posts } = openSettings({ device: [hidden] });
    const tablet = await app('Tablet');
    fireEvent.click(within(tablet).getByRole('button', { name: 'Override kioskMode' }));
    expect(within(tablet).getByText('Overridden on this device')).toBeTruthy();

    fireEvent.click(within(tablet).getByRole('button', { name: 'Add device setting' }));
    const added = within(tablet).getByRole('group', { name: 'Setting new setting' });
    fireEvent.change(within(added).getByRole('textbox', { name: 'Key' }), { target: { value: 'deviceToken' } });
    fireEvent.change(within(within(tablet).getByRole('group', { name: 'Setting deviceToken' })).getByRole('textbox', { name: 'Value' }), {
      target: { value: 'tok-123' },
    });
    expect(within(tablet).getByText('Only on this device.')).toBeTruthy();
    expect(within(await card()).getByText('2 unsaved changes')).toBeTruthy();

    fireEvent.click(within(await card()).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual([
      hidden,
      expect.objectContaining({ applicationId: 1, name: 'kioskMode', type: 'BOOLEAN', value: 'false', readonly: false, variable: false }),
      expect.objectContaining({ applicationId: 1, name: 'deviceToken', type: 'STRING', value: 'tok-123' }),
    ]);
    await screen.findByText('Application settings saved');
    expect(document.body.textContent).not.toMatch(/tok-123.*saved|saved.*tok-123/);
    await waitFor(() => expect(within(screen.getByRole('tabpanel')).queryByText(/unsaved change/)).toBeNull());
  });

  it('Discard goes back to the loaded rows, and an edit set back is no change', async () => {
    openSettings({ device: [setting(1, 'serverUrl', 'https://a')] });
    const tablet = await app('Tablet');
    const value = within(within(tablet).getByRole('group', { name: 'Setting serverUrl' })).getByRole('textbox', { name: 'Value' });
    fireEvent.change(value, { target: { value: 'https://b' } });
    expect(within(await card()).getByText('1 unsaved change')).toBeTruthy();
    fireEvent.change(value, { target: { value: 'https://a' } });
    expect(within(await card()).queryByText(/unsaved change/)).toBeNull();

    fireEvent.click(within(tablet).getByRole('button', { name: 'Remove setting serverUrl' }));
    fireEvent.click(within(await card()).getByRole('button', { name: 'Discard' }));
    expect(within(tablet).getByRole('group', { name: 'Setting serverUrl' })).toBeTruthy();
  });

  it('refuses a duplicate key without saving', async () => {
    const { posts } = openSettings({ device: [setting(1, 'serverUrl', 'https://a')] });
    const tablet = await app('Tablet');
    fireEvent.click(within(tablet).getByRole('button', { name: 'Add device setting' }));
    fireEvent.change(within(within(tablet).getByRole('group', { name: 'Setting new setting' })).getByRole('textbox', { name: 'Key' }), {
      target: { value: 'serverUrl' },
    });
    expect(within(tablet).getByText('This app already has a setting with this key.')).toBeTruthy();
    fireEvent.click(within(await card()).getByRole('button', { name: 'Save' }));
    await screen.findByText('Fix 1 problem before saving');
    expect(posts).toHaveLength(0);
  });

  it('leaves the card out for a role without edit_device_app_settings, as the server refuses it the values', async () => {
    const { reads } = openSettings({ device: [setting(1, 'serverUrl', 'https://staging.example.hr')], user: ROLES.observer });
    await screen.findByRole('heading', { name: 'Installed apps' });
    expect(screen.queryByRole('heading', { name: 'Application settings' })).toBeNull();
    expect(reads()).toBe(0);
  });
});
