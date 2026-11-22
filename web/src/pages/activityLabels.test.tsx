// The activity wording on the Overview feed and the device's Activity tab: a command result names its command and
// step (from the device's command history), kiosk exit and return have labels, and the Overview shows an event's own
// detail, never the device's description in its place. Rendered through the real routes, against a stubbed API.
import { act, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const NOW = Date.now();
const MIN = 60_000;
const DEVICES = [{ id: 1, number: 'WH-1', description: 'Front desk', configurationId: 3, lastUpdate: NOW }];
const EVENTS = [
  { id: 1, type: 'commandResult', ts: NOW - 1 * MIN, detail: '1234:done' },
  { id: 2, type: 'commandResult', ts: NOW - 2 * MIN, detail: '1300:failed' },
  { id: 3, type: 'kioskExit', ts: NOW - 3 * MIN, detail: 'exited on-device' },
  { id: 4, type: 'boot', ts: NOW - 4 * MIN, detail: null },
  { id: 5, type: 'kioskReturn', ts: NOW - 5 * MIN, detail: 'returned on-device' },
];
const COMMANDS = [
  { id: 1234, type: 'device.lock', status: 'done', completedAt: NOW - MIN },
  { id: 1300, type: 'app.install', status: 'failed', subject: 'com.acme.scan', detail: 'checksum mismatch', completedAt: NOW - 2 * MIN },
];

function api(path: string, init?: RequestInit): unknown {
  const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
  if (p === '/devices/search') return { devices: { items: DEVICES, totalItemsCount: 1 }, configurations: { 3: { id: 3, name: 'Warehouse' } } };
  if (p === '/devices/number/WH-1') return DEVICES[0];
  if (p === '/configurations/search') return [{ id: 3, name: 'Warehouse' }];
  if (p === '/configurations/3') return { id: 3, name: 'Warehouse', kioskMode: false };
  if (p.endsWith('/syncSummary')) return [{ configurationId: 3, total: 1, inSync: 1, outOfSync: 0, unsupported: 0, neverSeen: 0, deferred: 0 }];
  if (p === '/agent/v1/devices/WH-1/events') return EVENTS;
  if (p === '/agent/v1/devices/WH-1/commands' && init?.method !== 'POST') return COMMANDS;
  if (p.endsWith('/locations')) return [];
  return null;
}

afterEach(() => vi.restoreAllMocks());

/** `minutes` later, the tab comes back into view: a refresh, a full one from 5 minutes on (useOverviewData). */
function returnLater(minutes: number) {
  vi.spyOn(Date, 'now').mockReturnValue(NOW + minutes * MIN);
  act(() => void document.dispatchEvent(new Event('visibilitychange')));
}

/** The API stub with the command histories held until `release()`; `calls(part)` counts requests to a path. */
function holdHistories() {
  const fetch = stubApi(api);
  const base = fetch.getMockImplementation()!;
  let release = () => {};
  let held = new Promise<void>((r) => (release = r));
  fetch.mockImplementation(async (input, init) => {
    if (String(input).includes('/commands')) await held;
    return base(input, init);
  });
  return {
    release: () => release(),
    /** Hold the next histories too. */
    hold: () => (held = new Promise<void>((r) => (release = r))),
    calls: (part: string) => fetch.mock.calls.filter(([p]) => String(p).includes(part)).length,
  };
}

describe('activity wording', () => {
  it('Overview: names the command and its result, labels kiosk exit and return, never shows the description', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { path: '/' });
    const feed = within(await screen.findByRole('list', { name: 'Recent activity' }, LOAD));
    await feed.findByText('Lock: done', undefined, LOAD);
    const rows = feed.getAllByRole('listitem').map((li) => li.textContent ?? '');
    expect(rows[0]).toContain('WH-1 ran a command Lock: done');
    expect(rows[1]).toContain('Install app: failed · com.acme.scan · checksum mismatch');
    expect(rows[2]).toContain('WH-1 left kiosk mode With the exit PIN, on the device');
    expect(rows[4]).toContain('WH-1 returned to kiosk mode From the device');
    expect(rows.some((r) => r.includes('Front desk'))).toBe(false);
    expect(rows.some((r) => r.includes('1234') || r.includes('kioskExit'))).toBe(false);
    await settled();
  });

  it('Overview: the feed shows in full before the command histories come, and names the commands once they do', async () => {
    const fetch = stubApi(api);
    const base = fetch.getMockImplementation()!;
    let release = () => {};
    const held = new Promise<void>((r) => (release = r));
    fetch.mockImplementation(async (input, init) => {
      if (String(input).includes('/commands')) await held;
      return base(input, init);
    });
    renderInConsole(<AppRoutes />, { path: '/' });
    const feed = within(await screen.findByRole('list', { name: 'Recent activity' }, LOAD));
    await feed.findByText('Command #1234: done', undefined, LOAD);
    expect(feed.getByText('Command #1300: failed')).toBeTruthy();
    expect(feed.getAllByRole('listitem').length).toBe(5);
    expect(feed.getByText('With the exit PIN, on the device')).toBeTruthy();
    release();
    await feed.findByText('Lock: done', undefined, LOAD);
    await settled();
  });

  it('Overview: a full refresh keeps the command names while the histories reload (no flicker)', async () => {
    const api_ = holdHistories();
    renderInConsole(<AppRoutes />, { path: '/' });
    api_.release();
    const feed = within(await screen.findByRole('list', { name: 'Recent activity' }, LOAD));
    await feed.findByText('Lock: done', undefined, LOAD);
    await settled();

    api_.hold();
    const events = api_.calls('/events');
    returnLater(6);
    await waitFor(() => expect(api_.calls('/events')).toBeGreaterThan(events), LOAD);
    await waitFor(() => expect(api_.calls('/commands')).toBe(2), LOAD); // the new feed asked for the history again
    expect(feed.getByText('Lock: done')).toBeTruthy();
    expect(feed.queryByText('Command #1234: done')).toBeNull();
    api_.release();
    await settled();
    expect(feed.getByText('Lock: done')).toBeTruthy();
  });

  it('Overview: a light refresh while the histories load doesn’t drop them', async () => {
    const api_ = holdHistories();
    renderInConsole(<AppRoutes />, { path: '/' });
    const feed = within(await screen.findByRole('list', { name: 'Recent activity' }, LOAD));
    await feed.findByText('Command #1234: done', undefined, LOAD);

    const lists = api_.calls('/devices/search');
    returnLater(2);
    await waitFor(() => expect(api_.calls('/devices/search')).toBeGreaterThan(lists), LOAD);
    api_.release();
    await feed.findByText('Lock: done', undefined, LOAD);
    await settled();
  });

  it('device Activity tab: the same wording', async () => {
    stubApi(api);
    renderInConsole(<AppRoutes />, { path: '/devices/WH-1?tab=activity' });
    const list = within(await screen.findByRole('list', { name: 'Device events' }, LOAD));
    await waitFor(() => expect(list.getByText('Lock: done')).toBeTruthy(), LOAD);
    expect(list.getByText('Install app: failed · com.acme.scan · checksum mismatch')).toBeTruthy();
    expect(list.getByText('Left kiosk mode')).toBeTruthy();
    expect(list.getByText('Returned to kiosk mode')).toBeTruthy();
    expect(list.queryByText('kioskExit')).toBeNull();
    await settled();
  });
});
