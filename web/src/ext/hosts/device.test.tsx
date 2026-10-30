// The device page as a host: core and extension tabs and rail groups through one registry, the open tab in the URL,
// and what useDevice() hands a tab. Rendered through the real routes, against a stubbed API.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState, type ReactNode } from 'react';
import { useLocation, useNavigate, type NavigateFunction } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../App';
import { LOCATION_HISTORY_LIMIT } from '../../api/deviceLocations';
import { ROLES, registryOf, renderInConsole, stubApi } from '../../test/console';
import demo from '../__fixtures__/demo';
import { CORE_EXTENSION } from '../core';
import { ORDER } from '../core/order';
import { createRegistry } from '../registry';
import { defineExtension, lazyComponent, type Contribution, type DeviceCommand } from '../types';
import { useDevice } from '../useDevice';

const DEVICE = { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: Date.now() };
const HOUR_AGO = Date.now() - 3600 * 1000;

/** The agent API for DEVICE: lookups, the polled reads, and a queued command. */
function deviceApi(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  const post = init?.method === 'POST';
  if (p === '/devices/search') {
    const value = (JSON.parse(String(init?.body ?? '{}')) as { value?: string }).value ?? '';
    const items = value === '' || DEVICE.number.includes(value) ? [DEVICE] : [];
    return { devices: { items, totalItemsCount: items.length }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
  }
  if (p === `/devices/number/${DEVICE.number}`) return DEVICE;
  if (p === '/configurations/3') return { id: 3, name: 'Warehouse', kioskMode: false };
  const agent = `/agent/v1/devices/${DEVICE.number}`;
  if (p === `${agent}/telemetry`) return { hardware: { model: 'TC52' } };
  if (p === `${agent}/state`) {
    return { battery: 80, charging: false, locked: false, kioskActive: false, androidRelease: '13', lastBootAt: 0, updatedAt: HOUR_AGO, agentVersion: '0.3.7' };
  }
  if (p === `${agent}/configStatus`) return { inSync: true, supported: true };
  if (p === `${agent}/events`) return [{ id: 1, type: 'boot', ts: HOUR_AGO }];
  if (p === `${agent}/commands`) return post ? { id: 42 } : [];
  if (p === `${agent}/locations`) return [];
  return null;
}

let here = '';
let go: NavigateFunction;
/** Where the console is, and a way to press Back and Forward. */
function Probe() {
  const location = useLocation();
  here = location.pathname + location.search + location.hash;
  go = useNavigate();
  return null;
}

function openDevice(options: Parameters<typeof renderInConsole>[1] = {}) {
  const fetch = stubApi(deviceApi);
  const view = renderInConsole(
    <>
      <AppRoutes />
      <Probe />
    </>,
    { path: `/devices/${DEVICE.number}`, ...options },
  );
  return { fetch, view };
}

/**
 * Do `fn` as the browser would, outside act(): React then renders the router's navigation (a transition) on its own
 * schedule, so a test can look at the page between a click and that render.
 */
async function asInBrowser(fn: () => Promise<void>) {
  const global = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
  const before = global.IS_REACT_ACT_ENVIRONMENT;
  global.IS_REACT_ACT_ENVIRONMENT = false;
  try {
    await fn();
  } finally {
    global.IS_REACT_ACT_ENVIRONMENT = before;
  }
}

// The page answers after several polled requests, which a loaded machine can stretch past the 1 s default: the waits
// for the page's data get longer, and nothing else.
const LOAD = { timeout: 3000 };

const tabNames = () => screen.getAllByRole('tab').map((t) => t.textContent);
const selectedTab = () => screen.getAllByRole('tab').find((t) => t.getAttribute('aria-selected') === 'true')?.textContent;
const rail = () => screen.getByRole('complementary', { name: 'Device' });
const railTitles = () => Array.from(rail().querySelectorAll('.v2-kvgroup')).map((g) => g.querySelector('h2')?.textContent);
const panel = () => screen.getByRole('tabpanel');
const calls = (fetch: ReturnType<typeof stubApi>, suffix: string, method = 'GET') =>
  fetch.mock.calls.filter(([input, init]) => String(input).endsWith(suffix) && (init?.method ?? 'GET') === method);

function ext(id: string, contributions: Contribution[]) {
  return defineExtension({ id, apiVersion: 1, title: id[0].toUpperCase() + id.slice(1), contributions });
}
const tab = (id: string, Body: () => ReactNode, extra: Partial<Contribution> = {}) =>
  ({ slot: 'device.tab', id, order: 1000, label: id, component: lazyComponent(async () => ({ Body }), 'Body'), ...extra }) as Contribution;

/** An extension whose one tab, at `order`, needs the server plugin `plug`. */
const plugExtension = (order: number) =>
  defineExtension({
    id: 'plug',
    apiVersion: 1,
    title: 'Plug',
    requires: { serverPlugin: 'plug' },
    contributions: [tab('plug', () => 'Plug tab body', { label: 'Plug', order })],
  });

/** The device API, with the active server plugins (GET /plugin/main/private/available) held until the returned answer. */
function holdPlugins() {
  let answer!: (ids: string[]) => void;
  const plugins = new Promise<string[]>((resolve) => (answer = resolve));
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const data = path.endsWith('/plugin/main/private/available')
        ? (await plugins).map((identifier) => ({ identifier }))
        : deviceApi(path, init);
      return Response.json({ status: 'OK', message: null, data: data ?? null });
    }),
  );
  return (ids: string[]) => act(async () => answer(ids));
}

