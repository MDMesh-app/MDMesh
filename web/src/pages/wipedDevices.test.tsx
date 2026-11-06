// Wiped devices (#137): out of the Devices list's default view, its counts and its bulk actions, listed under Wiped;
// neither offline nor needing attention on the Overview; a notice and no commands on the device page. And what a sync
// says when the device has no live connection. Rendered through the real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, stubApi } from '../test/console';

// The pages answer after several requests, which a loaded machine stretches: generous waits for the data, and test
// timeouts to match (a test that times out keeps clicking in the next one's page).
const LOAD = { timeout: 10_000 };
const SLOW = 20_000;
const NOW = Date.now();
const HOUR = 3600_000;
const DEVICES = [
  { id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW },
  { id: 2, number: 'WH-2', description: 'Back office', lastUpdate: NOW - 2 * HOUR },
  { id: 3, number: 'WH-3', description: 'Dock', lastUpdate: NOW },
  // Wiped a minute ago: by its last check-in alone it would show online for nine more minutes.
  { id: 9, number: 'WH-9', description: 'Old scanner', configurationId: 3, lastUpdate: NOW - 60_000, wipedAt: NOW - 60_000 },
];

/** The fleet, WH-1 and WH-9 out of sync (so the Overview offers Sync now), and `sync` as every wake's answer. */
function api(sync: unknown, devices: typeof DEVICES = DEVICES) {
  return (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    const post = init?.method === 'POST';
    if (p === '/devices/search') {
      const value = (JSON.parse(String(init?.body ?? '{}')) as { value?: string }).value ?? '';
      const items = devices.filter((d) => d.number.includes(value));
      return { devices: { items, totalItemsCount: items.length }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
    }
    const byNumber = /^\/devices\/number\/([^/]+)$/.exec(p);
    if (byNumber) return devices.find((d) => d.number === decodeURIComponent(byNumber[1])) ?? null;
    if (p === '/configurations/search') return [{ id: 3, name: 'Warehouse' }];
    if (p === '/configurations/3') return { id: 3, name: 'Warehouse', kioskMode: false };
    if (p === '/agent/v1/configurations/syncSummary') {
      return [{ configurationId: 3, total: 2, inSync: 0, outOfSync: 2, unsupported: 0, neverSeen: 0, deferred: 0 }];
    }
    if (p.endsWith('/configStatus')) {
      return { configurationId: 3, currentRevision: 'b', appliedRevision: 'a', inSync: false, supported: true, lastCommand: { id: 5, status: 'done' } };
    }
    if (p.endsWith('/sync') && post) return sync;
    if (p.endsWith('/commands') || p.endsWith('/events') || p.endsWith('/locations')) return post ? { id: 42 } : [];
    return null;
  };
}

let here = '';
/** Where the console is. */
function Probe() {
  const location = useLocation();
  here = location.pathname + location.search;
  return null;
}

/** Open `path` with `sync` as the answer to a wake (null: an older server's empty one). */
function open(path: string, sync: unknown = null, devices: typeof DEVICES = DEVICES) {
  const fetch = stubApi(api(sync, devices));
  renderInConsole(
    <>
      <AppRoutes />
      <Probe />
    </>,
    { path },
  );
  return fetch;
}

const main = () => within(screen.getByRole('main'));
const table = () => within(main().getByRole('table', { name: 'Devices' }));
const statusFilter = () => within(main().getByRole('radiogroup', { name: 'Status' }));
const toastOf = (text: string) => screen.getByText(text).closest('.toast');
const posts = (fetch: ReturnType<typeof stubApi>, suffix: string) =>
  fetch.mock.calls.filter(([input, init]) => String(input).endsWith(suffix) && init?.method === 'POST');

describe('wiped devices on Devices', () => {
  it('leaves them out of All, its counts and the bulk actions, and lists them under Wiped', async () => {
    open('/devices');
    await main().findByRole('link', { name: /Front desk/ }, LOAD);
    await waitFor(
      () =>
        expect(statusFilter().getAllByRole('radio').map((r) => r.textContent)).toEqual([
          'All 3', 'Online 1', 'Attention 1', 'Offline 1', 'Wiped 1',
        ]),
      LOAD,
    );
    expect(table().queryByRole('link', { name: /Old scanner/ })).toBeNull();
    expect(document.querySelector('.v2-pagehead')?.textContent).toContain('1 of 3 online now');

    // Select all takes the devices All shows, not the wiped one.
    fireEvent.click(main().getByRole('checkbox', { name: 'Select all on this page' }));
    expect((await screen.findByRole('region', { name: 'Selection' })).textContent).toContain('3 selected');

    fireEvent.click(statusFilter().getByRole('radio', { name: /^Wiped/ }));
    await waitFor(() => expect(here).toBe('/devices?status=wiped'), LOAD);
    expect(table().getAllByRole('link').map((l) => l.textContent)).toEqual([expect.stringContaining('Old scanner')]);
    expect(table().getByText('Wiped 1m ago')).toBeTruthy();
    // No checkboxes, so no bulk bar: the selection made under All is gone too.
    expect(main().queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Selection' })).toBeNull();
    // Commands are greyed out; Delete stays.
    expect(table().getByRole('button', { name: 'Sync WH-9 now' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(table().getByRole('button', { name: 'More actions for WH-9' }));
    const menu = within(await screen.findByRole('menu'));
    const disabled = Object.fromEntries(menu.getAllByRole('menuitem').map((m) => [m.textContent, m.hasAttribute('disabled')]));
    expect(disabled).toEqual({ 'Lock device': true, 'Ring device': true, 'Change configuration…': false, 'Delete…': false });
  }, SLOW);

  it('does not count a wiped device as a duplicate of its re-enrolled twin', async () => {
    // WH-9 was wiped and the phone enrolled again as WH-4: one live row, so nothing to merge.
    const twins = [
      ...DEVICES.map((d) => (d.id === 9 ? { ...d, hardwareId: 'hw-1' } : d)),
      { id: 4, number: 'WH-4', description: 'Re-enrolled', lastUpdate: NOW, hardwareId: 'hw-1' },
    ] as typeof DEVICES;
    open('/devices', null, twins);
    await main().findByRole('link', { name: /Re-enrolled/ }, LOAD);
    expect(main().queryByRole('button', { name: /^Duplicates/ })).toBeNull();
  }, SLOW);

  it('says where a search’s wiped match is when All shows nothing', async () => {
    open('/devices');
    await main().findByRole('link', { name: /Front desk/ }, LOAD);
    fireEvent.change(main().getByRole('searchbox', { name: 'Search devices' }), { target: { value: 'WH-9' } });
    expect(await main().findByText('Wiped devices show under Wiped.', undefined, LOAD)).toBeTruthy();
  }, SLOW);
});

describe('what a sync says', () => {
  it.each([
    ['a device without a live connection', { connected: false }, 'WH-3 isn’t connected right now', 'warn'],
    ['a connected device', { connected: true }, 'Sync requested', 'ok'],
    ['an older server, which does not say', null, 'Sync requested', 'ok'],
  ])('on the Devices list, for %s', async (_, answer, title, kind) => {
    open('/devices', answer);
    fireEvent.click(await main().findByRole('button', { name: 'Sync WH-3 now' }, LOAD));
    expect(await screen.findByText(title, undefined, LOAD)).toBeTruthy();
    expect(toastOf(title)?.classList.contains(kind)).toBe(true);
    if (kind === 'warn') expect(screen.getByText('It syncs at its next check-in.')).toBeTruthy();
  }, SLOW);

  it('after a command from the row menu, says when the device will get it', async () => {
    open('/devices', { connected: false });
    fireEvent.click(await main().findByRole('button', { name: 'More actions for WH-3' }, LOAD));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Lock device' }));
    expect(await screen.findByText('Lock device queued', undefined, LOAD)).toBeTruthy();
    expect(await screen.findByText('WH-3 isn’t connected right now', undefined, LOAD)).toBeTruthy();
    expect(screen.getByText('It gets the command at its next check-in.')).toBeTruthy();
  }, SLOW);

  it('on the Overview’s Sync now', async () => {
    open('/', { connected: false });
    fireEvent.click(await main().findByRole('button', { name: 'Sync now: Front desk' }, LOAD));
    expect(await screen.findByText('WH-1 isn’t connected right now', undefined, LOAD)).toBeTruthy();
    expect(screen.queryByText('Sync requested')).toBeNull();
  }, SLOW);

  it(
    'on the device page’s Sync now',
    async () => {
      open('/devices/WH-1', { connected: false });
      const actions = within(await screen.findByRole('toolbar', { name: 'Quick actions' }, LOAD));
      await waitFor(() => expect(actions.getByRole('button', { name: 'Sync now' }).hasAttribute('disabled')).toBe(false), LOAD);
      fireEvent.click(actions.getByRole('button', { name: 'Sync now' }));
      expect(await screen.findByText('WH-1 isn’t connected right now', undefined, LOAD)).toBeTruthy();
      expect(toastOf('WH-1 isn’t connected right now')?.classList.contains('warn')).toBe(true);
    },
    SLOW,
  );
});

describe('wiped devices on the Overview', () => {
  it('counts them beside the total, not as offline, and never lists them as needing attention', async () => {
    open('/');
    const list = within(await main().findByRole('table', { name: 'Devices that need attention' }, LOAD));
    await list.findByText(/Front desk/, undefined, LOAD);
    expect(list.getByText(/Back office/)).toBeTruthy();
    expect(list.queryByText(/Old scanner/)).toBeNull();
    const offline = main().getByRole('link', { name: /^Offline/ });
    expect(offline.querySelector('.v2-fleet__count')?.textContent).toBe('1');
    expect(main().getByRole('link', { name: '1 wiped' }).getAttribute('href')).toBe('/devices?status=wiped');
  }, SLOW);

  it('says so when every device was wiped, not that every device is online', async () => {
    open('/', null, DEVICES.filter((d) => d.wipedAt != null));
    await main().findByText('No active devices', undefined, LOAD);
    expect(main().getAllByText('Every enrolled device was wiped.').length).toBeGreaterThan(0);
    expect(main().queryByText(/Every device is online/)).toBeNull();
    expect(main().queryByText('All clear')).toBeNull();
  }, SLOW);
});

describe('a configuration with only wiped devices', () => {
  const WIPED_ONLY = [{ configurationId: 3, total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0, deferred: 0, wiped: 1 }];
  const WHOLE = /1 device still uses this configuration, so the server will refuse to delete it/;
  const FILTER = /That includes 1 wiped device, listed under the Wiped filter on Devices/;

  /** The fleet's answers, with `summary` as the sync summary and the one wiped device as the whole fleet. */
  function openWith(path: string, summary: unknown) {
    const base = api(null, DEVICES.filter((d) => d.wipedAt != null));
    stubApi((p, init) => {
      if (p.includes('/configurations/syncSummary')) return summary;
      return p.includes('/configurations/applications/') ? [] : base(p, init);
    });
    renderInConsole(<AppRoutes />, { path });
  }

  it('says so on its card, and Delete warns before the server refuses', async () => {
    openWith('/configurations', WIPED_ONLY);
    await main().findByText('Used only by 1 wiped device', undefined, LOAD);
    expect(main().queryByText('Not used by any device yet')).toBeNull();
    fireEvent.click(main().getByRole('button', { name: 'More for Warehouse' }));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Delete…' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toMatch(WHOLE);
    expect(dialog.textContent).toMatch(FILTER);
  }, SLOW);

  it('says so in the editor, links to the wiped device, and Delete warns there too', async () => {
    openWith('/configurations/3', WIPED_ONLY);
    const link = await main().findByRole('link', { name: '1 wiped device' }, LOAD);
    expect(link.getAttribute('href')).toBe('/devices?status=wiped&config=3');
    expect(link.parentElement?.textContent).toMatch(/^Used only by 1 wiped device\.$/);
    expect(main().queryByText(/Not used by any device yet/)).toBeNull();
    fireEvent.change(main().getByRole('textbox', { name: /Description/ }), { target: { value: 'Old scanners' } });
    await waitFor(() => expect(screen.getByRole('region', { name: 'Unsaved changes' }).textContent).toMatch(/Used only by 1 wiped device\./), LOAD);
    expect(screen.queryByText(/No device uses this configuration yet/)).toBeNull();
    fireEvent.click(await main().findByRole('button', { name: 'Delete…' }, LOAD));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toMatch(WHOLE);
    expect(dialog.textContent).toMatch(FILTER);
  }, SLOW);

  it('counts wiped devices alongside live ones in the Delete warning', async () => {
    openWith('/configurations', [{ ...WIPED_ONLY[0], total: 2, inSync: 2 }]);
    await main().findByText('All 2 in sync', undefined, LOAD);
    fireEvent.click(main().getByRole('button', { name: 'More for Warehouse' }));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Delete…' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).toMatch(/3 devices still use this configuration/);
    expect(dialog.textContent).toMatch(FILTER);
  }, SLOW);

  it('leaves the Delete dialog without a warning when nothing uses it, and on an older server', async () => {
    openWith('/configurations', []);
    await main().findByText('Not used by any device yet', undefined, LOAD);
    fireEvent.click(main().getByRole('button', { name: 'More for Warehouse' }));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Delete…' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog.textContent).not.toMatch(/refuse/);
  }, SLOW);

  it('is not "All in sync" on the Overview', async () => {
    openWith('/', WIPED_ONLY);
    await main().findByText('Used only by 1 wiped device', undefined, LOAD);
    expect(main().queryByText('All in sync')).toBeNull();
  }, SLOW);
});

describe('a wiped device’s page', () => {
  it(
    'says it was wiped and greys out the commands',
    async () => {
      const fetch = open('/devices/WH-9');
      const actions = within(await screen.findByRole('toolbar', { name: 'Quick actions' }, LOAD));
      expect(within(screen.getByRole('complementary', { name: 'Device' })).getByText('Wiped')).toBeTruthy();
      const notice = main().getByText(/The device erased itself/).closest('.v2-callout');
      expect(notice?.textContent).toMatch(
        /^Wiped .+\. The device erased itself, so it no longer reports\. Delete it from Devices, or keep it for its history\.$/,
      );
      expect(within(notice as HTMLElement).getByRole('link', { name: 'Devices' }).getAttribute('href')).toBe('/devices?status=wiped');
      for (const name of ['Sync now', 'Lock', 'More actions']) {
        expect(actions.getByRole('button', { name }).hasAttribute('disabled')).toBe(true);
      }
      expect(posts(fetch, '/sync')).toHaveLength(0);
    },
    SLOW,
  );

  it(
    'offers no Scan now on its Apps tab',
    async () => {
      const fetch = open('/devices/WH-9?tab=apps');
      expect(await screen.findByText('No recent scan', undefined, LOAD)).toBeTruthy();
      expect(within(screen.getByRole('tabpanel')).queryByRole('button', { name: 'Scan now' })).toBeNull();
      expect(posts(fetch, '/commands')).toHaveLength(0);
    },
    SLOW,
  );
});
