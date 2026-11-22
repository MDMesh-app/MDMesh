// The Library's row actions: a role whose only entry is Remove from library keeps it in the row's "…" (a lone
// destructive action is never a bare button), and removing an app moves focus to the next row, not to the page.
// Rendered through the real routes, against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../api/auth';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';

const LOAD = { timeout: 10_000 };
const SCANNER = { id: 1, name: 'Scanner', pkg: 'com.acme.scanner', version: '1.0', type: 'app', url: 'https://cdn.example.com/scanner.apk', hasApkHash: true };
const LEGACY = { id: 2, name: 'Legacy', pkg: 'com.acme.legacy', version: '0.9', type: 'app', url: 'https://cdn.example.com/legacy.apk', hasApkHash: true };

/** May remove library records (edit_applications) but not set checksums or push to devices: Remove is its one entry. */
const REMOVER: AuthUser = {
  id: 40,
  login: 'remover',
  userRole: { id: 40, permissions: ['applications', 'edit_applications'].map((name) => ({ name })) },
};

afterEach(() => vi.restoreAllMocks());

function open() {
  let library = [SCANNER, LEGACY];
  stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest/private', '');
    if (p === '/applications/search') return library;
    if (p.startsWith('/applications/configurations/')) return [];
    if (p === '/applications/1' && init?.method === 'DELETE') library = library.filter((a) => a.id !== 1);
    return null;
  });
  renderInConsole(<AppRoutes />, { path: '/apps', user: REMOVER });
}

const main = () => within(screen.getByRole('main'));

/** The Library has loaded: its rows, every row's Used in, and every call answered. */
async function loaded() {
  await main().findByText('com.acme.scanner', undefined, LOAD);
  await settled();
}

describe('Library row actions', () => {
  it('keeps a lone Remove from library in the row’s “…”, not as a button', async () => {
    open();
    await loaded();
    expect(main().queryByRole('button', { name: /^Remove from library/ })).toBeNull();
    fireEvent.click(main().getByRole('button', { name: 'More for Scanner' }));
    const menu = within(await screen.findByRole('menu'));
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Remove from library']);
  });

  it('after removing an app, focus moves to the next row; a cancel returns it to the “…”', async () => {
    open();
    await loaded();
    const more = main().getByRole('button', { name: 'More for Scanner' });
    const pick = async () => {
      more.focus();
      fireEvent.keyDown(more, { key: 'Enter' });
      fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Remove from library' }));
      return within(await screen.findByRole('alertdialog', { name: 'Remove Scanner from the library?' }, LOAD));
    };

    fireEvent.click((await pick()).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(more));

    fireEvent.click((await pick()).getByRole('button', { name: 'Remove from library' }));
    await waitFor(() => expect(main().queryByText('com.acme.scanner')).toBeNull(), LOAD);
    const legacyRow = main().getByText('com.acme.legacy').closest('tr')!;
    await waitFor(() => expect(legacyRow.contains(document.activeElement)).toBe(true));
    expect(await screen.findByText('Removed from Library', undefined, LOAD)).toBeTruthy();
    await settled();
  });
});