/** An extension whose `commands` tab sends `commands` one after another, with `values`, on "Send". */
function commandsExtension(commands: DeviceCommand[], values?: Record<string, string>) {
  function Commands() {
    const { sendCommand } = useDevice();
    const [sent, setSent] = useState<number | null>(null);
    const send = async () => {
      let n = 0;
      for (const c of commands) if (await sendCommand(c, values)) n++;
      setSent(n);
    };
    return (
      <>
        <button type="button" onClick={() => void send()}>
          Send
        </button>
        {sent !== null ? <p>Sent {sent}</p> : null}
      </>
    );
  }
  return ext('commands', [tab('commands', Commands, { label: 'Commands' })]);
}

describe('the device page without extensions', () => {
  it('shows core’s tabs and rail groups as before, the first tab open and no ?tab in the URL', async () => {
    openDevice();
    await screen.findByRole('tab', { name: 'Control' }, LOAD);
    await waitFor(() => expect(tabNames()).toEqual(['Control', 'Telemetry', 'Apps', 'Activity1', 'Location']), LOAD);
    expect(selectedTab()).toBe('Control');
    expect(railTitles()).toEqual(['Status', 'Location', 'Hardware', 'Network', 'Management']);
    // Only the last group is marked last.
    expect(Array.from(rail().querySelectorAll('.v2-kvgroup.is-last')).map((g) => g.textContent)).toEqual([
      expect.stringContaining('Management'),
    ]);
    expect(await within(panel()).findByText('Recent commands', undefined, LOAD)).toBeTruthy();
    expect(here).toBe('/devices/WH-1');
  });

  it('closes its own dialog and shows a requested mode as pending once queued, before the sync nudge answers', async () => {
    // A slow nudge (POST /sync) must not hold the confirm dialog open or hide "Waiting for the device". Each nudge
    // waits until the test answers it.
    const nudges: (() => void)[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (init?.method === 'POST' && path.endsWith(`/devices/${DEVICE.number}/sync`)) {
          await new Promise<void>((resolve) => nudges.push(resolve));
        }
        return Response.json({ status: 'OK', message: null, data: deviceApi(path, init) ?? null });
      }),
    );
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1' });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('radio', { name: 'Always-on' }, LOAD));
    expect(await within(panel()).findByText('Waiting for the device')).toBeTruthy();
    expect(nudges).toHaveLength(1);
    await act(async () => nudges[0]());

    await waitFor(() => expect(within(panel()).getByRole('button', { name: /Reboot/ }).hasAttribute('disabled')).toBe(false));
    fireEvent.click(within(panel()).getByRole('button', { name: /Reboot/ }));
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Reboot device queued')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(nudges).toHaveLength(2);
    await act(async () => nudges[1]());
  });
});

