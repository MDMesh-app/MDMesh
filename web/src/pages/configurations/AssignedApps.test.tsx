// The configuration editor's assigned apps with search, the On devices filter and sort: what they show, and that the
// edits made through them, the changed count, Discard and the save are the same as without them. Rendered through the
// real routes against a stubbed API.
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ConfigApp } from '../../api/configurations';
import { AppRoutes } from '../../App';
import { renderInConsole } from '../../test/console';
import { APP_CONTROLS_THRESHOLD } from './AssignedApps';

const LOAD = { timeout: 3000 };

/** [id, name, pkg, action] — 6 Install, 2 Do not install, 2 Remove; stored in this (unsorted) order. */
const TEN: Array<[number, string, string, number]> = [
  [1, 'Chrome', 'com.android.chrome', 1],
  [2, 'Zebra DataWedge', 'com.symbol.datawedge', 1],
  [3, 'Field Ops', 'com.acme.fieldops', 1],
  [4, 'Microsoft Teams', 'com.microsoft.teams', 0],
  [5, 'Signage Player', 'com.acme.signage', 1],
  [6, 'Barcode Reader', 'org.scan.barcode', 2],
  [7, 'Maps', 'com.google.android.apps.maps', 1],
  [8, 'Camera', 'com.acme.camera', 0],
  [9, 'Notes', 'com.acme.notes', 1],
  [10, 'Wallet', 'com.acme.wallet', 2],
];

const assigned = (list = TEN): ConfigApp[] =>
  list.map(([id, name, pkg, action]) => ({ id, name, pkg, version: '1.0', action, showIcon: true, usedVersionId: 100 + id, remove: false }));

