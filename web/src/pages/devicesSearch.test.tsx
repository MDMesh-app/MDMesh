import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import type { DeviceView } from '../api/devices';
import { renderInConsole, stubApi } from '../test/console';

const IMEI = '359881234567890';
const main = () => within(screen.getByRole('main'));

describe.each(['manual', 'legacy'] as const)('device search by %s IMEI', (source) => {
  it('finds a server match outside the initially loaded page', async () => {
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/devices/search')) {
        const value = (JSON.parse(String(init?.body)) as { value: string }).value;
        const items = value ? [{
          id: 3, number: 'REMOTE-3', description: 'Remote device', lastUpdate: Date.now(),
          ...(source === 'manual' ? { imei: IMEI } : { info: { imei: IMEI } }),
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
    expect(main().queryByText('Office')).toBeNull();
    expect(fetch.mock.calls.some(([path, init]) => String(path).endsWith('/devices/search') &&
      (JSON.parse(String(init?.body)) as { value: string }).value === IMEI)).toBe(true);
  });

  it.each([IMEI, '1234567'])('matches %s immediately and after the server answers', async (query) => {
    const target: DeviceView = {
      id: 1, number: 'TEST-1', description: 'Reception', lastUpdate: Date.now(),
      ...(source === 'manual' ? { imei: IMEI } : { info: { imei: IMEI } }),
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
    await waitFor(() => expect(main().getByText('Reception')).toBeTruthy());
    expect(main().queryByText('Office')).toBeNull();
  });
});

describe('device search by current-agent IMEI', () => {
  it.each([IMEI, '359881'])('keeps a server-only match for %s without exposing IMEI fields', async (query) => {
    // Current telemetry is searched on the server and is not included in DeviceView.
    // No other visible field contains the query either, so a second local text filter would drop this row.
    const target: DeviceView = { id: 3, number: 'REMOTE-3', description: 'Remote device', lastUpdate: Date.now() };
    const fetch = stubApi((path, init) => {
      if (path.endsWith('/devices/search')) {
        const value = (JSON.parse(String(init?.body)) as { value: string }).value;
        const items = value ? [target] : [{ id: 2, number: 'TEST-2', description: 'Office', lastUpdate: Date.now() }];
        return { devices: { items, totalItemsCount: value ? 1 : 1001 } };
      }
      if (path.endsWith('/configurations/search')) return [];
      return null;
    });
    renderInConsole(<AppRoutes />, { path: '/devices' });
    await main().findByText('Office');
    fireEvent.change(main().getByRole('searchbox', { name: 'Search devices' }), { target: { value: query } });
    await main().findByText('Remote device');
    expect(main().queryByText('Office')).toBeNull();
    expect(main().getByRole('link', { name: /Remote device/ }).getAttribute('href')).toBe('/devices/REMOTE-3');
    expect(fetch.mock.calls.some(([path, init]) => String(path).endsWith('/devices/search') &&
      (JSON.parse(String(init?.body)) as { value: string }).value === query)).toBe(true);
  });
});