describe('the Location tab’s time range', () => {
  const FIXES = [
    { lat: 52.52, lon: 13.405, capturedAt: Date.now() - 3 * 3600 * 1000 },
    { lat: 52.5, lon: 13.4, capturedAt: Date.now() - 2 * 24 * 3600 * 1000 },
  ];

  /** The device API with `fixes` as the location history, filtered by `since`; while held, each answer waits for the test. */
  function locationsApi(fixes: { capturedAt: number }[]) {
    const held: (() => void)[] = [];
    const state = { hold: false };
    const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const url = new URL(path, 'http://console');
      let data = deviceApi(path, init);
      if (url.pathname.endsWith('/locations')) {
        if (state.hold) await new Promise<void>((resolve) => held.push(resolve));
        data = fixes.filter((f) => f.capturedAt >= Number(url.searchParams.get('since')));
      }
      return Response.json({ status: 'OK', message: null, data: data ?? null });
    });
    vi.stubGlobal('fetch', fetch);
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1?tab=location' });
    const sinces = () =>
      fetch.mock.calls
        .map(([input]) => new URL(String(input), 'http://console'))
        .filter((u) => u.pathname.endsWith('/locations'))
        .map((u) => Number(u.searchParams.get('since')));
    return { held, state, sinces };
  }
  const range = (name: string) => within(panel()).getByRole('radio', { name });

  it('asks for every fix by default, and for the last hour’s once 1h is picked', async () => {
    const { sinces } = locationsApi(FIXES);
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText(/^2 fixes/, undefined, LOAD)).toBeTruthy();
    expect(sinces().every((since) => since === 0)).toBe(true);
    expect(range('All').getAttribute('aria-checked')).toBe('true');

    const before = Date.now();
    const last = () => sinces()[sinces().length - 1];
    fireEvent.click(range('1h'));
    await waitFor(() => expect(last()).toBeGreaterThan(0));
    expect(last()).toBeGreaterThanOrEqual(before - 3600 * 1000);
    expect(last()).toBeLessThanOrEqual(Date.now() - 3600 * 1000);
  });

  it('says a range is empty, and the rail keeps the latest fix', async () => {
    locationsApi(FIXES);
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText(/^2 fixes/, undefined, LOAD);
    fireEvent.click(range('1h'));
    expect(await within(panel()).findByText('No location in the last hour', undefined, LOAD)).toBeTruthy();
    expect(within(panel()).queryByText('No location reported yet')).toBeNull();
    expect(within(rail()).getByText('52.52000, 13.40500')).toBeTruthy();

    fireEvent.click(range('7d'));
    expect(await within(panel()).findByRole('region', { name: 'Map of the device’s locations in the last 7 days' }, LOAD)).toBeTruthy();
  });

  it('shows the range picked last when an earlier range answers after it', async () => {
    const { held, state } = locationsApi(FIXES);
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText(/^2 fixes/, undefined, LOAD);
    state.hold = true;
    fireEvent.click(range('7d'));
    fireEvent.click(range('1h'));
    await waitFor(() => expect(held).toHaveLength(2));
    await act(async () => held[1]());
    expect(await within(panel()).findByText('No location in the last hour')).toBeTruthy();
    await act(async () => held[0]());
    expect(within(panel()).getByText('No location in the last hour')).toBeTruthy();
    expect(within(panel()).queryByRole('region', { name: /^Map of/ })).toBeNull();
    expect(range('1h').getAttribute('aria-checked')).toBe('true');
  });

  it('says when the server’s cap cut a range', async () => {
    locationsApi(Array.from({ length: LOCATION_HISTORY_LIMIT }, (_, i) => ({ lat: 52, lon: 13, capturedAt: Date.now() - i * 1000 })));
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText(/^newest 500 fixes/, undefined, LOAD);
    fireEvent.click(range('24h'));
    expect(await within(panel()).findByText(/^newest 500 in this range/, undefined, LOAD)).toBeTruthy();
  });
});

describe('core fills the slots like any extension', () => {
  it('drops a core tab and rail group from the page when they leave the registry', async () => {
    const dropped = (c: Contribution) => (c.slot === 'device.tab' || c.slot === 'device.railGroup') && c.id === 'location';
    const { registry } = createRegistry({ ...CORE_EXTENSION, contributions: CORE_EXTENSION.contributions.filter((c) => !dropped(c)) }, []);
    openDevice({ registry });
    await waitFor(() => expect(tabNames()).toEqual(['Control', 'Telemetry', 'Apps', 'Activity1']), LOAD);
    expect(railTitles()).toEqual(['Status', 'Hardware', 'Network', 'Management']);
  });
});

