// The Configurations list: cards, "New configuration" creating directly unless there are templates to choose
// from (K7), and the Delete confirmation naming its verb. Rendered through the real routes against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole, settled, stubApi } from '../test/console';

const WAREHOUSE = { id: 7, name: 'Warehouse', description: 'Scanners and shared handhelds', kioskMode: false };
const TEMPLATE = { id: 2, name: 'MIUI (Xiaomi Redmi)', kioskMode: false };
const LOBBY = { id: 8, name: 'Lobby', kioskMode: false };

function open(configs: Array<Record<string, unknown>>, listed: Promise<void> = Promise.resolve()) {
  let list = configs;
  const fetch = stubApi((path, init) => {
    const p = new URL(path, 'http://console').pathname.replace('/rest', '');
    if (init?.method === 'DELETE') {
      // The server forgets it: the reload after a delete no longer lists it.
      const id = Number(p.split('/').pop());
      list = list.filter((c) => c.id !== id);
      return null;
    }
    if (p === '/private/configurations/search') return list;
    if (p.startsWith('/private/configurations/applications/')) return [];
    if (p === '/private/configurations/7') return { ...WAREHOUSE, extensions: {} };
    if (p === '/private/configurations/2') return { ...TEMPLATE, extensions: {} };
    if (p === '/private/agent/v1/configurations/syncSummary') {
      return [{ configurationId: 7, total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
    }
    if (p.startsWith('/private/applications/search')) return [];
    return null;
  });
  const base = fetch.getMockImplementation()!;
  fetch.mockImplementation(async (input, init) => {
    if (String(input).includes('/configurations/search')) await listed;
    return base(input, init);
  });
  renderInConsole(<AppRoutes />, { path: '/configurations' });
}

/** The page has finished loading: every call answered, no placeholder left. */
const loaded = () => settled();

/** Opens `name`'s "…" from the keyboard and picks Delete; returns the trigger and the confirmation dialog. */
async function askDelete(name: string) {
  const more = screen.getByRole('button', { name: `More for ${name}` });
  more.focus();
  fireEvent.keyDown(more, { key: 'Enter' });
  fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Delete' }));
  return { more, dialog: within(await screen.findByRole('alertdialog', { name: 'Delete configuration?' })) };
}

describe('configurations list', () => {
  it('lists configurations as cards, with their description and actions', async () => {
    open([WAREHOUSE]);
    const list = await screen.findByRole('list', { name: 'Configurations' });
    await loaded();
    const card = within(within(list).getByRole('article', { name: 'Warehouse' }));
    expect(card.getByText('Scanners and shared handhelds')).toBeTruthy();
    expect(card.getByRole('link', { name: /Edit/ }).getAttribute('href')).toBe('/configurations/7');
    expect(card.getByRole('button', { name: /Duplicate/ })).toBeTruthy();
    expect(card.getByRole('button', { name: 'More for Warehouse' })).toBeTruthy();
  });

  it('tags a kiosk configuration whose exit PIN predates the 6-to-12-digit rule, and no other', async () => {
    open([
      { ...WAREHOUSE, kioskMode: true, password: '4931' },
      { id: 8, name: 'Lobby', kioskMode: true, password: '482139' },
      { id: 9, name: 'Office', kioskMode: false, password: '4931' },
    ]);
    const list = await screen.findByRole('list', { name: 'Configurations' });
    await loaded();
    expect(within(within(list).getByRole('article', { name: 'Warehouse' })).getByText('Old PIN')).toBeTruthy();
    expect(within(within(list).getByRole('article', { name: 'Lobby' })).queryByText('Old PIN')).toBeNull();
    expect(within(within(list).getByRole('article', { name: 'Office' })).queryByText('Old PIN')).toBeNull();
  });

  it('has no kind filter with one kind only', async () => {
    open([WAREHOUSE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    await loaded();
    expect(screen.queryByRole('radiogroup', { name: 'Show' })).toBeNull();
  });

  it('shows All, Custom and Built-in with both kinds, and tags a template Built-in', async () => {
    open([WAREHOUSE, TEMPLATE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    await loaded();
    const show = screen.getByRole('radiogroup', { name: 'Show' });
    expect(within(show).getAllByRole('radio').map((r) => r.textContent)).toEqual(['All 2', 'Custom 1', 'Built-in 1']);
    expect(within(screen.getByRole('article', { name: 'MIUI (Xiaomi Redmi)' })).getByText('Built-in')).toBeTruthy();
  });

  it('shows each card action once: Edit and Duplicate in the footer, only Delete in the “…”', async () => {
    open([WAREHOUSE, TEMPLATE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    await loaded();
    fireEvent.click(screen.getByRole('button', { name: 'More for Warehouse' }));
    const menu = within(await screen.findByRole('menu'));
    expect(menu.getAllByRole('menuitem').map((m) => m.textContent)).toEqual(['Delete']);
    // A built-in can't be deleted: its card has no menu at all (View and Duplicate to edit are its buttons).
    const builtIn = within(screen.getByRole('article', { name: 'MIUI (Xiaomi Redmi)' }));
    expect(builtIn.queryByRole('button', { name: /^More for/ })).toBeNull();
    expect(builtIn.getByRole('button', { name: 'Duplicate to edit' })).toBeTruthy();
  });

  it('with no template, New configuration opens a blank one directly', async () => {
    open([WAREHOUSE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    fireEvent.click(screen.getByRole('button', { name: 'New configuration' }));
    expect(await screen.findByRole('button', { name: 'Create configuration' })).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    await loaded();
  });

  it('New configuration waits for the list, so templates arriving late still get the chooser', async () => {
    let arrive = () => {};
    open([WAREHOUSE, TEMPLATE], new Promise<void>((r) => (arrive = r)));
    const button = await screen.findByRole('button', { name: 'New configuration' });
    expect(button).toHaveProperty('disabled', true);
    fireEvent.click(button);
    expect(screen.queryByRole('button', { name: 'Create configuration' })).toBeNull();
    arrive();
    await waitFor(() => expect(button).toHaveProperty('disabled', false));
    fireEvent.click(button);
    expect(await screen.findByRole('dialog', { name: 'New configuration' })).toBeTruthy();
    await loaded();
  });

  it('with templates, New configuration asks where to start, then Create opens it', async () => {
    open([WAREHOUSE, TEMPLATE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    fireEvent.click(screen.getByRole('button', { name: 'New configuration' }));
    const dialog = await screen.findByRole('dialog', { name: 'New configuration' });
    expect(within(dialog).getByRole('radio', { name: /Blank configuration/ })).toHaveProperty('checked', true);
    fireEvent.click(within(dialog).getByRole('radio', { name: /Based on “MIUI/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('Based on “MIUI (Xiaomi Redmi)”. Not saved yet.')).toBeTruthy();
    await loaded();
  });

  it('Delete, from the keyboard, confirms with “Delete configuration”; a cancel returns focus to the “…”', async () => {
    open([WAREHOUSE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    await loaded();
    const { more, dialog } = await askDelete('Warehouse');
    expect(dialog.getByRole('button', { name: 'Delete configuration' })).toBeTruthy();
    fireEvent.click(dialog.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(more));
  });

  it('after a delete, focus moves to the next card, not to the page', async () => {
    open([WAREHOUSE, LOBBY]);
    await screen.findByRole('link', { name: 'Warehouse' });
    await loaded();
    const { dialog } = await askDelete('Warehouse');
    fireEvent.click(dialog.getByRole('button', { name: 'Delete configuration' }));
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Warehouse' })).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('link', { name: 'Lobby' })));
    expect(await screen.findByText('Configuration deleted')).toBeTruthy();
    await loaded();
  });
});
