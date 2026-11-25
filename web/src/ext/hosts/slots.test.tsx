// Settings, the Overview and the Devices and Apps tables as hosts: core's sections, cards and actions and the
// extensions' through one registry, ordered together, gated by `requires`, each in its own boundary. Rendered
// through the real routes against a stubbed API, as a fork's extension would be.
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { createElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../../api/auth';
import { AppRoutes } from '../../App';
import { ROLES, registryOf, renderInConsole, settled, stubApi } from '../../test/console';
import demo from '../__fixtures__/demo';
import { CORE_EXTENSION } from '../core';
import { ORDER } from '../core/order';
import { createRegistry, type Registry } from '../registry';
import { defineExtension, lazyComponent, type Contribution, type LazyComponent, type SettingsSectionProps } from '../types';

const NOW = Date.now();
const DEVICES = [
  { id: 8, number: 'WH-2', description: 'Back office', configurationId: 3, lastUpdate: NOW - 60_000 },
  { id: 7, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW },
];
const APPS = [
  { id: 1, name: 'Scanner', pkg: 'com.acme.scanner', version: '1.0', type: 'app', url: 'https://cdn.example.com/s.apk', hasApkHash: true },
  { id: 2, name: 'Notes', pkg: 'com.acme.notes.pro', version: '2.1', type: 'app', url: 'https://cdn.example.com/n.apk', hasApkHash: true },
];

/** Two devices, a configuration, the library, and a queued command. */
function api(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  if (p === '/devices/search') {
    return { devices: { items: DEVICES, totalItemsCount: DEVICES.length }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
  }
  if (p === '/configurations/search') return [{ id: 3, name: 'Warehouse' }];
  if (p === '/applications/search') return APPS;
  if (p === '/agent/v1/bulk/commands') return { queued: 2, skipped: [] };
  if (p.endsWith('/commands') && init?.method === 'POST') return { id: 42 };
  return null;
}

function open(
  path: string,
  {
    registry = registryOf(demo),
    user = ROLES.superAdmin,
    server = api,
  }: { registry?: Registry; user?: AuthUser; server?: typeof api } = {},
) {
  const fetch = stubApi(server);
  renderInConsole(<AppRoutes />, { path, registry, user });
  return fetch;
}

const ext = (id: string, contributions: Contribution[]) =>
  defineExtension({ id, apiVersion: 1, title: id[0].toUpperCase() + id.slice(1), contributions });
/** A body that throws on render, for any slot's props. */
function throwing<P extends object>(what: string): LazyComponent<P> {
  const Broken = (_: P): never => {
    throw new Error(what);
  };
  return lazyComponent(async () => ({ Broken }), 'Broken');
}
/** Core without one of its contributions: the page must lose it, which shows the page is built from the registry. */
const coreWithout = (key: string) => {
  const { registry } = createRegistry(
    { ...CORE_EXTENSION, contributions: CORE_EXTENSION.contributions.filter((c) => `${c.slot}:${c.id}` !== key) },
    [],
  );
  return registry;
};
const main = () => within(screen.getByRole('main'));
const quiet = () => vi.spyOn(console, 'error').mockImplementation(() => {});

describe('settings.section', () => {
  const nav = () => within(screen.getByRole('navigation', { name: 'Settings sections' }));
  const navLinks = () => nav().getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')]);
  const sectionIds = () => Array.from(document.querySelectorAll('.v2-card[id]')).map((c) => c.id);

  it('shows core’s sections and nav as before without extensions', async () => {
    open('/settings', { registry: registryOf() });
    await main().findByRole('heading', { name: 'Appearance' });
    await settled();
    expect(navLinks()).toEqual([
      ['Account', '#account'], ['Server', '#server'], ['Updates', '#updates'], ['Agent rollout', '#agent-rollout'],
      ['Enrollment', '#enrollment'], ['Appearance', '#appearance'],
    ]);
    expect(sectionIds()).toEqual(['account', 'server', 'updates', 'agent-rollout', 'enrollment', 'appearance']);
    expect(document.getElementById('account')?.getAttribute('aria-labelledby')).toBe('account-title');
  });

  it('places an extension’s section and nav entry among core’s, under its own anchor', async () => {
    open('/settings');
    const card = await main().findByRole('heading', { name: 'Demo settings' });
    expect(navLinks()[3]).toEqual(['Demo', '#x-demo-demo']);
    expect(sectionIds().indexOf('x-demo-demo')).toBe(3);
    expect(card.id).toBe('x-demo-demo-title');
    expect(main().getByText('Anchored at #x-demo-demo')).toBeTruthy();
  });

  it('drops a core section with its nav entry when it leaves the registry', async () => {
    open('/settings', { registry: coreWithout('settings.section:enrollment') });
    await main().findByRole('heading', { name: 'Appearance' });
    await settled();
    expect(sectionIds()).not.toContain('enrollment');
    expect(navLinks().map(([label]) => label)).not.toContain('Enrollment');
  });

  it('hides a section the role may not use, with its nav entry', async () => {
    const gated = ext('gated', [
      { slot: 'settings.section', id: 'audit', order: 50, title: 'Audit', requires: { permission: 'settings' }, component: throwing('never') },
    ]);
    open('/settings', { registry: registryOf(gated), user: ROLES.user });
    await main().findByRole('heading', { name: 'Appearance' });
    await settled();
    expect(navLinks().map(([label]) => label)).not.toContain('Audit');
  });

  it('shows a section that crashes as a callout in its own card, keeping its anchor and heading', async () => {
    quiet();
    const broken = ext('broken', [{ slot: 'settings.section', id: 'oops', order: 150, title: 'Oops', component: throwing('boom') }]);
    open('/settings', { registry: registryOf(broken) });
    const callout = await main().findByText(/The “Broken” extension ran into a problem/);
    const card = document.getElementById('x-broken-oops');
    expect(card?.contains(callout)).toBe(true);
    expect(within(card as HTMLElement).getByRole('heading', { name: 'Oops' })).toBeTruthy();
    expect(main().getByRole('heading', { name: 'Server' })).toBeTruthy();
  });

  it('keeps a crashed core section’s card class, so it looks like its neighbours', async () => {
    quiet();
    const { registry } = createRegistry(
      {
        ...CORE_EXTENSION,
        contributions: CORE_EXTENSION.contributions.map((c) =>
          c.slot === 'settings.section' && c.id === 'appearance' ? { ...c, component: throwing<SettingsSectionProps>('boom') } : c,
        ),
      },
      [],
    );
    open('/settings', { registry });
    const callout = await main().findByText(/This part of the page ran into a problem/);
    const card = document.getElementById('appearance') as HTMLElement;
    expect(card.contains(callout)).toBe(true);
    expect(card.classList).toContain('v2-settings-card');
  });
});

describe('overview.card', () => {
  const statLabels = () => Array.from(document.querySelectorAll('.v2-statgrid .v2-stat__label')).map((d) => d.textContent);
  /** The card rows of the Overview grid: each main cell starts a row, the aside cell after it joins that row. */
  const columns = () => {
    const rows: Array<Array<[string, Array<string | null>]>> = [];
    for (const cell of Array.from(document.querySelectorAll('.v2-ov-main, .v2-ov-aside'))) {
      const kind = cell.classList.contains('v2-ov-main') ? 'v2-ov-main' : 'v2-ov-aside';
      const cards = Array.from(cell.children).map((c) => c.getAttribute('aria-labelledby') ?? c.getAttribute('aria-label'));
      if (kind === 'v2-ov-main' || rows.length === 0) rows.push([]);
      rows[rows.length - 1].push([kind, cards]);
    }
    return rows;
  };

  it('lays out core’s cards in their rows and columns as before without extensions', async () => {
    open('/', { registry: registryOf() });
    await main().findByText('Configuration in sync');
    await settled();
    expect(statLabels()).toEqual(['Configuration in sync']);
    expect(columns()).toEqual([[['v2-ov-main', ['v2-ov-att', 'v2-ov-act']], ['v2-ov-aside', ['v2-ov-cfg', 'v2-ov-srv']]]]);
  });

  it('stacks an aside card under the core card ahead of it, and places a stat tile among core’s', async () => {
    open('/');
    await main().findByRole('heading', { name: 'Demo card' });
    await main().findByText('Demo stat');
    await settled();
    expect(statLabels()).toEqual(['Configuration in sync', 'Demo stat']);
    expect(columns()[0]).toEqual([['v2-ov-main', ['v2-ov-att', 'v2-ov-act']], ['v2-ov-aside', ['v2-ov-cfg', 'Demo card', 'v2-ov-srv']]]);
  });

  it('keeps By configuration’s column width when an extension aside heads that column', async () => {
    const ahead = ext('ahead', [
      { slot: 'overview.card', id: 'ahead', order: ORDER.overviewCard.needsAttention + 50, placement: 'aside', component: demoCard() },
    ]);
    open('/', { registry: registryOf(ahead) });
    await main().findByRole('heading', { name: 'Demo card' });
    await settled();
    expect(columns()[0]).toEqual([['v2-ov-main', ['v2-ov-att', 'v2-ov-act']], ['v2-ov-aside', ['Demo card', 'v2-ov-cfg', 'v2-ov-srv']]]);
  });

  it('drops a core card when it leaves the registry', async () => {
    open('/', { registry: coreWithout('overview.card:recent-activity') });
    await main().findByText('Configuration in sync');
    await settled();
    expect(columns().flat().map(([, cards]) => cards).flat()).not.toContain('v2-ov-act');
  });

  it('shows a card that crashes as a callout in its place only', async () => {
    quiet();
    const broken = ext('broken', [{ slot: 'overview.card', id: 'oops', order: 50, placement: 'main', component: throwing('boom') }]);
    open('/', { registry: registryOf(broken) });
    expect(await main().findByText(/The “Broken” extension ran into a problem/)).toBeTruthy();
    await settled();
    expect(main().getByText('Configuration in sync')).toBeTruthy();
    expect(main().getByRole('heading', { name: /Needs attention/ })).toBeTruthy();
  });

  it('shows a stat tile that crashes as a small mark in the stat grid', async () => {
    quiet();
    const broken = ext('broken', [{ slot: 'overview.card', id: 'oops', order: 150, placement: 'stat', component: throwing('boom') }]);
    open('/', { registry: registryOf(broken) });
    await main().findByText('Configuration in sync');
    await settled();
    const grid = document.querySelector('.v2-statgrid') as HTMLElement;
    const mark = await within(grid).findByText('Error');
    expect(within(grid).queryByRole('alert')).toBeNull();
    // Still what a <dl> may hold: each tile a div of a dt and its dd.
    expect(Array.from(grid.children).map((c) => [c.tagName, c.className])).toEqual([['DIV', 'v2-stat'], ['DIV', 'v2-stat']]);
    expect(mark.closest('dd')?.previousElementSibling?.tagName).toBe('DT');
  });

  it('keeps an extension’s stat tiles on the first-run Overview, without core’s', async () => {
    const empty = (path: string, init?: RequestInit): unknown =>
      new URL(path, 'http://console').pathname.endsWith('/devices/search')
        ? { devices: { items: [], totalItemsCount: 0 }, configurations: {} }
        : api(path, init);
    open('/', { server: empty });
    await main().findByRole('heading', { name: 'Enroll your first device' });
    await main().findByText('Demo stat');
    expect(statLabels()).toEqual(['Demo stat']);
  });

  it('hides a card the role may not use', async () => {
    const gated = ext('gated', [
      { slot: 'overview.card', id: 'audit', order: 50, placement: 'stat', requires: { permission: 'settings' }, component: demoStat() },
    ]);
    open('/', { registry: registryOf(gated), user: ROLES.user });
    await main().findByText('Configuration in sync');
    await settled();
    expect(statLabels()).toEqual(['Configuration in sync']);
  });
});

function demoCard() {
  return lazyComponent(() => import('../__fixtures__/demo/DemoOverviewCard'), 'DemoOverviewCard');
}

function demoStat() {
  return lazyComponent(() => import('../__fixtures__/demo/DemoOverviewCard'), 'DemoStat');
}

describe('list.column, list.rowAction, list.bulkAction on Devices', () => {
  const table = () => within(screen.getByRole('table', { name: 'Devices' }));
  const headers = () => table().getAllByRole('columnheader').map((h) => h.textContent);
  const firstColumn = (header: string) => {
    const index = headers().indexOf(header);
    return table().getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[index]?.textContent);
  };
  /** The bulk bar's action buttons (not its Select all / Clear). */
  const bulkButtons = () =>
    within(screen.getByRole('region', { name: 'Selection' }))
      .getAllByRole('button')
      .map((b) => b.textContent)
      .filter((t) => !/^(Select all|Clear)/.test(t ?? ''));
  const openMenu = async (number: string) => {
    fireEvent.click(main().getByRole('button', { name: `More actions for ${number}` }));
    return within(await screen.findByRole('menu'));
  };

  it('keeps core’s columns, row menu and bulk bar as before without extensions', async () => {
    open('/devices', { registry: registryOf() });
    await main().findByRole('link', { name: /Front desk/ });
    expect(headers()).toEqual(['', 'Device', 'Status', 'Configuration', 'Android', 'Last seen ↓', 'Actions']);
    const menu = await openMenu('WH-1');
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Lock', 'Ring', 'Change configuration', 'Delete']);
    expect(menu.getAllByRole('separator')).toHaveLength(1);
    fireEvent.keyDown(menu.getAllByRole('menuitem')[0], { key: 'Escape' });
    fireEvent.click(main().getByRole('checkbox', { name: 'Select WH-1' }));
    await screen.findByRole('region', { name: 'Selection' });
    expect(bulkButtons()).toEqual(['Run action', 'Change configuration', 'Delete']);
  });

  it('places an extension column among core’s, and sorts by its value', async () => {
    open('/devices');
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    expect(headers()).toEqual(['', 'Device', 'Status', 'Configuration', 'Android', 'Demo id', 'Last seen ↓', 'Actions']);
    expect(firstColumn('Demo id')).toEqual(['#7', '#8']); // last seen first, the default
    fireEvent.click(table().getByRole('button', { name: 'Demo id' }));
    expect(firstColumn('Demo id ↑')).toEqual(['#7', '#8']);
    fireEvent.click(table().getByRole('button', { name: /Demo id/ }));
    expect(firstColumn('Demo id ↓')).toEqual(['#8', '#7']);
  });

  it('goes back to the default sort when the column it sorts by goes away', async () => {
    const gated = ext('gated', [
      {
        slot: 'list.column', id: 'num', list: 'devices', order: 1, header: 'Num', requires: { permission: 'settings' },
        cell: (d) => d.number, sortValue: (d) => d.number,
      },
    ]);
    let current: AuthUser = ROLES.admin;
    stubApi((path, init) => (path.endsWith('/users/current') ? current : api(path, init)));
    renderInConsole(<AppRoutes />, { path: '/devices', registry: registryOf(gated), user: ROLES.admin });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click(table().getByRole('button', { name: 'Num' }));
    fireEvent.click(table().getByRole('button', { name: /Num/ }));
    expect(firstColumn('Num ↓')).toEqual(['WH-2', 'WH-1']);

    current = { ...ROLES.user, id: ROLES.admin.id, login: ROLES.admin.login };
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    await waitFor(() => expect(headers()).not.toContain('Num ↓'));
    expect(headers()).toContain('Last seen ↓');
    expect(firstColumn('Device')).toEqual([expect.stringContaining('Front desk'), expect.stringContaining('Back office')]);
  });

  it('keeps a broken cell to its cell: a throw shows —, a value React cannot render an error mark', async () => {
    quiet();
    const broken = ext('broken', [
      { slot: 'list.column', id: 'throws', list: 'devices', order: 1, header: 'Throws', cell: () => { throw new Error('boom'); } },
      { slot: 'list.column', id: 'object', list: 'devices', order: 2, header: createElement('span', null, 'Object'), cell: () => ({ not: 'a node' }) as never },
    ]);
    open('/devices', { registry: registryOf(broken) });
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    expect(firstColumn('Throws')).toEqual(['—', '—']);
    expect(firstColumn('Object')).toEqual(['Error', 'Error']);
    expect(firstColumn('Device')).toEqual([expect.stringContaining('Front desk'), expect.stringContaining('Back office')]);
  });

  it('leaves out a row action that throws or returns no menu item, and keeps a header that crashes to its cell', async () => {
    quiet();
    const Crash = (): never => {
      throw new Error('header');
    };
    const broken = ext('broken', [
      { slot: 'list.rowAction', id: 'throws', list: 'devices', order: 150, item: () => { throw new Error('boom'); } },
      { slot: 'list.rowAction', id: 'junk', list: 'devices', order: 160, item: () => ({ key: 'x', label: {} }) as never },
      { slot: 'list.column', id: 'crash', list: 'devices', order: 1, header: createElement(Crash), cell: () => 'ok' },
    ]);
    open('/devices', { registry: registryOf(broken) });
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    expect(headers()).toContain('Error');
    expect(firstColumn('Error')).toEqual(['ok', 'ok']);
    const menu = await openMenu('WH-1');
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Lock', 'Ring', 'Change configuration', 'Delete']);
  });

  it('hands actions helpers they cannot change, so core’s Lock still sends its own command', async () => {
    quiet();
    const hijack = vi.fn(async () => true);
    const meddling = ext('meddling', [
      {
        slot: 'list.rowAction',
        id: 'meddle',
        list: 'devices',
        order: 50,
        item: (_device, helpers) => {
          (helpers as { sendCommand: unknown }).sendCommand = hijack;
          return null;
        },
      },
    ]);
    const fetch = open('/devices', { registry: registryOf(meddling) });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click((await openMenu('WH-1')).getByRole('menuitem', { name: 'Lock' }));
    expect(await screen.findByText('Lock queued')).toBeTruthy();
    expect(hijack).not.toHaveBeenCalled();
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/devices/WH-1/commands'))).toBe(true);
    await settled();
  });

  it('keeps a row action’s label that crashes to its menu item, which still works', async () => {
    quiet();
    const Crash = (): never => {
      throw new Error('label');
    };
    const onSelect = vi.fn();
    const broken = ext('broken', [
      { slot: 'list.rowAction', id: 'crash', list: 'devices', order: ORDER.devicesRowAction.ring + 10, item: () => ({ key: 'x', label: createElement(Crash), textValue: 'Crash', onSelect }) },
    ]);
    open('/devices', { registry: registryOf(broken) });
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    const menu = await openMenu('WH-1');
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Lock', 'Ring', 'Error', 'Change configuration', 'Delete']);
    fireEvent.click(menu.getByRole('menuitem', { name: 'Error' }));
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it('keeps a bulk action whose run throws, or whose icon crashes, to its own button', async () => {
    quiet();
    const Crash = (): never => {
      throw new Error('icon');
    };
    const broken = ext('broken', [
      { slot: 'list.bulkAction', id: 'throws', list: 'devices', order: 1000, label: 'Throws', run: () => { throw new Error('boom'); } },
      { slot: 'list.bulkAction', id: 'rejects', list: 'devices', order: 1010, label: 'Rejects', run: () => Promise.reject(new Error('boom')) },
      { slot: 'list.bulkAction', id: 'icon', list: 'devices', order: 1020, label: 'Bad icon', icon: createElement(Crash), run: () => {} },
    ]);
    open('/devices', { registry: registryOf(broken) });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click(main().getByRole('checkbox', { name: 'Select WH-1' }));
    await screen.findByRole('region', { name: 'Selection' });
    expect(bulkButtons()).toEqual(['Run action', 'Change configuration', 'Delete', 'Throws', 'Rejects', 'Bad icon']);
    fireEvent.click(main().getByRole('button', { name: 'Throws' }));
    const rejects = main().getByRole('button', { name: 'Rejects' });
    fireEvent.click(rejects);
    await waitFor(() => expect((rejects as HTMLButtonElement).disabled).toBe(false)); // settled, not stuck busy
    fireEvent.click(main().getByRole('button', { name: 'Run action' }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
  });

  it('puts an extension row action among core’s, and hands it that row’s device', async () => {
    const fetch = open('/devices');
    await main().findByRole('link', { name: /Back office/ });
    const menu = await openMenu('WH-2');
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual([
      'Lock', 'Ring', 'Demo ping', 'Change configuration', 'Delete', 'Demo note',
    ]);
    fireEvent.click(menu.getByRole('menuitem', { name: 'Demo ping' }));
    expect(await screen.findByText('Demo ping queued')).toBeTruthy();
    const posts = fetch.mock.calls.filter(([url, init]) => init?.method === 'POST' && String(url).includes('/commands'));
    expect(posts.map(([url]) => String(url))).toEqual([expect.stringContaining('/devices/WH-2/commands')]);
    expect(JSON.parse(String(posts[0][1]?.body))).toEqual({ type: 'demo.ping' });
    await settled();
  });

  it('puts an extension bulk action among core’s, and hands it the selected devices', async () => {
    open('/devices');
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    fireEvent.click(main().getByRole('checkbox', { name: 'Select all on this page' }));
    await screen.findByRole('region', { name: 'Selection' });
    expect(bulkButtons()).toEqual(['Run action', 'Change configuration', 'Demo export', 'Delete']);
    fireEvent.click(main().getByRole('button', { name: 'Demo export' }));
    expect(await screen.findByText('WH-1, WH-2')).toBeTruthy();
  });

  it('sends a command to several devices as one bulk request', async () => {
    const bulk = ext('bulk', [
      {
        slot: 'list.bulkAction',
        id: 'ping',
        list: 'devices',
        order: 1000,
        label: 'Ping all',
        run: (devices, { sendCommand }) => sendCommand(devices, { label: 'Ping', request: { type: 'demo.ping' } }).then(() => undefined),
      },
    ]);
    const fetch = open('/devices', { registry: registryOf(bulk) });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click(main().getByRole('checkbox', { name: 'Select all on this page' }));
    fireEvent.click(await main().findByRole('button', { name: 'Ping all' }));
    expect(await screen.findByText('Queued for 2 devices.')).toBeTruthy();
    const [post] = fetch.mock.calls.filter(([url]) => String(url).endsWith('/agent/v1/bulk/commands'));
    expect(JSON.parse(String(post[1]?.body))).toEqual({ deviceIds: [7, 8], command: { type: 'demo.ping' } });
  });

  it('refuses wipe, passcode reset and uninstall for any number of devices, one included, before any request', async () => {
    const sent: boolean[] = [];
    const destructive = ext('destructive', [
      {
        slot: 'list.rowAction',
        id: 'wipe',
        list: 'devices',
        order: 150,
        item: (device, { sendCommand }) => ({
          key: 'wipe',
          label: 'Wipe now',
          onSelect: () => void sendCommand([device], { label: 'Wipe', request: { type: 'device.wipe' } }).then((ok) => sent.push(ok)),
        }),
      },
      {
        slot: 'list.bulkAction',
        id: 'reset',
        list: 'devices',
        order: 1000,
        label: 'Reset passcodes',
        run: (devices, { sendCommand }) =>
          sendCommand(devices, { label: 'Reset', request: { type: ' Device.PasscodeReset' } }).then((ok) => void sent.push(ok)),
      },
      {
        slot: 'list.bulkAction',
        id: 'uninstall',
        list: 'devices',
        order: 1001,
        label: 'Uninstall POS',
        run: (devices, { sendCommand }) =>
          sendCommand(devices, {
            label: 'Uninstall',
            request: { type: 'app.uninstall', payload: JSON.stringify({ packageName: 'com.example.pos' }) },
          }).then((ok) => void sent.push(ok)),
      },
    ]);
    const fetch = open('/devices', { registry: registryOf(destructive) });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click((await openMenu('WH-1')).getByRole('menuitem', { name: 'Wipe now' }));
    expect(await screen.findByText('Wipe failed')).toBeTruthy();
    fireEvent.click(main().getByRole('checkbox', { name: 'Select all on this page' }));
    fireEvent.click(await main().findByRole('button', { name: 'Reset passcodes' }));
    expect(await screen.findByText('Reset failed')).toBeTruthy();
    fireEvent.click(await main().findByRole('button', { name: 'Uninstall POS' }));
    expect(await screen.findByText('Uninstall failed')).toBeTruthy();
    expect(sent).toEqual([false, false, false]);
    expect(fetch.mock.calls.filter(([url]) => String(url).includes('/commands'))).toEqual([]);
  });

  it('hides a bulk action the role may not use', async () => {
    open('/devices', { user: ROLES.user });
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    fireEvent.click(main().getByRole('checkbox', { name: 'Select WH-1' }));
    await screen.findByRole('region', { name: 'Selection' });
    expect(bulkButtons()).toEqual(['Run action', 'Change configuration', 'Delete']); // Demo export needs settings
  });

  it('drops the selection when a role refresh leaves no bulk action, and a row click opens the device again', async () => {
    let current: AuthUser = ROLES.admin;
    stubApi((path, init) => (path.endsWith('/users/current') ? current : api(path, init)));
    renderInConsole(<AppRoutes />, { path: '/devices', registry: registryOf(), user: ROLES.admin });
    await main().findByRole('link', { name: /Front desk/ });
    fireEvent.click(main().getByRole('checkbox', { name: 'Select WH-1' }));
    await screen.findByRole('region', { name: 'Selection' });

    current = { ...ROLES.observer, id: ROLES.admin.id, login: ROLES.admin.login };
    act(() => void document.dispatchEvent(new Event('visibilitychange')));
    await waitFor(() => expect(main().queryByRole('checkbox', { name: 'Select WH-1' })).toBeNull());
    expect(screen.queryByRole('region', { name: 'Selection' })).toBeNull();
    fireEvent.click(main().getByText('Back office'));
    expect(await screen.findByRole('tabpanel')).toBeTruthy(); // the device page
    await settled();
  });

  it('leaves an Observer only the row action that writes nothing, and no selection once no bulk action is left', async () => {
    open('/devices', { user: ROLES.observer });
    await main().findByRole('link', { name: /Front desk/ });
    await settled();
    expect(main().queryByRole('checkbox', { name: 'Select WH-1' })).toBeNull();
    expect(main().queryByRole('button', { name: 'Sync now: WH-1' })).toBeNull();
    const menu = await openMenu('WH-1');
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Demo note']);
    expect(menu.queryAllByRole('separator')).toHaveLength(0);
  });
});

describe('list.column and list.rowAction on Apps', () => {
  it('keeps the library’s columns as before without extensions', async () => {
    open('/apps', { registry: registryOf() });
    await main().findByText('com.acme.scanner');
    await settled();
    const table = within(screen.getByRole('table', { name: 'Library' }));
    expect(table.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['App', 'Version', 'Source', 'Checksum', 'Used in', 'Actions']);
  });

  it('places an extension column among core’s, its element header in place', async () => {
    open('/apps');
    await main().findByText('com.acme.scanner');
    await settled();
    const table = within(screen.getByRole('table', { name: 'Library' }));
    expect(table.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['App', 'Version', 'Pkg', 'Source', 'Checksum', 'Used in', 'Actions']);
    expect(table.getByTitle('Package length')).toBeTruthy();
  });

  it('hands an app’s row actions helpers they cannot change', async () => {
    let frozen: boolean | undefined;
    const peek = ext('peek', [
      {
        slot: 'list.rowAction', id: 'peek', list: 'apps', order: ORDER.appsRowAction.checksum + 50,
        item: (_app, helpers) => {
          frozen = Object.isFrozen(helpers);
          return null;
        },
      },
    ]);
    open('/apps', { registry: registryOf(peek) });
    await main().findByText('com.acme.scanner');
    await settled();
    fireEvent.click(main().getByRole('button', { name: 'More for Notes' }));
    await screen.findByRole('menu');
    expect(frozen).toBe(true);
  });

  it('adds an extension row action to an app’s menu', async () => {
    const pin = ext('pin', [
      { slot: 'list.rowAction', id: 'pin', list: 'apps', order: ORDER.appsRowAction.checksum + 50, item: (app) => ({ key: 'pin', label: `Pin ${app.name}`, onSelect: () => {} }) },
    ]);
    open('/apps', { registry: registryOf(pin) });
    await main().findByText('com.acme.scanner');
    await settled();
    fireEvent.click(main().getByRole('button', { name: 'More for Notes' }));
    expect(within(await screen.findByRole('menu')).getAllByRole('menuitem').map((m) => m.textContent)).toEqual([
      'SHA-256 checksum', 'Pin Notes', 'Remove from library',
    ]);
  });
});