describe('extension tabs and rail groups', () => {
  it('orders them among core’s by ORDER', async () => {
    openDevice({ registry: registryOf(demo) });
    await screen.findByRole('tab', { name: 'Demo' }, LOAD);
    await waitFor(() => expect(tabNames()).toEqual(['Control', 'Telemetry', 'Demo', 'Apps', 'Activity1', 'Location', 'Demo admin']), LOAD);
    expect(railTitles()).toEqual(['Status', 'Location', 'Hardware', 'Network', 'Demo', 'Management']);
    expect(within(rail()).getByText('Demo agent').closest('div, li, dl')?.textContent).toContain('0.3.7');
  });

  it('hides a tab the role may not use', async () => {
    openDevice({ registry: registryOf(demo), user: ROLES.user });
    await screen.findByRole('tab', { name: 'Demo' }, LOAD);
    expect(tabNames()).not.toContain('Demo admin');
  });

  it('opens a tab the role may not use as the first tab, replacing the URL rather than adding to history', async () => {
    openDevice({ registry: registryOf(demo), user: ROLES.user, path: ['/devices', '/devices/WH-1?tab=demo-admin'] });
    await screen.findByRole('tab', { name: 'Demo' }, LOAD);
    await waitFor(() => expect(here).toBe('/devices/WH-1'), LOAD);
    expect(selectedTab()).toBe('Control');
    await act(() => go(-1));
    await waitFor(() => expect(here).toBe('/devices'));
  });

  it('shows a tab that throws as a callout in its own tab only', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Boom = () => {
      throw new Error('boom');
    };
    openDevice({ registry: registryOf(ext('boom', [tab('boom', Boom, { label: 'Boom' })])), path: '/devices/WH-1?tab=boom' });
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText(/The “Boom” extension ran into a problem/)).toBeTruthy();
    expect(railTitles()).toEqual(['Status', 'Location', 'Hardware', 'Network', 'Management']);
    fireEvent.click(screen.getByRole('tab', { name: 'Telemetry' }));
    expect(await within(panel()).findByText('TC52', undefined, LOAD)).toBeTruthy();
    expect(screen.queryByText(/ran into a problem/)).toBeNull();
  });

  it('shows the page, without the icon, when a tab’s icon throws', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const BadIcon = () => {
      throw new Error('no icon');
    };
    openDevice({ registry: registryOf(ext('iconic', [tab('iconic', () => 'Iconic body', { label: 'Iconic', icon: BadIcon } as Partial<Contribution>)])) });
    await waitFor(() => expect(tabNames()).toEqual(['Control', 'Telemetry', 'Apps', 'Activity1', 'Location', 'Iconic']), LOAD);
    expect(screen.getByRole('tab', { name: 'Iconic' }).querySelector('svg')).toBeNull();
    expect(railTitles()).toEqual(['Status', 'Location', 'Hardware', 'Network', 'Management']);
  });

  it('leaves out a count or a rail group whose function throws, and shows a bad rail value as a callout there', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const broken = ext('broken', [
      tab('counted', () => 'body', {
        label: 'Counted',
        count: () => {
          throw new Error('no count');
        },
      } as Partial<Contribution>),
      tab('object-count', () => 'body', { label: 'Odd', count: () => ({}) as string } as Partial<Contribution>),
      {
        slot: 'device.railGroup',
        id: 'throws',
        order: 1,
        title: 'Throws',
        items: () => {
          throw new Error('no items');
        },
      },
      { slot: 'device.railGroup', id: 'bad-value', order: 2, title: 'Bad value', items: () => [{ label: 'x', value: {} as string }] },
    ]);
    openDevice({ registry: registryOf(broken) });
    await screen.findByRole('tab', { name: 'Counted' }, LOAD);
    await waitFor(() => expect(tabNames()).toEqual(['Control', 'Telemetry', 'Apps', 'Activity1', 'Location', 'Counted', 'Odd']), LOAD);
    expect(railTitles()).toEqual(['Status', 'Location', 'Hardware', 'Network', 'Management']);
    expect(within(rail()).getByRole('alert').textContent).toMatch(/The “Broken” extension ran into a problem/);
  });
});

