// The Configurations list: one table, "New configuration" creating directly unless there are templates to choose
// from (K7), and the Delete confirmation naming its verb. Rendered through the real routes against a stubbed API.
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderInConsole } from '../test/console';

const WAREHOUSE = { id: 7, name: 'Warehouse', description: 'Scanners and shared handhelds', kioskMode: false };
const TEMPLATE = { id: 2, name: 'MIUI (Xiaomi Redmi)', kioskMode: false };

function open(configs: Array<Record<string, unknown>>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const p = new URL(String(input), 'http://console').pathname.replace('/rest', '');
      let data: unknown = null;
      if (p === '/private/configurations/search') data = configs;
      else if (p.startsWith('/private/configurations/applications/')) data = [];
      else if (p === '/private/configurations/7') data = { ...WAREHOUSE, extensions: {} };
      else if (p === '/private/configurations/2') data = { ...TEMPLATE, extensions: {} };
      else if (p === '/private/agent/v1/configurations/syncSummary') {
        data = [{ configurationId: 7, total: 0, inSync: 0, outOfSync: 0, unsupported: 0, neverSeen: 0 }];
      } else if (p.startsWith('/private/applications/search')) data = [];
      return new Response(JSON.stringify({ status: 'OK', message: null, data }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
  renderInConsole(<AppRoutes />, { path: '/configurations' });
}

describe('configurations list', () => {
  it('lists configurations as cards, with their description and actions', async () => {
    open([WAREHOUSE]);
    const list = await screen.findByRole('list', { name: 'Configurations' });
    const card = within(within(list).getByRole('article', { name: 'Warehouse' }));
    expect(card.getByText('Scanners and shared handhelds')).toBeTruthy();
    expect(card.getByRole('link', { name: /Edit/ }).getAttribute('href')).toBe('/configurations/7');
    expect(card.getByRole('button', { name: /Duplicate/ })).toBeTruthy();
    expect(card.getByRole('button', { name: 'More for Warehouse' })).toBeTruthy();
  });

  it('with no template, New configuration opens a blank one directly', async () => {
    open([WAREHOUSE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    fireEvent.click(screen.getByRole('button', { name: 'New configuration' }));
    expect(await screen.findByRole('button', { name: 'Create configuration' })).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
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
  });

  it('Delete confirms with “Delete configuration”', async () => {
    open([WAREHOUSE]);
    await screen.findByRole('link', { name: 'Warehouse' });
    fireEvent.click(screen.getByRole('button', { name: 'More for Warehouse' }));
    fireEvent.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Delete' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete configuration?' });
    expect(within(dialog).getByRole('button', { name: 'Delete configuration' })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });
});
