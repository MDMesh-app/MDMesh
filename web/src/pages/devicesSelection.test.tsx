// Selecting on Devices: with nothing selected a click opens the device; once one is selected, a click on a row or card
// toggles it, and Shift selects (or clears, to match the last one toggled) the range between, in the list's order.
// Rendered through the real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const NOW = Date.now();
// Sorted by name by default: Alpha, Bravo, Charlie, Delta, Echo.
const DEVICES = ['Alpha', 'Bravo', 'Charlie', 'Delta', 'Echo'].map((name, i) => ({
  id: i + 1,
  number: `D-${i + 1}`,
  description: name,
  configurationId: 3,
  lastUpdate: NOW,
}));

function api(path: string): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  if (p === '/devices/search') {
    return { devices: { items: DEVICES, totalItemsCount: DEVICES.length }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
  }
  if (p === '/configurations/search') return [{ id: 3, name: 'Warehouse' }];
  return null;
}

let here = '';
function Probe() {
  const location = useLocation();
  here = location.pathname;
  return null;
}

function open(path = '/devices') {
  stubApi(api);
  renderInConsole(
    <>
      <AppRoutes />
      <Probe />
    </>,
    { path },
  );
}

const main = () => within(screen.getByRole('main'));
const box = (n: number) => main().getByRole('checkbox', { name: `Select D-${n}` }) as HTMLInputElement;
const checked = () => [1, 2, 3, 4, 5].filter((n) => box(n).checked);
/** A click on the device's row or card itself, away from its links and buttons. */
const clickDevice = (name: string, init: { shiftKey?: boolean } = {}) =>
  fireEvent.click(main().getByText(name).closest('tr, li') as HTMLElement, init);

describe.each([
  ['the list', '/devices'],
  ['the cards', '/devices?view=cards'],
])('selecting devices in %s', (_view, path) => {
  it('with nothing selected, a click opens the device', async () => {
    open(path);
    await main().findByText('Charlie', {}, LOAD);
    clickDevice('Charlie');
    await waitFor(() => expect(here).toBe('/devices/D-3'));
  });

  it('once one is selected, a click toggles a device instead of opening it, and the count is announced', async () => {
    open(path);
    await main().findByText('Charlie', {}, LOAD);
    fireEvent.click(box(1));
    clickDevice('Charlie');
    expect(checked()).toEqual([1, 3]);
    clickDevice('Charlie');
    expect(checked()).toEqual([1]);
    expect(here).toBe('/devices');
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('1 selected'));
  });

  it('Shift+click selects the range forward from the last device toggled', async () => {
    open(path);
    await main().findByText('Charlie', {}, LOAD);
    fireEvent.click(box(2));
    clickDevice('Delta', { shiftKey: true });
    expect(checked()).toEqual([2, 3, 4]);
    await waitFor(() => expect(screen.getByRole('status').textContent).toBe('3 selected'));
  });

  it('Shift+click selects the range backward, and on a checkbox too', async () => {
    open(path);
    await main().findByText('Charlie', {}, LOAD);
    fireEvent.click(box(5));
    fireEvent.click(box(2), { shiftKey: true });
    expect(checked()).toEqual([2, 3, 4, 5]);
  });

  it('Shift+click clears the range when the last device toggled was cleared', async () => {
    open(path);
    await main().findByText('Charlie', {}, LOAD);
    fireEvent.click(box(1));
    clickDevice('Echo', { shiftKey: true });
    expect(checked()).toEqual([1, 2, 3, 4, 5]);
    clickDevice('Bravo');
    expect(checked()).toEqual([1, 3, 4, 5]);
    clickDevice('Delta', { shiftKey: true });
    expect(checked()).toEqual([1, 5]);
  });
});