describe('the open tab lives in the URL', () => {
  it('opens a deep link, and Back and Forward move between tabs', async () => {
    openDevice({ path: '/devices/WH-1?tab=telemetry' });
    await screen.findByRole('tab', { name: 'Telemetry' }, LOAD);
    expect(selectedTab()).toBe('Telemetry');
    expect(await within(panel()).findByText('TC52', undefined, LOAD)).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: /Activity/ }));
    expect(here).toBe('/devices/WH-1?tab=activity');
    expect(await within(panel()).findByText('Events')).toBeTruthy();

    await act(() => go(-1));
    await waitFor(() => expect(selectedTab()).toBe('Telemetry'));
    await act(() => go(1));
    await waitFor(() => expect(selectedTab()).toBe('Activity1'));

    // The first tab is the page without a parameter.
    fireEvent.click(screen.getByRole('tab', { name: 'Control' }));
    expect(here).toBe('/devices/WH-1');
  });

  it('moves between tabs with the arrow keys, Home and End in place, so Back leaves the page', async () => {
    openDevice({ path: ['/devices', '/devices/WH-1'] });
    fireEvent.keyDown(await screen.findByRole('tab', { name: 'Control' }, LOAD), { key: 'ArrowRight' });
    await waitFor(() => expect(here).toBe('/devices/WH-1?tab=telemetry'));
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Telemetry' }), { key: 'ArrowRight' });
    await waitFor(() => expect(here).toBe('/devices/WH-1?tab=apps'));
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Apps' }), { key: 'End' });
    await waitFor(() => expect(here).toBe('/devices/WH-1?tab=location'));
    expect(selectedTab()).toBe('Location');
    await act(() => go(-1));
    await waitFor(() => expect(here).toBe('/devices'));
  });

  it('shows a clicked tab at once, before the router renders the navigation', async () => {
    openDevice({ path: '/devices/WH-1?tab=telemetry' });
    await screen.findByRole('tab', { name: 'Telemetry' }, LOAD);
    // The Activity tab's label carries its count, which comes with the events: wait for them before the click.
    await waitFor(() => expect(tabNames()).toContain('Activity1'), LOAD);
    await asInBrowser(async () => {
      screen.getByRole('tab', { name: /Activity/ }).click();
      // React renders a click's own updates before the next task; the router's transition comes after.
      await Promise.resolve();
      expect(here).toBe('/devices/WH-1?tab=telemetry'); // not rendered yet: the gap this test is about
      expect(selectedTab()).toBe('Activity1');
      expect(within(panel()).getByText('Events')).toBeTruthy();
      await waitFor(() => expect(here).toBe('/devices/WH-1?tab=activity'));
    });
  });

  it('follows the URL when Back comes before a click’s navigation has rendered', async () => {
    openDevice({ path: '/devices/WH-1?tab=telemetry' });
    await screen.findByRole('tab', { name: 'Telemetry' }, LOAD);
    await asInBrowser(async () => {
      screen.getByRole('tab', { name: /Activity/ }).click();
      void go(-1);
      await waitFor(() => expect(here).toBe('/devices/WH-1?tab=telemetry'));
      await waitFor(() => expect(selectedTab()).toBe('Telemetry'));
    });
    expect(await within(panel()).findByText('TC52', undefined, LOAD)).toBeTruthy();
  });

  it('opens Activity from the Control tab’s "All activity" link, as a history entry', async () => {
    openDevice();
    // "All activity" shows before the events answer; the Activity tab's count comes with them.
    await waitFor(() => expect(tabNames()).toContain('Activity1'), LOAD);
    fireEvent.click(screen.getByRole('button', { name: 'All activity' }));
    expect(here).toBe('/devices/WH-1?tab=activity');
    expect(selectedTab()).toBe('Activity1');
  });

  it('keeps the rest of the query and the hash when the tab changes', async () => {
    openDevice({ path: ['/devices', '/devices/WH-1?tab=nope&from=list#x'] });
    await waitFor(() => expect(here).toBe('/devices/WH-1?from=list#x'), LOAD);
    fireEvent.click(await screen.findByRole('tab', { name: 'Telemetry' }, LOAD));
    await waitFor(() => expect(here).toBe('/devices/WH-1?from=list&tab=telemetry#x'));
    fireEvent.click(screen.getByRole('tab', { name: 'Control' }));
    await waitFor(() => expect(here).toBe('/devices/WH-1?from=list#x'));
  });

  it('opens an unknown tab as the first one, replacing the URL', async () => {
    openDevice({ path: ['/devices', '/devices/WH-1?tab=nope'] });
    await screen.findByRole('tab', { name: 'Control' }, LOAD);
    await waitFor(() => expect(here).toBe('/devices/WH-1'), LOAD);
    expect(selectedTab()).toBe('Control');
    await act(() => go(-1));
    await waitFor(() => expect(here).toBe('/devices'));
  });

  it('keeps the query and the hash when an old id link moves to the device’s number', async () => {
    openDevice({ path: `/devices/${DEVICE.id}?tab=telemetry#x` });
    await waitFor(() => expect(here).toBe('/devices/WH-1?tab=telemetry#x'), LOAD);
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('TC52', undefined, LOAD)).toBeTruthy();
  });

  it('waits for the active server plugins before deciding on a plugin tab', async () => {
    const answer = holdPlugins();
    renderInConsole(
      <>
        <AppRoutes />
        <Probe />
      </>,
      { registry: registryOf(plugExtension(1000)), path: '/devices/WH-1?tab=plug' },
    );
    await screen.findByRole('tab', { name: 'Control' }, LOAD);
    expect(here).toBe('/devices/WH-1?tab=plug'); // not sent to the first tab meanwhile
    expect(screen.queryByRole('tabpanel')).toBeNull();
    // The strip stays in the Tab order meanwhile, through its first tab.
    expect(screen.getAllByRole('tab').filter((t) => t.tabIndex === 0).map((t) => t.textContent)).toEqual(['Control']);
    await answer(['plug']);
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('Plug tab body')).toBeTruthy();
    expect(selectedTab()).toBe('Plug');
  });

  it('opens no tab while a plugin tab ahead of the first waits, so the open tab does not change under the user', async () => {
    const answer = holdPlugins();
    renderInConsole(<AppRoutes />, { registry: registryOf(plugExtension(ORDER.deviceTab.control - 50)), path: '/devices/WH-1' });
    await screen.findByRole('tab', { name: 'Control' }, LOAD);
    expect(screen.queryByRole('tabpanel')).toBeNull();
    await answer(['plug']);
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('Plug tab body')).toBeTruthy();
    expect(tabNames()[0]).toBe('Plug');
  });

  it('opens the first tab once the plugin list leaves out the tab ahead of it', async () => {
    const answer = holdPlugins();
    renderInConsole(<AppRoutes />, { registry: registryOf(plugExtension(ORDER.deviceTab.control - 50)), path: '/devices/WH-1' });
    await screen.findByRole('tab', { name: 'Control' }, LOAD);
    await answer([]);
    expect(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('Recent commands', undefined, LOAD)).toBeTruthy();
    expect(selectedTab()).toBe('Control');
  });
});

