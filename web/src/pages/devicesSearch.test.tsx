import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import type { DeviceView } from '../api/devices';
import { renderInConsole, settled, stubApi } from '../test/console';

const IMEI = '359881234567890';
const main = () => within(screen.getByRole('main'));
// Where each agent generation puts the IMEI in the device list: set by an admin, legacy agent info, or the IMEIs the
// current agent reported (one per SIM slot).
const imeiFields = {
  manual: { imei: IMEI },
  legacy: { info: { imei: IMEI } },
  current: { reportedImeis: ['359889876543210', IMEI] },
} satisfies Record<string, Partial<DeviceView>>;

describe.each(['manual', 'legacy', 'current'] as const)('device search by %s IMEI', (source) => {
  it('finds a server match outside the initially loaded page', async () => {
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/devices/search')) {
        const value = (JSON.parse(String(init?.body)) as { value: string }).value;
        const items = value ? [{
          id: 3, number: 'REMOTE-3', description: 'Remote device', lastUpdate: Date.now(),
          ...imeiFields[source],
        }] : [{ id: 2, number: 'TEST-2', description: 'Office', lastUpdate: Date.now() }];
        return { devices: { items, totalItemsCount: value ? 1 : 1001 } };
      }
      if (path.endsWith('/configurations/search')) return [];
      return null;
    });
    renderInConsole(<AppRoutes />, { path: '/devices' });
    await main().findByText('Office');
    expect(main().queryByText('Remote device')).toBeNull();
    fireEvent.change(main().getByRole('searchbox', { name: 'Search devices' }), { target: { value: IMEI } });
    await main().findByText('Remote device');
    await settled();
    expect(main().queryByText('Office')).toBeNull();
    expect(fetch.mock.calls.some(([path, init]) => String(path).endsWith('/devices/search') &&
      (JSON.parse(String(init?.body)) as { value: string }).value === IMEI)).toBe(true);
  });

  it.each([IMEI, '1234567'])('matches %s immediately and after the server answers', async (query) => {
    const target: DeviceView = {
      id: 1, number: 'TEST-1', description: 'Reception', lastUpdate: Date.now(),
      ...imeiFields[source],
    };
    const other: DeviceView = { id: 2, number: 'TEST-2', description: 'Office', lastUpdate: Date.now() };
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/devices/search')) {
        const value = (JSON.parse(String(init?.body)) as { value: string }).value;
        const items = value ? [target] : [target, other];
        return { devices: { items, totalItemsCount: items.length } };
      }
      if (path.endsWith('/configurations/search')) return [];
      return null;
    });
    renderInConsole(<AppRoutes />, { path: '/devices' });
    await main().findByText('Office');
    fireEvent.change(main().getByRole('searchbox', { name: 'Search devices' }), { target: { value: ` ${query} ` } });
    // Check the immediate local filter before the debounced server request.
    expect(main().getByText('Reception')).toBeTruthy();
    expect(main().queryByText('Office')).toBeNull();
    await waitFor(() => expect(fetch.mock.calls.some(([path, init]) =>
      String(path).endsWith('/devices/search') &&
      (JSON.parse(String(init?.body)) as { value: string }).value === query,
    )).toBe(true));
    await settled();
    expect(main().getByText('Reception')).toBeTruthy();
    expect(main().queryByText('Office')).toBeNull();
  });
});