/** Configuration 7 with `apps` assigned, and one more app in the library (Agenda) to add. */
function open(apps = assigned()) {
  const puts: Record<string, unknown>[] = [];
  const answer = (path: string, init?: RequestInit): unknown => {
    const p = new URL(path, 'http://console').pathname.replace('/rest', '');
    if (p === '/private/configurations' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      puts.push(body);
      return body;
    }
    if (p === '/private/configurations/7') return { id: 7, name: 'Warehouse', kioskMode: false, extensions: {} };
    if (p === '/private/configurations/applications/7') return apps.map((a) => ({ ...a, selected: true }));
    if (p.startsWith('/private/applications/search')) {
      return [{ id: 11, name: 'Agenda', pkg: 'com.acme.agenda', version: '2.0', type: 'app', latestVersion: 111 }];
    }
    if (p === '/private/agent/v1/configurations/syncSummary') {
      return [{ configurationId: 7, total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
    }
    return null;
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const out = answer(String(input), init);
      return new Response(JSON.stringify({ status: 'OK', message: null, data: out ?? null }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  renderInConsole(<AppRoutes />, { path: '/configurations/7' });
  return { puts };
}

const table = () => document.querySelector<HTMLTableElement>('.v2-cfged-apps__table')!;
/** The app names in the table, in order. */
const names = () => [...table().querySelectorAll('.v2-cfged-app__name')].map((n) => n.firstChild?.textContent);
const search = () => screen.getByRole<HTMLInputElement>('searchbox', { name: 'Search assigned apps' });
const filter = () => screen.getByRole('radiogroup', { name: 'On devices' });
const pick = (name: RegExp) => fireEvent.click(within(filter()).getByRole('radio', { name }));
const sortBy = (label: string) =>
  fireEvent.change(screen.getByRole('combobox', { name: 'Sort' }), {
    target: { value: { Name: 'name', Package: 'pkg', 'On devices': 'state' }[label] },
  });
const actionOf = (name: string) => screen.getByRole<HTMLSelectElement>('combobox', { name: `What happens to ${name}` });
const saveBar = () => screen.queryByRole('region', { name: 'Unsaved changes' });
const live = () => document.querySelector('.v2-cfged-appsbar [aria-live="polite"]')!;
const loaded = () => screen.findByRole('combobox', { name: 'What happens to Chrome' }, LOAD);

async function save() {
  const button = within(saveBar()!).getByRole<HTMLButtonElement>('button', { name: 'Save and apply' });
  await waitFor(() => expect(button.disabled).toBe(false));
  fireEvent.click(button);
}

describe('assigned apps: search, filter and sort', () => {
  it('shows no controls for a list of the threshold or fewer, in stored order', async () => {
    open(assigned(TEN.slice(0, APP_CONTROLS_THRESHOLD)));
    await loaded();
    expect(screen.queryByRole('searchbox', { name: 'Search assigned apps' })).toBeNull();
    expect(screen.queryByRole('radiogroup', { name: 'On devices' })).toBeNull();
    expect(screen.queryByRole('combobox', { name: 'Sort' })).toBeNull();
    expect(names()).toEqual(TEN.slice(0, APP_CONTROLS_THRESHOLD).map((a) => a[1]));
  });

  it('shows them one app past it; the list sorts by name', async () => {
    open(assigned(TEN.slice(0, APP_CONTROLS_THRESHOLD + 1)));
    await loaded();
    expect(search()).toBeTruthy();
    expect(names()).toEqual(['Barcode Reader', 'Camera', 'Chrome', 'Field Ops', 'Maps', 'Microsoft Teams', 'Notes', 'Signage Player', 'Zebra DataWedge']);
  });

  it('searches name and package, trimmed and in any case, and says how many show', async () => {
    open();
    await loaded();
    expect(live().textContent).toBe('');
    fireEvent.change(search(), { target: { value: '  CHROME ' } });
    expect(names()).toEqual(['Chrome']);
    expect(live().textContent).toBe('Showing 1 of 10 apps');
    // Name or package, not text that runs across both.
    fireEvent.change(search(), { target: { value: 'chrome com' } });
    expect(names()).toEqual([]);
    fireEvent.change(search(), { target: { value: 'com.acme.' } });
    expect(names()).toEqual(['Camera', 'Field Ops', 'Notes', 'Signage Player', 'Wallet']);
    fireEvent.change(search(), { target: { value: '' } });
    expect(names()).toHaveLength(10);
    expect(live().textContent).toBe('');
  });

  it('filters by what happens on devices, each segment with its count', async () => {
    open();
    await loaded();
    const radios = within(filter()).getAllByRole('radio').map((r) => r.textContent);
    expect(radios).toEqual(['All 10', 'Install 6', 'Do not install 2', 'Remove 2']);
    pick(/^Remove/);
    expect(names()).toEqual(['Barcode Reader', 'Wallet']);
    expect(live().textContent).toBe('Showing 2 of 10 apps');
    pick(/^Do not install/);
    expect(names()).toEqual(['Camera', 'Microsoft Teams']);
    // Search and filter together.
    fireEvent.change(search(), { target: { value: 'teams' } });
    expect(names()).toEqual(['Microsoft Teams']);
  });

  it('sorts by name, package and what happens on devices', async () => {
    open();
    await loaded();
    sortBy('Package');
    expect(names()).toEqual([
      'Camera', 'Field Ops', 'Notes', 'Signage Player', 'Wallet', 'Chrome', 'Maps', 'Microsoft Teams', 'Zebra DataWedge', 'Barcode Reader',
    ]);
    sortBy('On devices');
    expect(names()).toEqual([
      'Chrome', 'Field Ops', 'Maps', 'Notes', 'Signage Player', 'Zebra DataWedge', 'Camera', 'Microsoft Teams', 'Barcode Reader', 'Wallet',
    ]);
    sortBy('Name');
    expect(names()[0]).toBe('Barcode Reader');
  });

  it('keeps a row edited to a state the filter hides until the filter changes, and keeps the edit', async () => {
    open();
    await loaded();
    pick(/^Install/);
    expect(names()).toHaveLength(6);
    fireEvent.change(actionOf('Chrome'), { target: { value: '2' } });
    // Still there, with its new state; the counts follow the edit.
    expect(names()).toContain('Chrome');
    expect(actionOf('Chrome').value).toBe('2');
    expect(within(filter()).getAllByRole('radio').map((r) => r.textContent)).toEqual(['All 10', 'Install 5', 'Do not install 2', 'Remove 3']);
    expect(saveBar()?.textContent).toContain('1 unsaved change');
    // A new view files it by its new state.
    pick(/^Remove/);
    expect(names()).toEqual(['Barcode Reader', 'Chrome', 'Wallet']);
    pick(/^Install/);
    expect(names()).not.toContain('Chrome');
    // The edit is still there under All.
    pick(/^All/);
    expect(actionOf('Chrome').value).toBe('2');
    expect(saveBar()?.textContent).toContain('1 unsaved change');
  });

  it('keeps a row in place when sorted by state and edited', async () => {
    open();
    await loaded();
    sortBy('On devices');
    fireEvent.change(actionOf('Chrome'), { target: { value: '2' } });
    expect(names()[0]).toBe('Chrome');
    sortBy('Name');
    sortBy('On devices');
    expect(names().slice(-3)).toEqual(['Barcode Reader', 'Chrome', 'Wallet']);
  });

  it('shows an app added under a filter that hides it, and files it by its state once the view changes', async () => {
    open();
    await loaded();
    pick(/^Remove/);
    fireEvent.click(screen.getByRole('button', { name: 'Add apps' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add apps' }, LOAD);
    fireEvent.click(await within(dialog).findByRole('checkbox', { name: /Agenda/ }, LOAD));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add 1' }));
    await waitFor(() => expect(names()).toEqual(['Agenda', 'Barcode Reader', 'Wallet']));
    pick(/^Install/);
    expect(names()).toContain('Agenda');
  });

  it('says when nothing matches, and Clear shows every app again', async () => {
    open();
    await loaded();
    pick(/^Remove/);
    fireEvent.change(search(), { target: { value: 'chrome' } });
    expect(screen.getByText('No apps match')).toBeTruthy();
    expect(live().textContent).toBe('Showing 0 of 10 apps');
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(names()).toHaveLength(10);
    expect(search().value).toBe('');
    expect(within(filter()).getByRole('radio', { name: /^All/ }).getAttribute('aria-checked')).toBe('true');
    expect(document.activeElement).toBe(search());
  });

  it('Discard under a filter restores every app, files them by their restored state and leaves no change', async () => {
    open();
    await loaded();
    pick(/^Install/);
    fireEvent.change(actionOf('Chrome'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Remove Maps' }));
    // A new view files Chrome under Do not install: Install no longer shows it.
    pick(/^Remove/);
    pick(/^Install/);
    expect(names()).not.toContain('Chrome');
    expect(saveBar()?.textContent).toContain('1 unsaved change');
    fireEvent.click(within(saveBar()!).getByRole('button', { name: 'Discard' }));
    await waitFor(() => expect(saveBar()).toBeNull());
    expect(names()).toEqual(['Chrome', 'Field Ops', 'Maps', 'Notes', 'Signage Player', 'Zebra DataWedge']);
    expect(actionOf('Chrome').value).toBe('1');
  });

  it('starts the view again when the list drops to the threshold and grows past it', async () => {
    open(assigned(TEN.slice(0, APP_CONTROLS_THRESHOLD + 1)));
    await loaded();
    pick(/^Remove/);
    fireEvent.change(search(), { target: { value: 'barcode' } });
    expect(names()).toEqual(['Barcode Reader']);
    // Down to the threshold: no controls, every app in stored order.
    fireEvent.click(screen.getByRole('button', { name: 'Remove Barcode Reader' }));
    expect(screen.queryByRole('radiogroup', { name: 'On devices' })).toBeNull();
    expect(names()).toHaveLength(APP_CONTROLS_THRESHOLD);
    fireEvent.change(actionOf('Microsoft Teams'), { target: { value: '2' } });
    // Past it again: no search or filter left from before, and the filter sees the state changed meanwhile.
    fireEvent.click(screen.getByRole('button', { name: 'Add apps' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add apps' }, LOAD);
    fireEvent.click(await within(dialog).findByRole('checkbox', { name: /Agenda/ }, LOAD));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add 1' }));
    await waitFor(() => expect(screen.queryByRole('radiogroup', { name: 'On devices' })).toBeTruthy());
    expect(search().value).toBe('');
    expect(within(filter()).getByRole('radio', { name: /^All/ }).getAttribute('aria-checked')).toBe('true');
    expect(names()).toHaveLength(APP_CONTROLS_THRESHOLD + 1);
    pick(/^Remove/);
    expect(names()).toEqual(['Microsoft Teams']);
  });

  it('a repeated value is no change of view: an edited row stays', async () => {
    open();
    await loaded();
    pick(/^Install/);
    fireEvent.change(actionOf('Chrome'), { target: { value: '2' } });
    // Sort is already Name; Segmented reports its focused option again on Home/End (here: Install).
    fireEvent.change(screen.getByRole('combobox', { name: 'Sort' }), { target: { value: 'name' } });
    expect(names()).toContain('Chrome');
    pick(/^Do not install/);
    pick(/^Install/);
    expect(names()).not.toContain('Chrome');
  });

  it('saves every assigned app, with the edits, whatever the search and filter show', async () => {
    const plain = open();
    await loaded();
    fireEvent.change(actionOf('Wallet'), { target: { value: '1' } });
    await save();
    await waitFor(() => expect(plain.puts).toHaveLength(1));
    expect((plain.puts[0].applications as ConfigApp[]).length).toBe(10);
    cleanup();

    const filtered = open();
    await loaded();
    pick(/^Remove/);
    fireEvent.change(search(), { target: { value: 'wallet' } });
    fireEvent.change(actionOf('Wallet'), { target: { value: '1' } });
    expect(names()).toEqual(['Wallet']);
    await save();
    await waitFor(() => expect(filtered.puts).toHaveLength(1));
    expect(filtered.puts[0]).toEqual(plain.puts[0]);
  });
});