describe('useDevice()', () => {
  it('hands a tab the device, its telemetry, state, configuration and status', async () => {
    openDevice({ registry: registryOf(demo), path: '/devices/WH-1?tab=demo' });
    const body = within(await screen.findByRole('tabpanel', undefined, LOAD));
    expect(await body.findByText('Device WH-1', undefined, LOAD)).toBeTruthy();
    expect(await body.findByText('TC52', undefined, LOAD)).toBeTruthy();
    expect(await body.findByText('0.3.7', undefined, LOAD)).toBeTruthy();
    expect(await body.findByText('Warehouse', undefined, LOAD)).toBeTruthy();
    expect(await body.findByText('true', undefined, LOAD)).toBeTruthy();
  });

  it('hands a tab only the public fields: core’s own parts cannot be reached from it', async () => {
    let seen: object = {};
    const Peek = () => {
      seen = useDevice();
      return 'Peeked';
    };
    openDevice({ registry: registryOf(ext('peek', [tab('peek', Peek, { label: 'Peek' })])), path: '/devices/WH-1?tab=peek' });
    await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByText('Peeked');
    expect(Object.getOwnPropertySymbols(seen)).toEqual([]);
    // Every contribution shares this one object, so none may swap a function out from under the others.
    expect(Object.isFrozen(seen)).toBe(true);
    expect(Reflect.ownKeys(seen).sort()).toEqual([
      'configStatus', 'configuration', 'device', 'now', 'refresh', 'sendCommand', 'state', 'telemetry', 'telemetryLoaded',
    ]);
  });

  it('refresh() polls again now', async () => {
    const { fetch } = openDevice({ registry: registryOf(demo), path: '/devices/WH-1?tab=demo' });
    const body = within(await screen.findByRole('tabpanel', undefined, LOAD));
    await body.findByText('TC52', undefined, LOAD);
    const before = calls(fetch, '/telemetry').length;
    fireEvent.click(body.getByRole('button', { name: 'Refresh now' }));
    await waitFor(() => expect(calls(fetch, '/telemetry').length).toBe(before + 1), LOAD);
  });

  it('sendCommand() queues the command as the page does: toast, nudge, refresh, and says it was queued', async () => {
    const { fetch } = openDevice({ registry: registryOf(demo), path: '/devices/WH-1?tab=demo' });
    const body = within(await screen.findByRole('tabpanel', undefined, LOAD));
    await body.findByText('TC52', undefined, LOAD);
    const polls = calls(fetch, '/telemetry').length;
    fireEvent.click(body.getByRole('button', { name: 'Ring from demo' }));
    expect(await screen.findByText('Demo ring queued')).toBeTruthy();
    expect(await body.findByText('Ring queued')).toBeTruthy();
    const [queued] = calls(fetch, `/devices/${DEVICE.number}/commands`, 'POST');
    expect(JSON.parse(String(queued[1]?.body))).toEqual({ type: 'device.ring', requiresCapability: 'device.ring' });
    expect(calls(fetch, `/devices/${DEVICE.number}/sync`, 'POST')).toHaveLength(1);
    await waitFor(() => expect(calls(fetch, '/telemetry').length).toBeGreaterThan(polls), LOAD);
  });

  it('sendCommand() toasts a failure and resolves to false', async () => {
    // The server refuses the command (an Observer lacks edit_devices): an error envelope.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (init?.method === 'POST' && path.endsWith(`/devices/${DEVICE.number}/commands`)) {
          return Response.json({ status: 'ERROR', message: 'Permission denied', data: null });
        }
        return Response.json({ status: 'OK', message: null, data: deviceApi(path, init) ?? null });
      }),
    );
    renderInConsole(<AppRoutes />, { registry: registryOf(demo), path: '/devices/WH-1?tab=demo' });
    const body = within(await screen.findByRole('tabpanel', undefined, LOAD));
    await body.findByText('TC52', undefined, LOAD);
    fireEvent.click(body.getByRole('button', { name: 'Ring from demo' }));
    expect(await screen.findByText('Demo ring failed')).toBeTruthy();
    expect(await body.findByText('Ring not queued')).toBeTruthy();
  });

  it('sendCommand() builds the request from the values when the command has build and no request', async () => {
    const note: DeviceCommand = { label: 'Note', build: (v) => ({ type: 'device.note', payload: JSON.stringify(v) }) };
    const { fetch } = openDevice({ registry: registryOf(commandsExtension([note], { text: 'hi' })), path: '/devices/WH-1?tab=commands' });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Sent 1')).toBeTruthy();
    const [queued] = calls(fetch, `/devices/${DEVICE.number}/commands`, 'POST');
    expect(JSON.parse(String(queued[1]?.body))).toEqual({ type: 'device.note', payload: '{"text":"hi"}' });
  });

  it('sendCommand() leaves the page’s own pending connection and location modes alone', async () => {
    // A `key` is how the page marks its own commands; an extension's command carrying one must not pass for them.
    const posing: (DeviceCommand & { key: string })[] = [
      { key: 'power-always', label: 'Posing power', request: { type: 'agent.setPowerMode', payload: '{"mode":"alwaysOn"}' } },
      { key: 'location-active', label: 'Posing location', request: { type: 'agent.setLocationMode', payload: '{"mode":"active"}' } },
    ];
    // A configuration that tracks location: under "Do not track" the location marker never shows at all.
    stubApi((path, init) =>
      path.endsWith('/configurations/3') ? { id: 3, name: 'Warehouse', kioskMode: false, requestUpdates: 'WIFI' } : deviceApi(path, init),
    );
    renderInConsole(<AppRoutes />, { registry: registryOf(commandsExtension(posing)), path: '/devices/WH-1?tab=commands' });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Sent 2')).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'Control' }));
    await within(panel()).findByText('Recent commands', undefined, LOAD);
    expect(within(panel()).queryByText('Waiting for the device')).toBeNull();
    expect(within(panel()).queryByText('Sent')).toBeNull();
    // The page's own location command does mark it, on the same configuration.
    await waitFor(() => expect(within(panel()).getByRole('radio', { name: 'Accurate' }).hasAttribute('disabled')).toBe(false), LOAD);
    fireEvent.click(within(panel()).getByRole('radio', { name: 'Accurate' }));
    expect(await within(panel()).findByText('Sent')).toBeTruthy();
  });

  it('sendCommand() sends the request of a command whose build is left undefined', async () => {
    // Plain JavaScript, or a copy of the page's own `{ request, build: t.build }`: an undefined build is no build.
    const ring = { label: 'Ring', request: { type: 'device.ring', requiresCapability: 'device.ring' }, build: undefined };
    const { fetch } = openDevice({
      registry: registryOf(commandsExtension([ring as unknown as DeviceCommand])),
      path: '/devices/WH-1?tab=commands',
    });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('button', { name: 'Send' }));
    expect(await screen.findByText('Sent 1')).toBeTruthy();
    const [queued] = calls(fetch, `/devices/${DEVICE.number}/commands`, 'POST');
    expect(JSON.parse(String(queued[1]?.body))).toEqual({ type: 'device.ring', requiresCapability: 'device.ring' });
  });

  it('sendCommand() leaves the page’s actions usable while its command is on the way', async () => {
    let queue!: () => void;
    const queued = new Promise<void>((resolve) => (queue = resolve));
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (init?.method === 'POST' && path.endsWith(`/devices/${DEVICE.number}/commands`)) await queued;
        return Response.json({ status: 'OK', message: null, data: deviceApi(path, init) ?? null });
      }),
    );
    const ring: DeviceCommand = { label: 'Ring', request: { type: 'device.ring', requiresCapability: 'device.ring' } };
    renderInConsole(<AppRoutes />, { registry: registryOf(commandsExtension([ring])), path: '/devices/WH-1?tab=commands' });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('button', { name: 'Send' }));
    fireEvent.click(screen.getByRole('tab', { name: 'Control' }));
    expect((await within(panel()).findByRole('button', { name: /Lock now/ })).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: 'Sync now' }).hasAttribute('disabled')).toBe(false);
    await act(async () => queue());
    expect(await screen.findByText('Ring queued')).toBeTruthy();
  });

  it('keeps refresh() and sendCommand() the same functions across polls and toasts', async () => {
    const seen = new Set<unknown>();
    const ring: DeviceCommand = { label: 'Ring', request: { type: 'device.ring', requiresCapability: 'device.ring' } };
    function Stable() {
      const { refresh, sendCommand } = useDevice();
      seen.add(refresh).add(sendCommand);
      return (
        <button type="button" onClick={() => void sendCommand(ring)}>
          Ring
        </button>
      );
    }
    openDevice({ registry: registryOf(ext('stable', [tab('stable', Stable, { label: 'Stable' })])), path: '/devices/WH-1?tab=stable' });
    fireEvent.click(await within(await screen.findByRole('tabpanel', undefined, LOAD)).findByRole('button', { name: 'Ring' }));
    expect(await screen.findByText('Ring queued')).toBeTruthy();
    expect(seen.size).toBe(2);
  });

  it('says where it works when called outside a device tab', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const Outside = () => <p>{useDevice().device.number}</p>;
    expect(() => render(<Outside />)).toThrow('useDevice() works only inside a device.tab component');
  });
});
